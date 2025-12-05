/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package global.goldenera.node.core.sync;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.exceptions.GEFailedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockIngestionService {

	ReentrantLock masterChainLock;

	MeterRegistry registry;
	ChainQuery chainQueryService;
	BlockValidator blockValidationService;
	TxValidator txValidationService;
	StateProcessor stateProcessor;
	WorldStateFactory worldStateFactory;
	BlockStateTransitions blockStateTransitionService;
	BlockOrphanBufferService orphanBufferService;

	public enum IngestionResult {
		SUCCESS, ORPHAN_BUFFERED, GAP_DETECTED, FAILED, ALREADY_EXISTS
	}

	/**
	 * Entry point for processing a single block (from Miner or Broadcast).
	 */
	public IngestionResult processBlock(Block block, ConnectedSource source, Address receivedFrom, Instant receivedAt) {
		Timer.Sample sample = Timer.start(registry);
		IngestionResult result = null;
		masterChainLock.lock();

		try {
			if (chainQueryService.getStoredBlockByHash(block.getHash()).isPresent()) {
				result = IngestionResult.ALREADY_EXISTS;
				return result;
			}

			Optional<StoredBlock> parentOpt = chainQueryService
					.getStoredBlockByHash(block.getHeader().getPreviousHash());
			if (parentOpt.isPresent()) {
				processBlockAndOrphans(block, parentOpt.get().getBlock(), source, receivedFrom, receivedAt);
				result = IngestionResult.SUCCESS;
				return result;
			} else {
				StoredBlock localBestStored = chainQueryService.getLatestStoredBlockOrThrow();
				if (block.getHeight() > localBestStored.getHeight() + 1) {
					log.debug("Gap detected | Block: {} | Local: {}", block.getHeight(),
							localBestStored.getHeight());
					result = IngestionResult.GAP_DETECTED;
					return result;
				} else {
					log.debug("Buffering orphan block {} (Parent: {})", block.getHeight(),
							block.getHeader().getPreviousHash());
					orphanBufferService.addOrphan(block, receivedFrom, receivedAt);
					result = IngestionResult.ORPHAN_BUFFERED;
					return result;
				}
			}
		} catch (Exception e) {
			log.error("Failed to connect block {}", block.getHash(), e);
			result = IngestionResult.FAILED;
			return result;
		} finally {
			masterChainLock.unlock();
			sample.stop(registry.timer("blockchain.block.process_time",
					"source", source.name(),
					"result", (result != null ? result.name() : "ERROR")));
		}
	}

	/**
	 * Processes a block and then recursively processes its "children" from the
	 * orphan buffer.
	 */
	private void processBlockAndOrphans(Block block, Block parent, ConnectedSource source,
			Address receivedFrom, Instant receivedAt) throws Exception {
		processSingleBlock(parent, block, source, receivedFrom, receivedAt);
		log.info("Block connected at height {} with hash {} ({} txs)", block.getHeight(),
				block.getHash().toShortLogString(),
				block.getTxs().size());
		List<BlockOrphanBufferService.OrphanBlockWrapper> orphans = orphanBufferService
				.getAndRemoveChildren(block.getHash());
		if (!orphans.isEmpty()) {
			log.debug("Processing {} orphan(s) for block {}", orphans.size(), block.getHeight());
			for (BlockOrphanBufferService.OrphanBlockWrapper orphan : orphans) {
				try {
					processBlockAndOrphans(orphan.getBlock(), block, source,
							orphan.getReceivedFrom(), orphan.getReceivedAt());
				} catch (Exception e) {
					log.warn("Failed to process connected orphan block {}: {}", orphan.getBlock().getHeight(),
							e.getMessage());
				}
			}
		}
	}

	private void processSingleBlock(Block parentBlock, Block childBlock,
			ConnectedSource source, Address receivedFrom, Instant receivedAt)
			throws Exception {
		WorldState worldState = worldStateFactory.createForValidation(parentBlock.getHeader().getStateRootHash());
		NetworkParamsState params = worldState.getParams();

		blockValidationService.validateHeaderContext(childBlock.getHeader(), parentBlock.getHeader(),
				params);
		childBlock.getTxs().parallelStream().forEach(tx -> txValidationService.validateStateless(tx));
		StateProcessor.ExecutionResult result = stateProcessor.executeTransactions(worldState,
				new SimpleBlock(childBlock), childBlock.getTxs(), params);

		Hash computedStateRoot = worldState.calculateRootHash();
		if (!computedStateRoot.equals(childBlock.getHeader().getStateRootHash())) {
			log.error("State Root Mismatch! Block: {} Calculated: {}", childBlock.getHeader().getStateRootHash(),
					computedStateRoot);
			throw new GEFailedException("State Root Mismatch");
		}

		blockStateTransitionService.connectBlock(childBlock, worldState, source, result, receivedFrom, receivedAt);
	}

	/**
	 * Checks if a block with the given hash is currently sitting in the orphan
	 * buffer.
	 */
	public boolean isOrphan(Hash blockHash) {
		return orphanBufferService.isOrphan(blockHash);
	}
}