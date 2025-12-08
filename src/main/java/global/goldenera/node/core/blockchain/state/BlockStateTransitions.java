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
package global.goldenera.node.core.blockchain.state;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.reorg.ChainSwitchService;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockStateTransitions {

	BlockRepository blockRepository;
	ChainQuery chainQueryService;
	ChainSwitchService chainSwitchService;
	ApplicationEventPublisher applicationEventPublisher;
	ReentrantLock masterChainLock;
	EntityIndexRepository entityIndexRepository;
	BlockEventExtractor blockEventExtractor;

	public BlockStateTransitions(
			BlockRepository blockRepository,
			ChainQuery chainQueryService,
			ChainSwitchService chainSwitchService,
			ApplicationEventPublisher applicationEventPublisher,
			@Qualifier("masterChainLock") ReentrantLock masterChainLock,
			EntityIndexRepository entityIndexRepository,
			BlockEventExtractor blockEventExtractor) {
		this.blockRepository = blockRepository;
		this.chainQueryService = chainQueryService;
		this.chainSwitchService = chainSwitchService;
		this.applicationEventPublisher = applicationEventPublisher;
		this.masterChainLock = masterChainLock;
		this.entityIndexRepository = entityIndexRepository;
		this.blockEventExtractor = blockEventExtractor;
	}

	public void connectBlock(
			@NonNull Block block,
			@NonNull WorldState worldState,
			@NonNull ConnectedSource source,
			StateProcessor.ExecutionResult executionResult,
			Address receivedFrom,
			Instant receivedAt) {
		masterChainLock.lock();
		try {
			long start = System.currentTimeMillis();
			long height = block.getHeight();
			BigInteger cumulativeDifficulty;

			if (source == ConnectedSource.GENESIS) {
				cumulativeDifficulty = block.getHeader().getDifficulty();
			} else {
				StoredBlock parentStored = blockRepository
						.getStoredBlockByHash(block.getHeader().getPreviousHash())
						.orElseThrow(() -> new GEFailedException(
								"Parent block not found: " + block.getHeader().getPreviousHash()));

				cumulativeDifficulty = parentStored.getCumulativeDifficulty()
						.add(block.getHeader().getDifficulty());
			}

			// Extract block events from execution result
			List<BlockEvent> blockEvents = List.of();
			if (height > 0 && executionResult != null) {
				Wei totalFees = executionResult.getTotalFeesCollected();
				Wei actualRewardPaid = executionResult.getMinerActualRewardPaid();
				// Block reward = total reward paid - fees (fees are added on top of block
				// reward)
				Wei blockRewardFromPool = actualRewardPaid.subtract(totalFees);

				blockEvents = blockEventExtractor.extractEvents(
						blockRewardFromPool,
						totalFees,
						block.getHeader().getCoinbase(),
						worldState.getParams().getBlockRewardPoolAddress(),
						worldState.getBipDiffs(),
						worldState.getTokenDiffs(),
						executionResult.getActualBurnAmounts());
			}

			StoredBlock storedBlockToSave = StoredBlock.builder()
					.block(block)
					.cumulativeDifficulty(cumulativeDifficulty)
					.receivedFrom(receivedFrom)
					.receivedAt(receivedAt)
					.connectedSource(source)
					.computeIndexes()
					.events(blockEvents)
					.build();

			boolean isNewHead = false;
			StoredBlock currentHead = null;

			if (source == ConnectedSource.GENESIS) {
				isNewHead = true;
			} else {
				currentHead = blockRepository.getLatestStoredBlock().orElse(null);
				if (currentHead == null || cumulativeDifficulty.compareTo(currentHead.getCumulativeDifficulty()) > 0) {
					isNewHead = true;
				}
			}

			// --- REORG DETECTION ---
			if (isNewHead && currentHead != null
					&& !block.getHeader().getPreviousHash().equals(currentHead.getHash())) {
				log.info("Reorg detected in connectBlock! New Head: {} (TD: {}), Current Head: {} (TD: {})",
						block.getHeight(), cumulativeDifficulty, currentHead.getHeight(),
						currentHead.getCumulativeDifficulty());

				try {
					// 1. Find Common Ancestor
					StoredBlock commonAncestor = null;
					List<StoredBlock> forkChain = new ArrayList<>();
					forkChain.add(storedBlockToSave);

					StoredBlock cursor = blockRepository.getStoredBlockByHash(block.getHeader().getPreviousHash())
							.orElseThrow(() -> new GEFailedException("Broken link in reorg chain"));

					while (cursor != null) {
						if (chainQueryService.getCanonicalStoredBlockByHash(cursor.getHash()).isPresent()) {
							commonAncestor = cursor;
							break;
						}
						forkChain.add(cursor);
						cursor = blockRepository.getStoredBlockByHash(cursor.getBlock().getHeader().getPreviousHash())
								.orElse(null);
					}

					if (commonAncestor == null) {
						throw new GEFailedException("Could not find common ancestor for reorg!");
					}

					Collections.reverse(forkChain); // Now it's Ancestor -> ... -> NewBlock

					// 2. Execute Switch
					chainSwitchService.executeAtomicReorgSwap(commonAncestor, forkChain, true);
					return; // Done, reorg service handled everything

				} catch (Exception e) {
					log.error("Failed to execute reorg in connectBlock", e);
					throw new GEFailedException("Reorg failed", e);
				}
			}

			// --- STANDARD CONNECT ---
			final boolean performFullConnect = isNewHead;

			try {
				blockRepository.getRepository().executeAtomicBatch(batch -> {
					worldState.persistToBatch(batch);
					if (performFullConnect) {
						entityIndexRepository.saveEntities(batch, block, worldState);
						blockRepository.addBlockToBatch(batch, storedBlockToSave);
					} else {
						try {
							blockRepository.saveBlockDataToBatch(batch, storedBlockToSave);
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
				});
				entityIndexRepository.invalidateCaches();
			} catch (RuntimeException e) {
				log.error("Atomic commit failed for block {}: {}", block.getHeight(), e.getMessage(), e);
				worldState.rollback();
				throw new GEFailedException(
						"Atomic commit failed for block " + block.getHeight() + ": " + e.getMessage(),
						e.getCause() != null ? e.getCause() : e);
			}

			long end = System.currentTimeMillis();

			if (performFullConnect) {
				log.info("Block connected as NEW HEAD at height {} (TD: {}) in {}s",
						block.getHeight(), cumulativeDifficulty, String.format("%.2f", (end - start) / 1000.0));

				Wei totalFees = height == 0 ? Wei.ZERO : executionResult.getTotalFeesCollected();
				Wei actualRewardPaid = height == 0 ? Wei.ZERO : executionResult.getMinerActualRewardPaid();
				Map<Hash, Wei> actualBurnAmounts = height == 0 ? Map.of() : executionResult.getActualBurnAmounts();

				BlockConnectedEvent event = new BlockConnectedEvent(
						this,
						source,
						block,
						worldState.getBalanceDiffs(),
						worldState.getNonceDiffs(),
						worldState.getTokenDiffs(),
						worldState.getBipDiffs(),
						worldState.getParamsDiff(),
						worldState.getDirtyAuthorities(),
						worldState.getAuthoritiesRemovedWithState(),
						worldState.getDirtyAddressAliases(),
						worldState.getAliasesRemovedWithState(),
						totalFees,
						actualRewardPaid,
						cumulativeDifficulty,
						actualBurnAmounts,
						receivedFrom,
						receivedAt);

				applicationEventPublisher.publishEvent(event);
			} else {
				log.info("Block {} stored as FORK (TD: {} <= Current Head). No event emitted.",
						block.getHeight(), cumulativeDifficulty);
			}
		} finally {
			masterChainLock.unlock();
		}
	}
}