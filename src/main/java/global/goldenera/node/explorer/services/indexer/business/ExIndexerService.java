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
package global.goldenera.node.explorer.services.indexer.business;

import static lombok.AccessLevel.PRIVATE;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.exceptions.AlreadyIndexedException;
import global.goldenera.node.explorer.exceptions.ChainSplitException;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerBlockDataCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerAccountHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerConsensusHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerTokenHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.mappers.ExIndexerTxToTransferMapper;
import global.goldenera.node.shared.exceptions.GEFailedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ExIndexerService {

	MeterRegistry registry;
	ChainQuery chainQueryService;
	ExIndexerEventReconstructionService eventReconstructionService;

	ExIndexerStatusCoreService exStatusCoreService;
	ExIndexerRevertService exRevertService;
	ExIndexerPartitionService postgrePartitionService;
	ExIndexerTxToTransferMapper txToTransferMapper;

	ExIndexerBlockDataCoreService exBlockDataCoreService;

	ExIndexerAccountHelperService exAccountService;
	ExIndexerConsensusHelperService exConsensusService;
	ExIndexerTokenHelperService exTokenService;

	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void handleBlockConnected(BlockConnectedEvent event) {
		Timer.Sample sample = Timer.start(registry);
		Block block = event.getBlock();

		int attempts = 0;
		while (true) {
			if (attempts++ > 3000) {
				throw new GEFailedException("Too many reorg attempts (3000+). Aborting to prevent infinite loop.");
			}
			try {
				checkContinuity(block);
				break;
			} catch (AlreadyIndexedException e) {
				log.warn("Block #{} already indexed. Skipping.", block.getHeight());
				return;
			} catch (ChainSplitException e) {
				registry.counter("explorer.reorg.detected").increment();
				log.warn("REORG DETECTED at Block #{}. Reverting HEAD to resolve split... (Attempt {})",
						block.getHeight() - 1, attempts);
				try {
					ExStatus status = exStatusCoreService.getStatusOrThrow();
					exRevertService.revertBlock(status.getSyncedBlockHash(), status.getSyncedBlockHeight());
					continue;
				} catch (Exception ex) {
					log.error("Automatic Revert Failed! Explorer is stuck.", ex);
					throw new GEFailedException("Critical Revert Failure", ex);
				}
			}
		}

		processBlock(block, event);
		sample.stop(registry.timer("explorer.block.index_time"));
	}

	private void processBlock(Block block, BlockConnectedEvent event) {
		long start = System.currentTimeMillis();
		if (block.getHeight() % 10000 == 0) {
			postgrePartitionService.ensurePartitionsExist(block.getHeight());
		}

		try {
			exAccountService.processBalances(block, event.getBalanceDiffs());
			exAccountService.processNonces(block, event.getNonceDiffs());
			exTokenService.processTokens(block, event.getTokenDiffs());
			exConsensusService.processBips(block, event.getBipDiffs());
			exConsensusService.processNetworkParams(block, event.getNetworkParamsDiff());
			exConsensusService.processAliases(block, event.getAddressAliasesToAdd(),
					event.getAddressAliasesToRemove());
			exConsensusService.processAuthorities(block, event.getAuthoritiesToAdd(),
					event.getAuthoritiesToRemove());

			exBlockDataCoreService.insertBlockHeader(block, event.getCumulativeDifficulty(),
					event.getMinerTotalFees().toBigInteger(), event.getMinerActualRewardPaid().toBigInteger());
			exBlockDataCoreService.insertTransactions(block.getTxs(), block.getHeight(), block.getHash());
			exBlockDataCoreService.insertTransfers(txToTransferMapper.map(event));
			exStatusCoreService.updateStatus(block.getHeader());

			long elapsed = System.currentTimeMillis() - start;
			int txCount = block.getTxs().size();

			// Reduce log spam during sync:
			// - Log every 100 blocks with summary
			// - Log individual blocks only if they have transactions or are slow (>500ms)
			// - Use DEBUG for routine empty blocks
			if (block.getHeight() % 100 == 0) {
				log.info("Explorer indexed to block #{} in {} ms", block.getHeight(), elapsed);
			} else if (txCount > 0 || elapsed > 500) {
				log.info("Indexed block #{} ({} txs) in {} ms", block.getHeight(), txCount, elapsed);
			} else {
				log.debug("Indexed block #{} ({}) in {} ms", block.getHeight(), txCount, elapsed);
			}

		} catch (Exception e) {
			log.error("CRITICAL: Failed to index block #{}. Rolling back.", block.getHeight(), e);
			throw new GEFailedException("Block indexing failed", e);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void handleBlockDisconnected(BlockDisconnectedEvent event) {
		checkRevertContinuity(event.getBlock());
		exRevertService.revertBlock(event.getBlock().getHash(), event.getBlock().getHeight());
	}

	private void checkContinuity(Block block) throws ChainSplitException, AlreadyIndexedException {
		long incomingHeight = block.getHeight();

		// --- GENESIS HANDLING ---
		if (incomingHeight == 0) {
			ExStatus status = exStatusCoreService.getStatus().orElse(null);
			if (status != null) {
				long currentDbHeight = status.getSyncedBlockHeight();
				if (currentDbHeight >= 0) {
					throw new AlreadyIndexedException("Genesis already indexed");
				}
			}
			return;
		}

		// --- STANDARD BLOCK ---
		ExStatus status = exStatusCoreService.getStatusOrThrow();
		Long height = status.getSyncedBlockHeight();
		Hash hash = status.getSyncedBlockHash();

		if (incomingHeight <= height) {
			if (incomingHeight == height && !block.getHash().equals(hash)) {
				throw new ChainSplitException("Reorg at HEAD: DB=" + hash + ", New=" + block.getHash());
			}
			throw new AlreadyIndexedException("Block " + incomingHeight + " already indexed");
		}

		// --- GAP DETECTION & HEALING ---
		if (incomingHeight > height + 1) {
			Long finalHeight = height;
			StoredBlock nextCoreBlock = chainQueryService.getStoredBlockByHeight(height + 1)
					.orElseThrow(() -> new GEFailedException("Missing block in chain query: " + (finalHeight + 1)));

			if (!nextCoreBlock.getBlock().getHeader().getPreviousHash().equals(hash)) {
				log.warn(
						"Deep Reorg detected! Core block #{} (Prev: {}) does not attach to Explorer Head #{} (Hash: {}). Triggering revert.",
						height + 1, nextCoreBlock.getBlock().getHeader().getPreviousHash(), height, hash);
				throw new ChainSplitException("Deep Chain Split detected at gap start: " + height);
			}

			log.warn("Gap detected! Explorer Head: #%d, Received: #%d. Healing...", height, incomingHeight);
			registry.counter("explorer.gap.healing").increment();

			healGap(height + 1, incomingHeight - 1);

			status = exStatusCoreService.getStatusOrThrow();
			height = status.getSyncedBlockHeight();
			hash = status.getSyncedBlockHash();

			if (!block.getHeader().getPreviousHash().equals(hash)) {
				log.warn(
						"Gap healed to #{}, but incoming block #{} (Prev: {}) STILL does not attach to Head (Hash: {}). "
								+
								"Incoming block is likely an orphan/stale event. Skipping processing to preserve healed chain.",
						height, incomingHeight, block.getHeader().getPreviousHash(), hash);
				throw new AlreadyIndexedException("Incoming block mismatch after heal - skipping as orphan");
			}
		}

		// Standard check
		if (!block.getHeader().getPreviousHash().equals(hash)) {
			throw new ChainSplitException(String.format(
					"Chain split: Explorer Head %s != Block Prev %s",
					hash, block.getHeader().getPreviousHash()));
		}
	}

	private void checkRevertContinuity(Block block) {
		ExStatus status = exStatusCoreService.getStatus().orElse(null);
		if (status == null)
			return;

		if (block.getHeight() != status.getSyncedBlockHeight()) {
			throw new GEFailedException(String.format(
					"REVERT ERROR: Trying to revert block #%d, but Explorer Head is at #%d. Can only revert HEAD.",
					block.getHeight(), status.getSyncedBlockHeight()));
		}

		if (!block.getHash().equals(status.getSyncedBlockHash())) {
			throw new GEFailedException("REVERT ERROR: Hash mismatch trying to revert HEAD.");
		}
	}

	private void healGap(long startHeight, long endHeight) {
		registry.counter("explorer.gap.healing").increment();
		log.info("Healing gap from #{} to #{}", startHeight, endHeight);
		for (long h = startHeight; h <= endHeight; h++) {
			final long currentHeight = h;
			try {
				StoredBlock storedBlock = chainQueryService.getStoredBlockByHeight(currentHeight)
						.orElseThrow(() -> new GEFailedException("Missing block in chain query: " + currentHeight));

				BlockConnectedEvent event = eventReconstructionService.reconstructEvent(storedBlock);
				processBlock(storedBlock.getBlock(), event);
			} catch (Exception e) {
				throw new GEFailedException("Failed to heal gap at block " + h, e);
			}
		}
	}

}