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
package global.goldenera.node.core.api.v1.miningeconomics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.serialization.state.miningwindow.MiningWindowStateEncoder;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.api.v1.miningeconomics.MiningEconomicsDiagnosticDtoV1.ValidatorPolicy;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.MiningEconomicsActivationService;
import global.goldenera.node.core.processing.ValidatorMiningViewService;
import global.goldenera.node.core.processing.ValidatorMiningViewService.ValidatorMiningView;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.enums.MiningPolicySource;
import global.goldenera.node.shared.properties.GeneralProperties;

class MiningEconomicsDiagnosticServiceTest {

	private static final Instant TIME = Instant.parse("2026-01-02T03:04:05Z");
	private static final Address LIMITED = address(1);
	private static final Address UNLIMITED = address(2);
	private static final Hash ROOT = hash(11);
	private static final Hash BLOCK_HASH = hash(12);

	@Test
	void capturesOneSortedVerifiableViewBoundToTheCanonicalHead() throws Exception {
		Fixture fixture = fixture(window(100, List.of(UNLIMITED, LIMITED, UNLIMITED)));

		MiningEconomicsDiagnosticDtoV1 diagnostic = fixture.service.capture();

		assertThat(diagnostic.anchor().height()).isEqualTo(42);
		assertThat(diagnostic.anchor().blockHash()).isEqualTo(BLOCK_HASH);
		assertThat(diagnostic.anchor().stateRoot()).isEqualTo(ROOT);
		assertThat(diagnostic.network()).isEqualTo(Network.TESTNET);
		assertThat(diagnostic.activeFork()).isEqualTo(ForkName.MINING_ECONOMICS);
		assertThat(diagnostic.activeForkActivationHeight()).isEqualTo(10);
		assertThat(diagnostic.activeConsensusLimits().maxBlockSizeInBytes()).isEqualTo(4_000_000);
		assertThat(diagnostic.networkParams().limitedValidatorMiningSharesBps()).containsExactly(4_000L);

		assertThat(diagnostic.validatorPolicies())
				.extracting(ValidatorPolicy::identity)
				.containsExactly(LIMITED, UNLIMITED);
		assertThat(diagnostic.validatorPolicies().getFirst().mode()).isEqualTo(MiningLimitMode.LIMITED);
		assertThat(diagnostic.validatorPolicies().getFirst().blocksMinedInCurrentWindow()).isEqualTo(1);
		assertThat(diagnostic.validatorPolicies().getFirst().remainingBlocksInCurrentWindow()).isEqualTo(39);

		Bytes windowBytes = MiningWindowStateEncoder.INSTANCE.encode(fixture.window);
		assertThat(diagnostic.miningWindow().present()).isTrue();
		assertThat(diagnostic.miningWindow().orderedValidatorIdentities())
				.containsExactly(UNLIMITED, LIMITED, UNLIMITED);
		assertThat(diagnostic.miningWindow().validatorBlockCounts().keySet())
				.containsExactly(LIMITED, UNLIMITED);
		assertThat(diagnostic.miningWindow().canonicalEncoding().byteLength()).isEqualTo(windowBytes.size());
		assertThat(diagnostic.miningWindow().canonicalEncoding().bytesHex()).isEqualTo(windowBytes.toHexString());
		assertThat(diagnostic.miningWindow().canonicalEncoding().digest())
				.isEqualTo(Hash.wrap(MessageDigest.getInstance("SHA-256").digest(windowBytes.toArrayUnsafe())));
		verify(fixture.activationService).assertHeadReady(fixture.state, 42);
	}

	@Test
	void failsClosedWhenTheCanonicalHeadChangesDuringCapture() {
		Fixture fixture = fixture(window(100, List.of(LIMITED)));
		StoredBlock racedHead = storedBlock(43, hash(91), hash(92));
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(racedHead);

		assertThatThrownBy(fixture.service::capture)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Canonical head changed");
	}

	@Test
	void failsClosedWhenValidatorIndexDoesNotMatchAnchoredTrie() {
		Fixture fixture = fixture(window(100, List.of(LIMITED)));
		when(fixture.state.getValidator(LIMITED)).thenReturn(validator(MiningLimitMode.UNLIMITED, 0));

		assertThatThrownBy(fixture.service::capture)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Validator index does not match");
	}

	@Test
	void failsClosedWhenNoCanonicalAnchorCanBeCaptured() {
		ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
		when(cache.getHeadSnapshot()).thenThrow(new IllegalStateException("No canonical head"));
		MiningEconomicsDiagnosticService service = new MiningEconomicsDiagnosticService(
				cache,
				mock(ChainQuery.class),
				mock(EntityIndexRepository.class),
				mock(ValidatorMiningViewService.class),
				mock(MiningEconomicsActivationService.class),
				mock(NetworkSettingsProvider.class),
				mock(GeneralProperties.class));

		assertThatThrownBy(service::capture)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("No canonical head");
	}

	@Test
	void omitsCanonicalBytesAboveBoundButKeepsDigestAndLength() {
		MiningWindowState largeWindow = MiningWindowStateImpl.empty(10_000, 10);
		for (int index = 0; index < 4_000; index++) {
			largeWindow = ((MiningWindowStateImpl) largeWindow).append(LIMITED, 11L + index);
		}
		Fixture fixture = fixture(largeWindow);

		var encoding = fixture.service.capture().miningWindow().canonicalEncoding();

		assertThat(encoding.byteLength()).isGreaterThan(
				MiningEconomicsDiagnosticService.MAX_CANONICAL_BYTES_IN_RESPONSE);
		assertThat(encoding.digest()).isNotNull();
		assertThat(encoding.bytesHex()).isNull();
	}

