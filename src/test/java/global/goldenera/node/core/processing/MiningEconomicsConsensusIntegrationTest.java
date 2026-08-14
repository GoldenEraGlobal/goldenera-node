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
package global.goldenera.node.core.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayloadImpl;
import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.serialization.state.miningwindow.MiningWindowStateEncoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateEncoder;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;

class MiningEconomicsConsensusIntegrationTest {

	private static final Address LIMITED = address(1);
	private static final Address UNLIMITED = address(2);
	private static final Address THIRD = address(3);
	private static final Address BENEFICIARY = address(99);
	private static final Hash ACTION = Hash.fromHexString(
			"0x00000000000000000000000000000000000000000000000000000000000000aa");
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
	private static final String SHARED_MINING_WINDOW_VECTOR = "0xf8730164f83f"
			+ "941111111111111111111111111111111111111111"
			+ "942222222222222222222222222222222222222222"
			+ "941111111111111111111111111111111111111111"
			+ "eed694111111111111111111111111111111111111111102"
			+ "d6942222222222222222222222222222222222222222010c";
	private static final String NODE_STATE_ROOT_VECTOR =
			"0x23fa23c1a14924f0c88a843f6cafefdff579c6a88193f1f5189270c8e1e88f25";
	private static final String NODE_STATE_ROOT_AFTER_APPEND_VECTOR =
			"0xd33bc3b39ad5255eed38d3e96125ada2ef5dec35803cfda290d5acf106de81d1";

	@TempDir
	Path databaseDirectory;

	private final ValidatorMiningPolicyService policyService = new ValidatorMiningPolicyService();
	private final ValidatorMiningGovernanceService governanceService = new ValidatorMiningGovernanceService();

	@Test
	void independentBranchesPersistDifferentWindowsAndCanSwitchAcrossActivation() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState legacy = storage.createEmpty(false);
			legacy.setParams(params(NetworkParamsStateVersion.V1, 3, 0, 0));
			legacy.addValidator(LIMITED, legacyValidator());
			legacy.addValidator(UNLIMITED, legacyValidator());
			legacy.addValidator(THIRD, legacyValidator());
			Bytes legacyValidatorBytes = ValidatorStateEncoder.INSTANCE.encode(legacy.getValidator(LIMITED));
			Hash legacyRoot = storage.persist(legacy);

			StateProcessor processor = processorWithActivation();
			WorldState activationA = storage.reload(legacyRoot, false);
			processor.executeTransactions(activationA, block(10, LIMITED), List.of(), activationA.getParams());
			Hash activationRootA = storage.persist(activationA);

			WorldState activationB = storage.reload(legacyRoot, false);
			processor.executeTransactions(activationB, block(10, UNLIMITED), List.of(), activationB.getParams());
			Hash activationRootB = storage.persist(activationB);

			assertThat(activationRootB).isEqualTo(activationRootA);
			assertThat(activationA.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
			assertThat(ValidatorStateEncoder.INSTANCE.encode(activationA.getValidator(LIMITED)))
					.isEqualTo(legacyValidatorBytes);

			WorldState branchA = storage.reload(activationRootA, false);
			policyService.validateCandidate(branchA, 11, LIMITED);
			processor.executeTransactions(branchA, block(11, LIMITED), List.of(), branchA.getParams());
			Hash branchARoot = storage.persist(branchA);

			WorldState branchB = storage.reload(activationRootB, false);
			policyService.validateCandidate(branchB, 11, UNLIMITED);
			processor.executeTransactions(branchB, block(11, UNLIMITED), List.of(), branchB.getParams());
			Hash branchBRoot = storage.persist(branchB);

			assertThat(branchARoot).isNotEqualTo(branchBRoot);
			assertThat(storage.reload(branchARoot, false).getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(LIMITED);
			assertThat(storage.reload(branchBRoot, false).getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(UNLIMITED);

			WorldState switchedToB = storage.reload(branchBRoot, false);
			policyService.validateCandidate(switchedToB, 12, THIRD);
			processor.executeTransactions(switchedToB, block(12, THIRD), List.of(), switchedToB.getParams());
			assertThat(switchedToB.getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(UNLIMITED, THIRD);
			assertThat(switchedToB.getMiningWindow().getOrderedValidatorIdentities()).doesNotContain(LIMITED);
		}
	}

	@Test
	void restartKeepsEligibilityAndLongLimitedAndUnlimitedSeriesHaveExactBoundary() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState initial = activeState(storage, 2, 1, 100);
			initial.addValidator(LIMITED, explicitValidator(MiningLimitMode.LIMITED, 4_000));
			initial.addValidator(UNLIMITED, explicitValidator(MiningLimitMode.UNLIMITED, 0));
			Hash root = storage.persist(initial);
			StateProcessor processor = processorWithoutActivation();

			WorldState state = storage.reload(root, false);
			for (long height = 11; height <= 50; height++) {
				policyService.validateCandidate(state, height, LIMITED);
				processor.executeTransactions(state, block(height, LIMITED), List.of(), state.getParams());
				if (height == 30) {
					root = storage.persist(state);
					state = storage.reload(root, false);
				}
			}

			Hash capRoot = storage.persist(state);
			WorldState restartedAtCap = storage.reload(capRoot, false);
			assertThat(restartedAtCap.getMiningWindow().getValidatorBlockCounts().get(LIMITED)).isEqualTo(40);
			assertThat(policyService.isCandidateEligible(restartedAtCap, 51, LIMITED)).isFalse();
			assertThatThrownBy(() -> policyService.validateCandidate(restartedAtCap, 51, LIMITED))
					.hasMessageContaining("candidate count 41 exceeds maximum 40");

			for (long height = 51; height <= 170; height++) {
				long candidateHeight = height;
				assertThatCode(() -> policyService.validateCandidate(
						restartedAtCap, candidateHeight, UNLIMITED))
						.doesNotThrowAnyException();
				processor.executeTransactions(
						restartedAtCap, block(height, UNLIMITED), List.of(), restartedAtCap.getParams());
			}

			assertThat(restartedAtCap.getMiningWindow().getOrderedValidatorIdentities()).hasSize(100);
			assertThat(restartedAtCap.getMiningWindow().getValidatorBlockCounts())
					.containsOnlyKeys(UNLIMITED)
					.containsEntry(UNLIMITED, 100L);
		}
	}

