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

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.CoreDbReadyEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExBlockHeader;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.repositories.ExBlockHeaderRepository;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExIndexerSyncService {

	ExBlockHeaderRepository exBlockHeaderRepository;
	ExIndexerStatusCoreService exStatusCoreService;
	ExIndexerQueueService exQueueService;
	ExIndexerRevertService exRevertService;

	ChainQuery chainQuery;
	WorldStateFactory worldStateFactory;
	StateProcessor stateProcessor;

	@EventListener(CoreDbReadyEvent.class)
	@Transactional(rollbackFor = Exception.class)
	public void syncExplorerOnStartup() {
		log.info("Checking Explorer synchronization status...");
		long explorerHeight = validateAndRepairStatus();

		long coreHeight = chainQuery.getLatestBlock().map(Block::getHeight).orElse(-1L);
		log.info("Explorer Height: {}, Core Height: {}", explorerHeight, coreHeight);

		// 1. Handle Reorgs
		explorerHeight = handleStartupReorgs(explorerHeight);

		// 2. Sync Forward
		if (explorerHeight < coreHeight) {
			log.info("Explorer is behind. Starting sync from {} to {}...", explorerHeight + 1, coreHeight);

			for (long h = explorerHeight + 1; h <= coreHeight; h++) {
				StoredBlock storedBlock = chainQuery.getStoredBlockByHeight(h).orElse(null);
				if (storedBlock == null) {
					log.error("Critical: Missing block #{} in Core during Explorer sync!", h);
					break;
				}
				try {
					BlockConnectedEvent reconstructedEvent = reconstructEvent(storedBlock);
					exQueueService.pushConnect(reconstructedEvent);
				} catch (Exception e) {
					log.error("Failed to reconstruct event for block #{}", h, e);
					throw new RuntimeException("Explorer sync failed at block " + h, e);
				}
			}
		} else {
			log.info("Explorer is fully synced.");
		}
	}

	private long handleStartupReorgs(long explorerHeight) {
		while (explorerHeight >= 0) {
			Hash explorerHash = exStatusCoreService.getStatus().map(ExStatus::getSyncedBlockHash).orElse(null);
			Block coreBlock = chainQuery.getBlockByHeight(explorerHeight).orElse(null);
			if (explorerHash != null && coreBlock != null && coreBlock.getHash().equals(explorerHash)) {
				return explorerHeight;
			}
			log.warn("Explorer DETECTED FORK at startup on block #{}. ExplorerHash: {}, CoreHash: {}. Reverting...",
					explorerHeight, explorerHash, (coreBlock != null ? coreBlock.getHash() : "null"));

			exRevertService.revertBlock(explorerHash, explorerHeight);
			explorerHeight--;
		}
		return -1;
	}

	private BlockConnectedEvent reconstructEvent(StoredBlock storedBlock) {
		Block block = storedBlock.getBlock();
		long height = block.getHeight();
		Address receivedFrom = storedBlock.getReceivedFrom();
		Instant receivedAt = storedBlock.getReceivedAt();
		ConnectedSource connectedSource = storedBlock.getConnectedSource();

		// --- GENESIS HANDLING ---
		if (height == 0) {
			return createGenesisEvent(block, receivedFrom, receivedAt);
		}

		// --- NORMAL BLOCK RECONSTRUCTION ---
		Block prevBlock = chainQuery.getBlockByHash(block.getHeader().getPreviousHash())
				.orElseThrow(() -> new IllegalStateException("Parent block not found for reconstruction: " + height));

		Hash startStateRoot = prevBlock.getHeader().getStateRootHash();

		// 1. Create WorldState (Validation Mode = No Journal, Initial State Capture ON)
		WorldState worldState = worldStateFactory.createForValidation(startStateRoot);
		NetworkParamsState params = worldState.getParams();

		// 2. Execute Transactions (To get Diffs & Fees)
		ExecutionResult executionResult = stateProcessor.executeTransactions(worldState,
				new StateProcessor.SimpleBlock(block),
				block.getTxs(), params);

		// 3. Extract Data
		BigInteger cumulativeDifficulty = storedBlock.getCumulativeDifficulty();
		Wei totalFees = executionResult.getTotalFeesCollected();
		Wei actualRewardPaid = executionResult.getMinerActualRewardPaid();
		Map<Hash, Wei> actualBurnAmounts = executionResult.getActualBurnAmounts();

		return new BlockConnectedEvent(
				this,
				connectedSource,
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
	}

	private BlockConnectedEvent createGenesisEvent(Block block, Address receivedFrom, Instant receivedAt) {
		return new BlockConnectedEvent(
				this,
				ConnectedSource.GENESIS,
				block,
				Collections.emptyMap(), // Balances
				Collections.emptyMap(), // Nonces
				Collections.emptyMap(), // Tokens
				Collections.emptyMap(), // Bips
				null, // Params diff
				Collections.emptyMap(), // Authorities
				Collections.emptyMap(), // Auth removed
				Collections.emptyMap(), // Aliases
				Collections.emptyMap(), // Aliases removed
				Wei.ZERO, // Fees
				Wei.ZERO, // Reward
				block.getHeader().getDifficulty(), // Cumulative = Own difficulty for Genesis
				Collections.emptyMap(), // Burn
				receivedFrom,
				receivedAt);
	}

	private long validateAndRepairStatus() {
		Optional<ExBlockHeader> realTopBlockOpt = exBlockHeaderRepository.findLatest();
		if (realTopBlockOpt.isEmpty()) {
			log.info("Self-Heal: No blocks found in Explorer DB. Ensuring Status is initialized/empty.");
			return -1;
		}

		ExBlockHeader realTopBlock = realTopBlockOpt.get();
		long realHeight = realTopBlock.getHeight();
		Hash realHash = realTopBlock.getHash();
		ExStatus currentStatus = exStatusCoreService.getStatus().orElse(null);
		boolean isCorrupted = false;

		if (currentStatus == null) {
			log.warn("Self-Heal: Status row MISSING! Repairing based on block #{}", realHeight);
			isCorrupted = true;
		} else if (currentStatus.getSyncedBlockHeight() != realHeight) {
			log.warn("Self-Heal: Height mismatch! Status: {}, DB: {}. Repairing...",
					currentStatus.getSyncedBlockHeight(), realHeight);
			isCorrupted = true;
		} else if (!currentStatus.getSyncedBlockHash().equals(realHash)) {
			log.warn("Self-Heal: Hash mismatch (Corrupted/Manual edit)! Status: {}, DB: {}. Repairing...",
					currentStatus.getSyncedBlockHash(), realHash);
			isCorrupted = true;
		}

		if (isCorrupted) {
			exStatusCoreService.updateStatus(realTopBlock);
			log.info("Self-Heal: Status successfully REPAIRED to match block #{}", realHeight);
		}
		return realHeight;
	}
}