	private Fixture fixture(MiningWindowState window) {
		NetworkParamsState params = params(window.getWindowSize());
		ValidatorState limited = validator(MiningLimitMode.LIMITED, 4_000);
		ValidatorState unlimited = validator(MiningLimitMode.UNLIMITED, 0);
		WorldState state = mock(WorldState.class);
		when(state.getFinalStateRoot()).thenReturn(ROOT);
		when(state.getParams()).thenReturn(params);
		when(state.isMiningWindowTriePresent()).thenReturn(true);
		when(state.getMiningWindow()).thenReturn(window);
		when(state.getValidator(LIMITED)).thenReturn(limited);
		when(state.getValidator(UNLIMITED)).thenReturn(unlimited);

		StoredBlock anchor = storedBlock(42, BLOCK_HASH, ROOT);
		ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
		when(cache.getHeadSnapshot()).thenReturn(new HeadStateSnapshot(anchor, state));
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(anchor);

		EntityIndexRepository index = mock(EntityIndexRepository.class);
		Map<Address, ValidatorState> reversedIndex = new LinkedHashMap<>();
		reversedIndex.put(UNLIMITED, unlimited);
		reversedIndex.put(LIMITED, limited);
		when(index.getAllValidatorsWithAddresses()).thenReturn(reversedIndex);

		ValidatorMiningViewService views = mock(ValidatorMiningViewService.class);
		long limitedMined = window.getValidatorBlockCounts().getOrDefault(LIMITED, 0L);
		long unlimitedMined = window.getValidatorBlockCounts().getOrDefault(UNLIMITED, 0L);
		when(views.evaluate(state, LIMITED, limited)).thenReturn(new ValidatorMiningView(
				MiningPolicySource.EXPLICIT, MiningLimitMode.LIMITED, 4_000,
				40L, limitedMined, Math.max(0, 40 - limitedMined), limitedMined < 40));
		when(views.evaluate(state, UNLIMITED, unlimited)).thenReturn(new ValidatorMiningView(
				MiningPolicySource.EXPLICIT, MiningLimitMode.UNLIMITED, 0,
				null, unlimitedMined, null, true));

		MiningEconomicsActivationService activation = mock(MiningEconomicsActivationService.class);
		NetworkSettings settings = mock(NetworkSettings.class);
		when(settings.forkActivationBlocks()).thenReturn(Map.of(
				ForkName.GENESIS, 0L,
				ForkName.MINING_ECONOMICS, 10L));
		when(settings.getMaxHeaderSizeInBytes(42)).thenReturn(1_000_000L);
		when(settings.getMaxTxSizeInBytes(42)).thenReturn(2_000_000L);
		when(settings.getMaxBlockSizeInBytes(42)).thenReturn(4_000_000L);
		when(settings.getMaxTxCountPerBlock(42)).thenReturn(3_000L);
		NetworkSettingsProvider settingsProvider = mock(NetworkSettingsProvider.class);
		when(settingsProvider.currentSettings()).thenReturn(settings);
		GeneralProperties general = mock(GeneralProperties.class);
		when(general.getNetwork()).thenReturn(Network.TESTNET);

		MiningEconomicsDiagnosticService service = new MiningEconomicsDiagnosticService(
				cache, chainQuery, index, views, activation, settingsProvider, general);
		return new Fixture(service, chainQuery, activation, state, window);
	}

	private NetworkParamsState params(long windowSize) {
		return NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.blockReward(Wei.valueOf(10))
				.blockRewardPoolAddress(Address.ZERO)
				.targetMiningTimeMs(30_000)
				.asertHalfLifeBlocks(64)
				.asertAnchorHeight(0)
				.minDifficulty(BigInteger.ONE)
				.minTxBaseFee(Wei.valueOf(1))
				.minTxByteFee(Wei.valueOf(1))
				.updatedByTxHash(Hash.ZERO)
				.currentAuthorityCount(1)
				.currentValidatorCount(2)
				.currentUnlimitedValidatorCount(1)
				.limitedValidatorMiningSharesBps(List.of(4_000L))
				.validatorMiningWindowBlocks(windowSize)
				.updatedAtBlockHeight(10)
				.updatedAtTimestamp(TIME)
				.build();
	}

	private ValidatorState validator(MiningLimitMode mode, long share) {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(0)
				.createdAtTimestamp(TIME)
				.miningLimitMode(mode)
				.maxMiningShareBps(share)
				.policyUpdatedByTxHash(Hash.ZERO)
				.policyUpdatedAtBlockHeight(10)
				.policyUpdatedAtTimestamp(TIME)
				.build();
	}

	private MiningWindowState window(long size, List<Address> identities) {
		MiningWindowState result = MiningWindowStateImpl.empty(size, 10);
		long height = 11;
		for (Address identity : identities) {
			result = ((MiningWindowStateImpl) result).append(identity, height++);
		}
		return result;
	}

	private StoredBlock storedBlock(long height, Hash hash, Hash stateRoot) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getHeight()).thenReturn(height);
		when(header.getStateRootHash()).thenReturn(stateRoot);
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		StoredBlock storedBlock = mock(StoredBlock.class);
		when(storedBlock.getHeight()).thenReturn(height);
		when(storedBlock.getHash()).thenReturn(hash);
		when(storedBlock.getBlock()).thenReturn(block);
		return storedBlock;
	}

	private static Address address(long value) {
		return Address.fromHexString(String.format("0x%040x", value));
	}

	private static Hash hash(long value) {
		return Hash.fromHexString(String.format("0x%064x", value));
	}

	private record Fixture(
			MiningEconomicsDiagnosticService service,
			ChainQuery chainQuery,
			MiningEconomicsActivationService activationService,
			WorldState state,
			MiningWindowState window) {
	}
}