	@Test
	void governanceAtHAppliesToHPlusOneAndResizeLeavesHEmptyThenStartsAtHPlusOne() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 2, 2, 100);
			state.addValidator(LIMITED, legacyValidator());
			state.addValidator(UNLIMITED, explicitValidator(MiningLimitMode.UNLIMITED, 0));
			MiningWindowState window = MiningWindowStateImpl.empty(100, 10);
			for (long height = 11; height <= 50; height++) {
				window = ((MiningWindowStateImpl) window).append(LIMITED, height);
			}
			state.setMiningWindow(window);

			assertThatCode(() -> policyService.validateCandidate(state, 51, LIMITED)).doesNotThrowAnyException();
			governanceService.setMiningPolicy(
					state, policy(LIMITED, MiningLimitMode.LIMITED, 4_000), block(51, LIMITED), ACTION);
			policyService.appendAcceptedBlock(state, block(51, LIMITED));
			assertThatThrownBy(() -> policyService.validateCandidate(state, 52, LIMITED))
					.hasMessageContaining("candidate count 42 exceeds maximum 40");

			policyService.validateCandidate(state, 52, UNLIMITED);
			governanceService.setNetworkParams(
					state, resize(250), block(52, UNLIMITED), ACTION, true);
			policyService.appendAcceptedBlock(state, block(52, UNLIMITED));
			assertThat(state.getMiningWindow().getWindowSize()).isEqualTo(250);
			assertThat(state.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
			assertThat(state.getMiningWindow().getLastUpdatedBlockHeight()).isEqualTo(52);

			Hash resizedRoot = storage.persist(state);
			WorldState resizedState = storage.reload(resizedRoot, false);
			policyService.validateCandidate(resizedState, 53, UNLIMITED);
			policyService.appendAcceptedBlock(resizedState, block(53, UNLIMITED));
			assertThat(resizedState.getMiningWindow().getOrderedValidatorIdentities())
					.containsExactly(UNLIMITED);
		}
	}

	@Test
	void fixedSharedSerializationAndNodeStateRootVectorsRemainStable() throws Exception {
		MiningWindowState vectorWindow = MiningWindowStateImpl.empty(100, 9)
				.append(addressFromByte(0x11), 10)
				.append(addressFromByte(0x22), 11)
				.append(addressFromByte(0x11), 12);
		assertThat(MiningWindowStateEncoder.INSTANCE.encode(vectorWindow).toHexString())
				.isEqualTo(SHARED_MINING_WINDOW_VECTOR);

		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 2, 1, 100);
			state.addValidator(LIMITED, explicitValidator(MiningLimitMode.LIMITED, 4_000));
			state.addValidator(UNLIMITED, explicitValidator(MiningLimitMode.UNLIMITED, 0));
			state.setMiningWindow(vectorWindow);

			assertThat(state.calculateRootHash().toHexString()).isEqualTo(NODE_STATE_ROOT_VECTOR);

			state.setMiningWindow(((MiningWindowStateImpl) vectorWindow).append(LIMITED, 13));
			assertThat(state.calculateRootHash().toHexString())
					.isEqualTo(NODE_STATE_ROOT_AFTER_APPEND_VECTOR);
		}
	}

	@Test
	void fixedEligibilityVectorsDeclareValidAndInvalidConsensusOutcomes() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			for (EligibilityVector vector : List.of(
					new EligibilityVector("limited-at-cap", MiningLimitMode.LIMITED, 39, true),
					new EligibilityVector("limited-above-cap", MiningLimitMode.LIMITED, 40, false),
					new EligibilityVector("unlimited-above-limited-cap", MiningLimitMode.UNLIMITED, 100, true))) {
				boolean limitedMode = vector.mode() == MiningLimitMode.LIMITED;
				WorldState state = activeState(storage, limitedMode ? 2 : 1, 1, 100);
				state.addValidator(LIMITED, explicitValidator(vector.mode(),
						limitedMode ? 4_000 : 0));
				if (limitedMode) {
					state.addValidator(UNLIMITED, explicitValidator(MiningLimitMode.UNLIMITED, 0));
				}
				MiningWindowState window = MiningWindowStateImpl.empty(100, 10);
				for (int index = 0; index < vector.parentCount(); index++) {
					window = ((MiningWindowStateImpl) window).append(LIMITED, 11L + index);
				}
				state.setMiningWindow(window);

				assertThat(policyService.isCandidateEligible(state, 111, LIMITED))
						.as("%s expected %s", vector.name(), vector.expectedValid() ? "VALID" : "INVALID")
						.isEqualTo(vector.expectedValid());
			}
		}
	}

	private StateProcessor processorWithActivation() {
		return new StateProcessor(List.of(), new MiningEconomicsActivationService(), policyService);
	}

	private StateProcessor processorWithoutActivation() {
		return new StateProcessor(List.of(), mock(MiningEconomicsActivationService.class), policyService);
	}

	private WorldState activeState(PersistentWorldStateTestSupport storage, long validatorCount,
			long unlimitedCount, long window) {
		WorldState state = storage.createEmpty(false);
		state.setParams(params(NetworkParamsStateVersion.V2, validatorCount, unlimitedCount, window));
		state.setMiningWindow(MiningWindowStateImpl.empty(window, 10));
		return state;
	}

	private NetworkParamsStateImpl params(NetworkParamsStateVersion version, long validatorCount,
			long unlimitedCount, long window) {
		NetworkParamsStateImpl.NetworkParamsStateImplBuilder builder = NetworkParamsStateImpl.builder()
				.version(version)
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
				.currentValidatorCount(validatorCount)
				.updatedAtBlockHeight(0)
				.updatedAtTimestamp(TIME);
		if (version == NetworkParamsStateVersion.V2) {
			builder.currentUnlimitedValidatorCount(unlimitedCount)
					.limitedValidatorMiningSharesBps(Collections.nCopies(
							Math.toIntExact(validatorCount - unlimitedCount), 4_000L))
					.validatorMiningWindowBlocks(window);
		}
		return builder.build();
	}

	private ValidatorStateImpl legacyValidator() {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V1)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(TIME)
				.build();
	}

	private ValidatorStateImpl explicitValidator(MiningLimitMode mode, long share) {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(TIME)
				.miningLimitMode(mode)
				.maxMiningShareBps(share)
				.policyUpdatedByTxHash(Hash.ZERO)
				.policyUpdatedAtBlockHeight(1)
				.policyUpdatedAtTimestamp(TIME)
				.build();
	}

	private TxBipValidatorMiningPolicySetPayloadImpl policy(Address address, MiningLimitMode mode, long share) {
		return TxBipValidatorMiningPolicySetPayloadImpl.builder()
				.validatorAddress(address)
				.miningLimitMode(mode)
				.maxMiningShareBps(share)
				.build();
	}

	private TxBipNetworkParamsSetPayloadImpl resize(long window) {
		return TxBipNetworkParamsSetPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.validatorMiningWindowBlocks(window)
				.build();
	}

	private SimpleBlock block(long height, Address identity) {
		return SimpleBlock.builder()
				.height(height)
				.timestamp(TIME.plusSeconds(height))
				.coinbase(BENEFICIARY)
				.identity(identity)
				.build();
	}

	private static Address address(int suffix) {
		return Address.fromHexString(String.format("0x%040x", suffix));
	}

	private static Address addressFromByte(int value) {
		return Address.fromHexString("0x" + String.format("%02x", value).repeat(20));
	}

	private record EligibilityVector(String name, MiningLimitMode mode, int parentCount, boolean expectedValid) {
	}
}
