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
package global.goldenera.node.explorer.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.Constants;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.genesis.GenesisInitializer;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.state.IsolatedWorldStateStorage;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateDiff;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.shared.datatypes.BalanceKey;
import lombok.extern.slf4j.Slf4j;

/** Re-executes the local canonical archive in an isolated trie database. */
@Slf4j
public final class IsolatedExplorerArchiveReplayEngine implements ExplorerArchiveReplayEngine {

	private static final int YIELD_INTERVAL = 250;
	private static final int INDEX_BATCH_SIZE = 64;
	private static final int PROGRESS_INTERVAL = 10_000;

	private final ChainQuery chainQuery;
	private final StateProcessor stateProcessor;
	private final BlockEventExtractor blockEventExtractor;
	private final ExIndexerService indexerService;
	private final ExIndexerStatusCoreService statusService;

	public IsolatedExplorerArchiveReplayEngine(
			ChainQuery chainQuery,
			StateProcessor stateProcessor,
			BlockEventExtractor blockEventExtractor,
			ExIndexerService indexerService,
			ExIndexerStatusCoreService statusService) {
		this.chainQuery = chainQuery;
		this.stateProcessor = stateProcessor;
		this.blockEventExtractor = blockEventExtractor;
		this.indexerService = indexerService;
		this.statusService = statusService;
	}

	@Override
	public void rebuildToCanonicalHead() {
		StoredBlock target = chainQuery.getLatestStoredBlockOrThrow();
		ExStatus resumeStatus = statusService.getStatus().orElse(null);
		long resumeHeight = canonicalResumeHeight(resumeStatus, target);
		if (resumeHeight > target.getHeight()) {
			throw new ExplorerCanonicalArchiveChangedException(
					"Explorer rebuild resume height is above canonical head");
		}
		try (IsolatedWorldStateStorage isolated = IsolatedWorldStateStorage.temporary("explorer-rebuild-")) {
			long startedNanos = System.nanoTime();
			List<BlockConnectedEvent> indexBatch = new ArrayList<>(INDEX_BATCH_SIZE);
			log.info("EXPLORER SNAPSHOT: Replaying canonical CORE archive 0..{}; PostgreSQL batch size={}",
					target.getHeight(), INDEX_BATCH_SIZE);
			Hash previousRoot = MerkleTrie.EMPTY_TRIE_NODE_HASH;
			Hash previousBlockHash = null;
			long nextHeight = 0L;
			while (true) {
				assertNotInterrupted();
				while (nextHeight <= target.getHeight()) {
					assertNotInterrupted();
					StoredBlock stored = canonical(nextHeight);
					if (nextHeight > 0
							&& !stored.getBlock().getHeader().getPreviousHash().equals(previousBlockHash)) {
						throw new ExplorerCanonicalArchiveChangedException(
								"Canonical parent changed during explorer rebuild at " + nextHeight);
					}
					WorldState worldState = isolated.worldStateFactory().createForValidation(previousRoot);
					BlockConnectedEvent event = nextHeight == 0
							? executeGenesis(worldState, stored)
							: executeBlock(worldState, stored);
					Hash calculated = worldState.calculateRootHash();
					if (!calculated.equals(stored.getBlock().getHeader().getStateRootHash())) {
						throw stateRootFailure(nextHeight, stored);
					}
					isolated.persist(worldState);
					previousRoot = calculated;
					previousBlockHash = stored.getHash();
					if (nextHeight > resumeHeight) {
						indexBatch.add(event);
						if (indexBatch.size() == INDEX_BATCH_SIZE) {
							flushIndexBatch(indexBatch);
						}
					}
					logProgress(nextHeight, target.getHeight(), startedNanos);
					if (nextHeight > 0 && nextHeight % YIELD_INTERVAL == 0) {
						Thread.yield();
					}
					nextHeight++;
				}

				StoredBlock processedTarget = canonical(target.getHeight());
				if (!processedTarget.getHash().equals(target.getHash())) {
					throw new ExplorerCanonicalArchiveChangedException(
							"Canonical target changed during explorer rebuild");
				}
				StoredBlock current = chainQuery.getLatestStoredBlockOrThrow();
				if (current.getHeight() == target.getHeight() && current.getHash().equals(target.getHash())) {
					flushIndexBatch(indexBatch);
					assertExplorerCaughtUp(target);
					return;
				}
				if (current.getHeight() < target.getHeight()) {
					throw new ExplorerCanonicalArchiveChangedException(
							"Canonical head moved behind explorer rebuild target");
				}
				target = current;
			}
		} catch (ExplorerSnapshotException e) {
			throw e;
		} catch (Exception e) {
			throw new ExplorerSnapshotException("Explorer archive rebuild failed", e);
		}
	}

