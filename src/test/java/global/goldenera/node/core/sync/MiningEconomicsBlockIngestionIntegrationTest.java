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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.reorg.ChainSwitchService;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.processing.MiningEconomicsActivationService;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.state.WorldStateSerialization;
import global.goldenera.node.core.state.trie.rocksdb.RocksDBMerkleStorageFactory;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MiningEconomicsBlockIngestionIntegrationTest {

	private static final PrivateKey MINER_A_KEY = key(1);
	private static final PrivateKey MINER_B_KEY = key(2);
	private static final Address MINER_A = MINER_A_KEY.getAddress();
	private static final Address MINER_B = MINER_B_KEY.getAddress();
	private static final Address BENEFICIARY = Address.fromHexString(
			"0x00000000000000000000000000000000000000f0");
	private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

	@TempDir
	Path databaseDirectory;

	@Test
	void normalConnectPersistsSignedIdentityWindowAndMatchingStateRoot() throws Exception {
		try (Fixture fixture = new Fixture(databaseDirectory)) {
			StoredBlock base = fixture.seedLegacyCanonicalBlock();
			Block activation = fixture.buildValidChild(base.getBlock(), MINER_A_KEY);
			assertThat(fixture.ingest(activation)).isEqualTo(BlockIngestionService.IngestionResult.SUCCESS);

			Block firstTracked = fixture.buildValidChild(activation, MINER_B_KEY);
			assertThat(fixture.ingest(firstTracked)).isEqualTo(BlockIngestionService.IngestionResult.SUCCESS);

			StoredBlock head = fixture.chainQuery.getLatestStoredBlockOrThrow();
			WorldState persisted = fixture.worldStateFactory.createForValidation(
					head.getBlock().getHeader().getStateRootHash());
			assertThat(head.getHash()).isEqualTo(firstTracked.getHash());
			assertThat(firstTracked.getHeader().getIdentity()).isEqualTo(MINER_B);
			assertThat(firstTracked.getHeader().getIdentity()).isNotEqualTo(BENEFICIARY);
			assertThat(persisted.getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(MINER_B);
			assertThat(persisted.calculateRootHash()).isEqualTo(firstTracked.getHeader().getStateRootHash());
		}
	}

	@Test
	void orphanChildIsReplayedThroughRealIngestionAndReorgWhenParentArrives() throws Exception {
		try (Fixture fixture = new Fixture(databaseDirectory)) {
			StoredBlock base = fixture.seedLegacyCanonicalBlock();
			Block canonicalActivation = fixture.buildValidChild(base.getBlock(), MINER_A_KEY);
			fixture.ingest(canonicalActivation);

			Block forkActivation = fixture.buildValidChild(base.getBlock(), MINER_B_KEY);
			Block orphanChild = fixture.buildValidChild(forkActivation, MINER_B_KEY);
			assertThat(fixture.ingest(orphanChild))
					.isEqualTo(BlockIngestionService.IngestionResult.ORPHAN_BUFFERED);
			assertThat(fixture.ingestionService.isOrphan(orphanChild.getHash())).isTrue();

			assertThat(fixture.ingest(forkActivation)).isEqualTo(BlockIngestionService.IngestionResult.SUCCESS);

			StoredBlock head = fixture.chainQuery.getLatestStoredBlockOrThrow();
			WorldState reorged = fixture.worldStateFactory.createForValidation(
					head.getBlock().getHeader().getStateRootHash());
			assertThat(fixture.ingestionService.isOrphan(orphanChild.getHash())).isFalse();
			assertThat(head.getHash()).isEqualTo(orphanChild.getHash());
			assertThat(reorged.getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(MINER_B)
					.doesNotContain(MINER_A);
		}
	}

	@Test
	void headersFirstBodyBatchHandoffCreatesEmptyActivationThenOrderedWindow() throws Exception {
		try (Fixture fixture = new Fixture(databaseDirectory)) {
			StoredBlock base = fixture.seedLegacyCanonicalBlock();
			Block activation = fixture.buildValidChild(base.getBlock(), MINER_A_KEY);
			Block firstTracked = fixture.buildValidChild(activation, MINER_B_KEY);
			Block secondTracked = fixture.buildValidChild(firstTracked, MINER_A_KEY);
			List<StoredBlock> batch = fixture.asSyncBatch(base,
					List.of(activation, firstTracked, secondTracked));

			fixture.blockReorgs.executeAtomicReorgSwap(base, batch);

			StoredBlock head = fixture.chainQuery.getLatestStoredBlockOrThrow();
			WorldState synced = fixture.worldStateFactory.createForValidation(
					head.getBlock().getHeader().getStateRootHash());
			WorldState activationState = fixture.worldStateFactory.createForValidation(
					activation.getHeader().getStateRootHash());
			assertThat(head.getHash()).isEqualTo(secondTracked.getHash());
			assertThat(activationState.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
			assertThat(synced.getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(MINER_B, MINER_A);
			assertThat(synced.calculateRootHash()).isEqualTo(secondTracked.getHeader().getStateRootHash());
		}
	}

	@Test
	void higherWorkForkRebuildsWindowOnlyFromWinningBranchAcrossActivation() throws Exception {
		try (Fixture fixture = new Fixture(databaseDirectory)) {
			StoredBlock base = fixture.seedLegacyCanonicalBlock();
			Block canonicalActivation = fixture.buildValidChild(base.getBlock(), MINER_A_KEY);
			Block canonicalEleven = fixture.buildValidChild(canonicalActivation, MINER_A_KEY);
			Block canonicalTwelve = fixture.buildValidChild(canonicalEleven, MINER_A_KEY);
			fixture.ingest(canonicalActivation);
			fixture.ingest(canonicalEleven);
			fixture.ingest(canonicalTwelve);

			WorldState canonicalState = fixture.worldStateFactory.createForValidation(
					canonicalTwelve.getHeader().getStateRootHash());
			assertThat(canonicalState.getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(MINER_A, MINER_A);

			Block forkActivation = fixture.buildValidChild(base.getBlock(), MINER_B_KEY);
			Block forkEleven = fixture.buildValidChild(forkActivation, MINER_B_KEY);
			Block forkTwelve = fixture.buildValidChild(forkEleven, MINER_B_KEY);
			Block forkThirteen = fixture.buildValidChild(forkTwelve, MINER_B_KEY);
			fixture.ingest(forkActivation);
			fixture.ingest(forkEleven);
			fixture.ingest(forkTwelve);
			assertThat(fixture.chainQuery.getLatestStoredBlockOrThrow().getHash())
					.isEqualTo(canonicalTwelve.getHash());

			fixture.ingest(forkThirteen);

			StoredBlock reorgedHead = fixture.chainQuery.getLatestStoredBlockOrThrow();
			WorldState reorged = fixture.worldStateFactory.createForValidation(
					reorgedHead.getBlock().getHeader().getStateRootHash());
			assertThat(reorgedHead.getHash()).isEqualTo(forkThirteen.getHash());
			assertThat(reorged.getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(MINER_B, MINER_B, MINER_B)
					.doesNotContain(MINER_A);
			assertThat(reorged.calculateRootHash()).isEqualTo(forkThirteen.getHeader().getStateRootHash());
		}
	}

	@Test
	void syncBatchRejectsFirstBlockOverQuotaWithoutMovingCanonicalHead() throws Exception {
		try (Fixture fixture = new Fixture(databaseDirectory)) {
			StoredBlock base = fixture.seedActiveCanonicalBlock();
			List<Block> blocks = new ArrayList<>();
			Block parent = base.getBlock();
			for (int index = 0; index < 41; index++) {
				Block child = fixture.buildValidChild(parent, MINER_A_KEY);
				blocks.add(child);
				parent = child;
			}
			List<StoredBlock> batch = fixture.asSyncBatch(base, blocks);

			assertThatThrownBy(() -> fixture.blockReorgs.executeAtomicReorgSwap(base, batch))
					.hasMessageContaining("candidate count 41 exceeds maximum 40");

			assertThat(fixture.chainQuery.getLatestStoredBlockOrThrow().getHash()).isEqualTo(base.getHash());
			assertThat(fixture.chainQuery.getStoredBlockByHeight(11)).isEmpty();
		}
	}

	private static PrivateKey key(int value) {
		return PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", value)));
	}

	private static final class Fixture implements AutoCloseable {

		private static final List<String> COLUMN_FAMILIES = List.of(
				RocksDbColumnFamilies.CF_BLOCKS,
				RocksDbColumnFamilies.CF_STATE_TRIE,
				RocksDbColumnFamilies.CF_TX_INDEX,
				RocksDbColumnFamilies.CF_HASH_BY_HEIGHT,
				RocksDbColumnFamilies.CF_METADATA,
				RocksDbColumnFamilies.CF_TOKENS,
				RocksDbColumnFamilies.CF_AUTHORITIES,
				RocksDbColumnFamilies.CF_VALIDATORS,
				RocksDbColumnFamilies.CF_ENTITY_UNDO_LOG);

		private final DBOptions dbOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true);
		private final List<ColumnFamilyOptions> columnOptions = new ArrayList<>();
		private final List<ColumnFamilyHandle> handles = new ArrayList<>();
		private final RocksDB database;
		private final RocksDBRepository rocksRepository;
		private final PersistentWorldStateTestSupport expectedStates;
		private final BlockRepository blockRepository;
		private final ChainQuery chainQuery;
		private final WorldStateFactory worldStateFactory;
		private final StateProcessor stateProcessor;
		private final BlockIngestionService ingestionService;
		private final ChainSwitchService chainSwitchService;
		private final BlockReorgs blockReorgs;

		private Fixture(Path directory) throws Exception {
			RocksDB.loadLibrary();
			List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
			columnOptions.add(new ColumnFamilyOptions());
			descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnOptions.get(0)));
			for (String family : COLUMN_FAMILIES) {
				ColumnFamilyOptions options = new ColumnFamilyOptions();
				columnOptions.add(options);
				descriptors.add(new ColumnFamilyDescriptor(family.getBytes(StandardCharsets.UTF_8), options));
			}
			database = RocksDB.open(dbOptions, directory.resolve("node").toString(), descriptors, handles);
			expectedStates = new PersistentWorldStateTestSupport(directory.resolve("expected"));

			RocksDbColumnFamilies families = new RocksDbColumnFamilies();
			families.addHandle("default", handles.get(0));
			for (int index = 0; index < COLUMN_FAMILIES.size(); index++) {
				families.addHandle(COLUMN_FAMILIES.get(index), handles.get(index + 1));
			}
			rocksRepository = new RocksDBRepository(database, families);
			blockRepository = new BlockRepository(
					rocksRepository,
					families,
					Caffeine.newBuilder().build(),
					Caffeine.newBuilder().build(),
					Caffeine.newBuilder().build(),
					Caffeine.newBuilder().build());
			chainQuery = new ChainQuery(blockRepository);

			RocksDBMerkleStorageFactory storageFactory = new RocksDBMerkleStorageFactory(
					database, families, Caffeine.newBuilder().build());
			WorldStateSerialization serialization = new WorldStateSerialization();
			worldStateFactory = new WorldStateFactory(
					storageFactory,
					serialization.rootStateSerializer(),
					serialization.rootStateDeserializer(),
					serialization.balanceSerializer(),
					serialization.balanceDeserializer(),
					serialization.nonceSerializer(),
					serialization.nonceDeserializer(),
					serialization.addressAliasSerializer(),
					serialization.addressAliasDeserializer(),
					serialization.authoritySerializer(),
					serialization.authorityDeserializer(),
					serialization.validatorSerializer(),
					serialization.validatorDeserializer(),
					serialization.bipStateSerializer(),
					serialization.bipStateDeserializer(),
					serialization.networkParamsSerializer(),
					serialization.networkParamsDeserializer(),
					serialization.miningWindowSerializer(),
					serialization.miningWindowDeserializer(),
					serialization.tokenSerializer(),
					serialization.tokenDeserializer());

			ValidatorMiningPolicyService policyService = new ValidatorMiningPolicyService();
			stateProcessor = new StateProcessor(
					List.of(), new MiningEconomicsActivationService(), policyService);
			TxValidator txValidator = mock(TxValidator.class);
			DifficultyCalculator difficulty = mock(DifficultyCalculator.class);
			when(difficulty.calculateNextDifficulty(any(), any())).thenReturn(BigInteger.ONE);
			BlockValidator blockValidator = new BlockValidator(
					mock(RandomXManager.class),
					difficulty,
					mock(CheckpointRegistry.class),
					txValidator,
					policyService);
			ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
			EntityIndexRepository entityIndex = mock(EntityIndexRepository.class);
			ReentrantLock lock = new ReentrantLock();
			BlockEventExtractor eventExtractor = new BlockEventExtractor();
			chainSwitchService = new ChainSwitchService(
					chainQuery,
					blockRepository,
					worldStateFactory,
					stateProcessor,
					blockValidator,
					events,
					lock,
					entityIndex,
					eventExtractor);
			blockReorgs = new BlockReorgs(chainSwitchService);
			BlockStateTransitions transitions = new BlockStateTransitions(
					blockRepository,
					chainQuery,
					chainSwitchService,
					events,
					lock,
					entityIndex,
					eventExtractor);
			SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
			BlockOrphanBufferService orphanBuffer = new BlockOrphanBufferService(
					meterRegistry, mock(ThreadPoolTaskScheduler.class));
			ingestionService = new BlockIngestionService(
					lock,
					meterRegistry,
					chainQuery,
					blockValidator,
					txValidator,
					stateProcessor,
					worldStateFactory,
					transitions,
					orphanBuffer);
		}

		private StoredBlock seedLegacyCanonicalBlock() {
			WorldState state = worldStateFactory.create(Hash.wrap(MerkleTrie.EMPTY_TRIE_NODE_HASH), false);
			initializeLegacyState(state);
			Hash root = state.calculateRootHash();
			WorldState expectedState = expectedStates.createEmpty(false);
			initializeLegacyState(expectedState);
			try {
				assertThat(expectedStates.persist(expectedState)).isEqualTo(root);
			} catch (Exception exception) {
				throw new IllegalStateException("Unable to seed expected legacy state", exception);
			}
			Block block = signedBlock(9, Hash.ZERO, root, MINER_A_KEY);
			StoredBlock stored = stored(block, BigInteger.TEN);
			rocksRepository.executeAtomicBatch(batch -> {
				state.persistToBatch(batch);
				blockRepository.addBlockToBatch(batch, stored);
			});
			return stored;
		}

		private StoredBlock seedActiveCanonicalBlock() {
			WorldState state = worldStateFactory.create(Hash.wrap(MerkleTrie.EMPTY_TRIE_NODE_HASH), false);
			initializeActiveState(state);
			Hash root = state.calculateRootHash();
			WorldState expectedState = expectedStates.createEmpty(false);
			initializeActiveState(expectedState);
			try {
				assertThat(expectedStates.persist(expectedState)).isEqualTo(root);
			} catch (Exception exception) {
				throw new IllegalStateException("Unable to seed expected active state", exception);
			}
			Block block = signedBlock(10, Hash.ZERO, root, MINER_B_KEY);
			StoredBlock stored = stored(block, BigInteger.valueOf(11));
			rocksRepository.executeAtomicBatch(batch -> {
				state.persistToBatch(batch);
				blockRepository.addBlockToBatch(batch, stored);
			});
			return stored;
		}

		private Block buildValidChild(Block parent, PrivateKey minerKey) {
			long height = parent.getHeight() + 1;
			WorldState state = expectedStates.factory().createForValidation(parent.getHeader().getStateRootHash());
			Block provisional = signedBlock(height, parent.getHash(), Hash.ZERO, minerKey);
			stateProcessor.executeTransactions(
					state, new SimpleBlock(provisional), List.of(), state.getParams());
			Hash root = state.calculateRootHash();
			try {
				expectedStates.persist(state);
			} catch (Exception exception) {
				throw new IllegalStateException("Unable to persist expected child state", exception);
			}
			return signedBlock(height, parent.getHash(), root, minerKey);
		}

		private void initializeLegacyState(WorldState state) {
			state.setParams(legacyParams());
			state.addValidator(MINER_A, legacyValidator());
			state.addValidator(MINER_B, legacyValidator());
		}

		private void initializeActiveState(WorldState state) {
			state.setParams(activeParams());
			state.setMiningWindow(MiningWindowStateImpl.empty(100, 10));
			state.addValidator(MINER_A, explicitValidator(MiningLimitMode.LIMITED, 4_000));
			state.addValidator(MINER_B, explicitValidator(MiningLimitMode.UNLIMITED, 0));
		}

		private BlockIngestionService.IngestionResult ingest(Block block) {
			return ingestionService.processBlock(
					block, ConnectedSource.BROADCAST, MINER_A, Instant.now());
		}

		private List<StoredBlock> asSyncBatch(StoredBlock parent, List<Block> blocks) {
			List<StoredBlock> stored = new ArrayList<>();
			BigInteger cumulativeDifficulty = parent.getCumulativeDifficulty();
			for (Block block : blocks) {
				cumulativeDifficulty = cumulativeDifficulty.add(block.getHeader().getDifficulty());
				stored.add(stored(block, cumulativeDifficulty));
			}
			return stored;
		}

		private StoredBlock stored(Block block, BigInteger cumulativeDifficulty) {
			return StoredBlock.builder()
					.block(block)
					.cumulativeDifficulty(cumulativeDifficulty)
					.receivedAt(block.getHeader().getTimestamp())
					.receivedFrom(MINER_A)
					.connectedSource(ConnectedSource.REORG)
					.identity(block.getHeader().getIdentity())
					.computeIndexes()
					.build();
		}

		private Block signedBlock(long height, Hash previousHash, Hash stateRoot, PrivateKey minerKey) {
			BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
					.version(BlockVersion.V1)
					.height(height)
					.timestamp(BASE_TIME.plusSeconds(height))
					.previousHash(previousHash)
					.txRootHash(TxRootUtil.txRootHash(List.of()))
					.stateRootHash(stateRoot)
					.difficulty(BigInteger.ONE)
					.coinbase(BENEFICIARY)
					.nonce(height)
					.build();
			BlockHeader signed = unsigned.toBuilder()
					.signature(minerKey.sign(BlockHeaderUtil.hashForSigning(unsigned)))
					.build();
			return BlockImpl.builder().header(signed).txs(List.of()).build();
		}

		private NetworkParamsStateImpl legacyParams() {
			return NetworkParamsStateImpl.builder()
					.version(NetworkParamsStateVersion.V1)
					.blockReward(Wei.ZERO)
					.blockRewardPoolAddress(Address.ZERO)
					.targetMiningTimeMs(30_000)
					.asertHalfLifeBlocks(64)
					.asertAnchorHeight(0)
					.minDifficulty(BigInteger.ONE)
					.minTxBaseFee(Wei.ZERO)
					.minTxByteFee(Wei.ZERO)
					.updatedByTxHash(Hash.ZERO)
					.currentAuthorityCount(1)
					.currentValidatorCount(2)
					.updatedAtBlockHeight(0)
					.updatedAtTimestamp(BASE_TIME)
					.build();
		}

		private NetworkParamsStateImpl activeParams() {
			return NetworkParamsStateImpl.builder()
					.version(NetworkParamsStateVersion.V2)
					.blockReward(Wei.ZERO)
					.blockRewardPoolAddress(Address.ZERO)
					.targetMiningTimeMs(30_000)
					.asertHalfLifeBlocks(64)
					.asertAnchorHeight(0)
					.minDifficulty(BigInteger.ONE)
					.minTxBaseFee(Wei.ZERO)
					.minTxByteFee(Wei.ZERO)
					.updatedByTxHash(Hash.ZERO)
					.currentAuthorityCount(1)
					.currentValidatorCount(2)
					.currentUnlimitedValidatorCount(1)
					.validatorMiningWindowBlocks(100)
					.updatedAtBlockHeight(10)
					.updatedAtTimestamp(BASE_TIME)
					.build();
		}

		private ValidatorStateImpl legacyValidator() {
			return ValidatorStateImpl.builder()
					.version(ValidatorStateVersion.V1)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(0)
					.createdAtTimestamp(BASE_TIME)
					.build();
		}

		private ValidatorStateImpl explicitValidator(MiningLimitMode mode, long shareBps) {
			return ValidatorStateImpl.builder()
					.version(ValidatorStateVersion.V2)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(0)
					.createdAtTimestamp(BASE_TIME)
					.miningLimitMode(mode)
					.maxMiningShareBps(shareBps)
					.policyUpdatedByTxHash(Hash.ZERO)
					.policyUpdatedAtBlockHeight(10)
					.policyUpdatedAtTimestamp(BASE_TIME)
					.build();
		}

		@Override
		public void close() {
			expectedStates.close();
			for (int index = handles.size() - 1; index >= 0; index--) {
				handles.get(index).close();
			}
			database.close();
			columnOptions.forEach(ColumnFamilyOptions::close);
			dbOptions.close();
		}
	}
}
