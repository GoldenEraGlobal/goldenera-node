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
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidationException;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
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
	StateProcessor stateProcessor;
	WorldStateFactory worldStateFactory;
	BlockStateTransitions blockStateTransitionService;
	BlockOrphanBufferService orphanBufferService;

	/**
	 * Entry point for processing a single block (from Miner or Broadcast).
	 */
	public BlockIngestionOutcome processBlock(Block block, ConnectedSource source, Address receivedFrom,
			Instant receivedAt) {
		return processBlock(block, null, source, receivedFrom, receivedAt);
	}

	BlockIngestionOutcome processValidatedBlock(StatelessValidatedBlock validatedBlock, ConnectedSource source,
			Address receivedFrom, Instant receivedAt) {
		return processBlock(validatedBlock.block(), validatedBlock, source, receivedFrom, receivedAt);
	}

	private BlockIngestionOutcome processBlock(Block block, StatelessValidatedBlock suppliedValidation,
			ConnectedSource source, Address receivedFrom, Instant receivedAt) {
		Timer.Sample sample = Timer.start(registry);
		BlockIngestionOutcome outcome = null;
		Hash blockHash = null;
		masterChainLock.lock();

		try {
			try {
				blockHash = block.getHash();
			} catch (RuntimeException e) {
				outcome = BlockIngestionOutcome.of(BlockIngestionOutcome.Code.REJECTED_STATELESS);
				return outcome;
			}
			if (chainQueryService.getStoredBlockByHash(blockHash).isPresent()) {
				outcome = BlockIngestionOutcome.of(BlockIngestionOutcome.Code.ALREADY_EXISTS);
				return outcome;
			}

			StatelessValidatedBlock validatedBlock = suppliedValidation;
			if (validatedBlock == null) {
				try {
					// The raw ingestion boundary never trusts its caller. Validate before
					// either buffering the block or executing/persisting it.
					validatedBlock = blockValidationService.validateFullBlock(block);
				} catch (BlockValidationException e) {
					log.debug("Rejected block {} during stateless validation: {}", blockHash, e.getMessage());
					outcome = BlockIngestionOutcome.of(BlockIngestionOutcome.Code.REJECTED_STATELESS);
					return outcome;
				}
			} else if (!validatedBlock.matches(block)) {
				outcome = BlockIngestionOutcome.of(BlockIngestionOutcome.Code.REJECTED_STATELESS);
				return outcome;
			}
			// From this point onward, only the immutable body that actually passed the
			// stateless gate may be buffered, executed or persisted.
			block = validatedBlock.block();
			blockHash = block.getHash();

			Optional<StoredBlock> parentOpt = chainQueryService
					.getStoredBlockByHash(block.getHeader().getPreviousHash());
			if (parentOpt.isPresent()) {
				outcome = processBlockAndOrphans(
						validatedBlock, parentOpt.get().getBlock(), source, receivedFrom, receivedAt);
				return outcome;
			} else {
				StoredBlock localBestStored = chainQueryService.getLatestStoredBlockOrThrow();
				if (block.getHeight() > localBestStored.getHeight() + 1) {
					log.debug("Gap detected | Block: {} | Local: {}", block.getHeight(),
							localBestStored.getHeight());
					outcome = BlockIngestionOutcome.of(BlockIngestionOutcome.Code.GAP_DETECTED);
					return outcome;
				} else {
					log.debug("Buffering orphan block {} (Parent: {})", block.getHeight(),
							block.getHeader().getPreviousHash());
					orphanBufferService.addOrphan(validatedBlock, receivedFrom, receivedAt);
					outcome = BlockIngestionOutcome.of(BlockIngestionOutcome.Code.ORPHAN_BUFFERED);
					return outcome;
				}
			}
		} catch (Exception e) {
			log.error("Failed to connect block {}", blockHash, e);
			outcome = BlockIngestionOutcome.of(BlockIngestionOutcome.Code.INTERNAL_FAILURE);
			return outcome;
		} finally {
			masterChainLock.unlock();
			sample.stop(registry.timer("blockchain.block.process_time",
					"source", source.name(),
					"result", (outcome != null ? outcome.code().name() : "ERROR")));
		}
	}

	/**
	 * Processes a block and then recursively processes its "children" from the
	 * orphan buffer.
	 */
	private BlockIngestionOutcome processBlockAndOrphans(StatelessValidatedBlock validatedBlock, Block parent,
			ConnectedSource source,
			Address receivedFrom, Instant receivedAt) {
		Block block = validatedBlock.block();
		BlockIngestionOutcome outcome = processSingleBlock(
				parent, validatedBlock, source, receivedFrom, receivedAt);
		if (outcome.code() != BlockIngestionOutcome.Code.ACCEPTED) {
			return outcome;
		}
		log.info("Block connected at height {} with hash {} ({} txs)", block.getHeight(),
				block.getHash().toShortLogString(),
				block.getTxs().size());
		List<BlockOrphanBufferService.OrphanBlockWrapper> orphans = orphanBufferService
				.getAndRemoveChildren(block.getHash());
		if (!orphans.isEmpty()) {
			log.debug("Processing {} orphan(s) for block {}", orphans.size(), block.getHeight());
			for (BlockOrphanBufferService.OrphanBlockWrapper orphan : orphans) {
				try {
					BlockIngestionOutcome orphanOutcome = processBlockAndOrphans(
							orphan.getValidatedBlock(), block, source,
							orphan.getReceivedFrom(), orphan.getReceivedAt());
					if (orphanOutcome.code() != BlockIngestionOutcome.Code.ACCEPTED) {
						log.warn("Rejected connected orphan block {} with outcome {}",
								orphan.getBlock().getHeight(), orphanOutcome.code());
					}
				} catch (Exception e) {
					log.warn("Failed to process connected orphan block {}: {}", orphan.getBlock().getHeight(),
							e.getMessage());
				}
			}
		}
		return outcome;
	}

	private BlockIngestionOutcome processSingleBlock(Block parentBlock, StatelessValidatedBlock validatedBlock,
			ConnectedSource source, Address receivedFrom, Instant receivedAt) {
		Block childBlock = validatedBlock.block();
		WorldState worldState = worldStateFactory.createForValidation(parentBlock.getHeader().getStateRootHash());
		NetworkParamsState params = worldState.getParams();
		try {
			blockValidationService.validateHeaderContext(childBlock.getHeader(), parentBlock.getHeader(), worldState);
		} catch (BlockValidationException e) {
			BlockIngestionOutcome.Code code = e.getCategory() == BlockValidationException.Category.CONSENSUS_POLICY
					? BlockIngestionOutcome.Code.REJECTED_CONSENSUS_POLICY
					: BlockIngestionOutcome.Code.REJECTED_CONTEXTUAL;
			log.debug("Rejected block {} during contextual validation: {}", childBlock.getHash(), e.getMessage());
			return BlockIngestionOutcome.of(code);
		}

		StateProcessor.ExecutionResult result;
		try {
			result = stateProcessor.executeTransactions(worldState,
					new SimpleBlock(childBlock), childBlock.getTxs(), params);
		} catch (RuntimeException e) {
			log.debug("Rejected block {} during state execution: {}", childBlock.getHash(), e.getMessage());
			return BlockIngestionOutcome.of(BlockIngestionOutcome.Code.REJECTED_EXECUTION);
		}

		Hash computedStateRoot = worldState.calculateRootHash();
		if (!computedStateRoot.equals(childBlock.getHeader().getStateRootHash())) {
			log.error("State Root Mismatch! Block: {} Calculated: {}", childBlock.getHeader().getStateRootHash(),
					computedStateRoot);
			return BlockIngestionOutcome.of(BlockIngestionOutcome.Code.REJECTED_STATE_ROOT);
		}

		try {
			blockStateTransitionService.connectValidatedBlock(
					validatedBlock, worldState, source, result, receivedFrom, receivedAt);
			return BlockIngestionOutcome.of(BlockIngestionOutcome.Code.ACCEPTED);
		} catch (RuntimeException e) {
			log.error("Failed to persist block {}", childBlock.getHash(), e);
			return BlockIngestionOutcome.of(BlockIngestionOutcome.Code.INTERNAL_FAILURE);
		}
	}

	/** Performs parent-state validation before a broadcast body is requested. */
	public void validateBroadcastHeaderContext(BlockHeader childHeader, Block parentBlock) {
		WorldState worldState = worldStateFactory.createForValidation(parentBlock.getHeader().getStateRootHash());
		blockValidationService.validateHeaderContext(childHeader, parentBlock.getHeader(), worldState);
	}

	/**
	 * Checks if a block with the given hash is currently sitting in the orphan
	 * buffer.
	 */
	public boolean isOrphan(Hash blockHash) {
		return orphanBufferService.isOrphan(blockHash);
	}
}