	private void assertNotInterrupted() {
		if (Thread.currentThread().isInterrupted()) {
			throw new ExplorerSnapshotException("Explorer canonical archive rebuild was interrupted");
		}
	}

	private void flushIndexBatch(List<BlockConnectedEvent> batch) {
		if (batch.isEmpty()) {
			return;
		}
		indexerService.handleBlockConnectedBatch(List.copyOf(batch));
		batch.clear();
	}

	private void logProgress(long height, long targetHeight, long startedNanos) {
		if (height != targetHeight && height % PROGRESS_INTERVAL != 0) {
			return;
		}
		long elapsedSeconds = Math.max(1, (System.nanoTime() - startedNanos) / 1_000_000_000L);
		long processed = height + 1;
		double blocksPerSecond = processed / (double) elapsedSeconds;
		long remaining = Math.max(0, targetHeight - height);
		long etaSeconds = blocksPerSecond <= 0 ? -1 : Math.round(remaining / blocksPerSecond);
		int percent = targetHeight < 0 ? 100 : (int) Math.min(100, processed * 100 / (targetHeight + 1));
		log.info("EXPLORER SNAPSHOT: Archive rebuild progress: {}/{} ({}%), {} blocks/s, ETA {}s",
				height, targetHeight, percent, String.format(Locale.ROOT, "%.1f", blocksPerSecond), etaSeconds);
	}

	private long canonicalResumeHeight(ExStatus resumeStatus, StoredBlock target) {
		if (resumeStatus == null) {
			return -1L;
		}
		if (resumeStatus.getSyncedBlockHeight() > target.getHeight()) {
			return resumeStatus.getSyncedBlockHeight();
		}
		Optional<StoredBlock> canonical = chainQuery.getStoredBlockByHeight(resumeStatus.getSyncedBlockHeight());
		if (canonical.isPresent() && canonical.get().getHash().equals(resumeStatus.getSyncedBlockHash())) {
			return resumeStatus.getSyncedBlockHeight();
		}
		return -1L;
	}

	private ExplorerSnapshotException stateRootFailure(long height, StoredBlock replayed) {
		Optional<Hash> currentHash = chainQuery.getBlockHashByHeight(height);
		if (currentHash.isEmpty() || !currentHash.get().equals(replayed.getHash())) {
			return new ExplorerCanonicalArchiveChangedException(
					"Canonical block changed during explorer rebuild at " + height);
		}
		return new ExplorerSnapshotException("Explorer rebuild state root mismatch at block " + height);
	}

	private void assertExplorerCaughtUp(StoredBlock target) {
		ExStatus status = statusService.getStatusOrThrow();
		if (status.getSyncedBlockHeight() != target.getHeight()
				|| !status.getSyncedBlockHash().equals(target.getHash())) {
			throw new ExplorerCanonicalArchiveChangedException(
					"Explorer status did not catch the moving canonical head");
		}
	}

	private StoredBlock canonical(long height) {
		StoredBlock block = chainQuery.getStoredBlockByHeight(height)
				.orElseThrow(() -> new ExplorerSnapshotException("Missing canonical block " + height));
		Hash indexed = chainQuery.getBlockHashByHeight(height)
				.orElseThrow(() -> new ExplorerSnapshotException("Missing canonical height index " + height));
		if (!indexed.equals(block.getHash())) {
			throw new ExplorerCanonicalArchiveChangedException(
					"Canonical block changed during explorer rebuild at " + height);
		}
		return block;
	}

	private BlockConnectedEvent executeGenesis(WorldState state, StoredBlock stored) {
		Block block = stored.getBlock();
		NetworkSettings settings = Constants.getSettings();
		GenesisInitializer.executeGenesisStateExplicitly(
				state, settings.genesisAuthorityAddresses(), block.getHeader().getTimestamp(), settings);

		NetworkParamsState params = state.getParams();
		StateDiff<NetworkParamsState> paramsDiff = new WorldStateDiff<>(
				NetworkParamsStateImpl.ZERO, params);
		Map<Address, StateDiff<TokenState>> tokenDiffs = new LinkedHashMap<>();
		TokenState nativeToken = state.getToken(Address.NATIVE_TOKEN);
		if (!TokenStateImpl.ZERO.equals(nativeToken)) {
			tokenDiffs.put(Address.NATIVE_TOKEN,
					new WorldStateDiff<>(TokenStateImpl.ZERO, nativeToken));
		}
		Map<Address, AuthorityState> authorities = new LinkedHashMap<>();
		for (Address address : settings.genesisAuthorityAddresses()) {
			AuthorityState value = state.getAuthority(address);
			if (!AuthorityStateImpl.ZERO.equals(value)) {
				authorities.put(address, value);
			}
		}
		Map<Address, ValidatorState> validators = new LinkedHashMap<>();
		for (Address address : settings.genesisValidatorAddresses()) {
			ValidatorState value = state.getValidator(address);
			if (!ValidatorStateImpl.ZERO.equals(value)) {
				validators.put(address, value);
			}
		}
		Map<BalanceKey, StateDiff<AccountBalanceState>> balances = new LinkedHashMap<>();
		for (Map.Entry<Address, Wei> allocation : settings.genesisInitialBalances().entrySet()) {
			if (allocation.getValue().compareTo(Wei.ZERO) > 0) {
				BalanceKey key = new BalanceKey(allocation.getKey(), Address.NATIVE_TOKEN);
				balances.put(key, new WorldStateDiff<>(
						AccountBalanceStateImpl.ZERO,
						state.getBalance(allocation.getKey(), Address.NATIVE_TOKEN)));
			}
		}
		return new BlockConnectedEvent(
				this, ConnectedSource.GENESIS, block, balances, Collections.emptyMap(), tokenDiffs,
				Collections.emptyMap(), paramsDiff, authorities, Collections.emptyMap(), validators,
				Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Wei.ZERO, Wei.ZERO,
				stored.getCumulativeDifficulty(), Collections.emptyMap(), List.of(), stored.getReceivedFrom(),
				stored.getReceivedAt());
	}

	private BlockConnectedEvent executeBlock(WorldState state, StoredBlock stored) {
		Block block = stored.getBlock();
		ExecutionResult result = stateProcessor.executeTransactions(
				state, new StateProcessor.SimpleBlock(block), block.getTxs(), state.getParams());
		Wei fees = result.getTotalFeesCollected();
		Wei reward = result.getMinerActualRewardPaid();
		List<BlockEvent> events = blockEventExtractor.extractEvents(
				reward.subtract(fees), fees, block.getHeader().getCoinbase(), result.getMinerRewardPoolAddress(),
				result.getMinerRewardUnlockBlockHeight(), state.getBipDiffs(), state.getTokenDiffs(),
				result.getActualBurnAmounts(), state.getParamsDiff());
		return new BlockConnectedEvent(
				this, ConnectedSource.SYNC, block, state.getBalanceDiffs(), state.getNonceDiffs(),
				state.getTokenDiffs(), state.getBipDiffs(), state.getParamsDiff(), state.getDirtyAuthorities(),
				state.getAuthoritiesRemovedWithState(), state.getDirtyValidators(),
				state.getValidatorsRemovedWithState(), state.getDirtyAddressAliases(),
				state.getAliasesRemovedWithState(), fees, reward, stored.getCumulativeDifficulty(),
				result.getActualBurnAmounts(), events, stored.getReceivedFrom(), stored.getReceivedAt());
	}
}
