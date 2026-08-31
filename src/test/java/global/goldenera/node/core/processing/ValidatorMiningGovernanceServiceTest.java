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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.LongStream;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayloadImpl;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;

class ValidatorMiningGovernanceServiceTest {

	private static final Address FIRST = Address.fromHexString("0x0000000000000000000000000000000000000001");
	private static final Address SECOND = Address.fromHexString("0x0000000000000000000000000000000000000002");
	private static final Address THIRD = Address.fromHexString("0x0000000000000000000000000000000000000003");
	private static final Hash ORIGIN = Hash.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000011");
	private static final Hash ACTION = Hash.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000022");
	private static final Instant CREATED = Instant.parse("2025-01-02T03:04:05Z");
	private static final Instant EXECUTED = Instant.parse("2026-02-03T04:05:06Z");
	private static final SimpleBlock BLOCK = SimpleBlock.builder()
			.height(20)
			.timestamp(EXECUTED)
			.coinbase(Address.ZERO)
			.build();

	private final ValidatorMiningGovernanceService service = new ValidatorMiningGovernanceService();

	@TempDir
	Path databaseDirectory;

	@Test
	void postForkAddCreatesV2AndUpdatesBothCountersByMode() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 1, 1, 100);
			state.addValidator(FIRST, validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0));

			service.addValidator(state, add(SECOND, MiningLimitMode.LIMITED, 4000), BLOCK, ACTION, true);
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(2);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
			assertThat(state.getValidator(SECOND).getVersion()).isEqualTo(ValidatorStateVersion.V2);
			assertThat(state.getValidator(SECOND).getPolicyUpdatedByTxHash()).isEqualTo(ACTION);

			service.addValidator(state, add(THIRD, MiningLimitMode.UNLIMITED, 0), BLOCK, ACTION, true);
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(3);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(2);
		}
	}

	@Test
	void preForkAddAndRemovePreserveLegacyV1StateAndCounterSemantics() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = storage.createEmpty(false);
			state.setParams(legacyParams(1));
			state.addValidator(FIRST, validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0));
			TxBipValidatorAddPayloadImpl legacyAdd = TxBipValidatorAddPayloadImpl.builder()
					.payloadVersion(TxPayloadVersion.V1)
					.address(SECOND)
					.build();

			service.addValidator(state, legacyAdd, BLOCK, ACTION, false);
			assertThat(state.getValidator(SECOND).getVersion()).isEqualTo(ValidatorStateVersion.V1);
			assertThat(state.getParams().getVersion()).isEqualTo(NetworkParamsStateVersion.V1);
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(2);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(2);

			service.removeValidator(state, remove(SECOND), BLOCK, ACTION, false);
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(1);
			assertThat(state.getParams().getVersion()).isEqualTo(NetworkParamsStateVersion.V1);
		}
	}

	@Test
	void removeUsesEffectivePolicyAndRejectsLastUnlimitedWithoutMutation() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 2, 1, 100);
			state.addValidator(FIRST, validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0));
			state.addValidator(SECOND, validator(ValidatorStateVersion.V2, MiningLimitMode.LIMITED, 2000));

			assertThatThrownBy(() -> service.removeValidator(state, remove(FIRST), BLOCK, ACTION, true))
					.hasMessageContaining("last unlimited");
			assertThat(state.getValidator(FIRST).exists()).isTrue();
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(2);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);

			service.removeValidator(state, remove(SECOND), BLOCK, ACTION, true);
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(1);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
			assertThat(state.getValidator(SECOND).exists()).isFalse();
		}
	}

	@Test
	void zeroValidatorOpenMiningStateOnlyAcceptsUnlimitedAsFirstValidatorAndCanReturnToZero() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 0, 0, 100);

			service.assertInvariant(state.getParams());
			assertThatThrownBy(() -> service.addValidator(
					state, add(FIRST, MiningLimitMode.LIMITED, 4_000), BLOCK, ACTION, true))
					.hasMessageContaining("requires an unlimited");
			assertThat(state.getValidator(FIRST).exists()).isFalse();

			service.addValidator(state, add(FIRST, MiningLimitMode.UNLIMITED, 0), BLOCK, ACTION, true);
			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(1);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);

			service.removeValidator(state, remove(FIRST), BLOCK, ACTION, true);
			assertThat(state.getParams().getCurrentValidatorCount()).isZero();
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isZero();
			assertThat(state.getParams().getLimitedValidatorMiningSharesBps()).isEmpty();
			assertThat(state.getValidator(FIRST).exists()).isFalse();
		}
	}

	@Test
	void removingUnlimitedWithBackupDecrementsBothCounters() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 2, 2, 100);
			state.addValidator(FIRST, validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0));
			state.addValidator(SECOND, validator(ValidatorStateVersion.V2, MiningLimitMode.UNLIMITED, 0));

			service.removeValidator(state, remove(FIRST), BLOCK, ACTION, true);

			assertThat(state.getParams().getCurrentValidatorCount()).isEqualTo(1);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
		}
	}

	@Test
	void policyUpgradePreservesRegistrationMetadataAndUpdatesCategoryCounter() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 2, 2, 100);
			ValidatorStateImpl legacy = validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0);
			state.addValidator(FIRST, legacy);
			state.addValidator(SECOND, validator(ValidatorStateVersion.V2, MiningLimitMode.UNLIMITED, 0));

			service.setMiningPolicy(state, policy(FIRST, MiningLimitMode.LIMITED, 2500), BLOCK, ACTION);

			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
			assertThat(state.getValidator(FIRST).getVersion()).isEqualTo(ValidatorStateVersion.V2);
			assertThat(state.getValidator(FIRST).getOriginTxHash()).isEqualTo(legacy.getOriginTxHash());
			assertThat(state.getValidator(FIRST).getCreatedAtBlockHeight())
					.isEqualTo(legacy.getCreatedAtBlockHeight());
			assertThat(state.getValidator(FIRST).getCreatedAtTimestamp()).isEqualTo(legacy.getCreatedAtTimestamp());
			assertThat(state.getValidator(FIRST).getPolicyUpdatedByTxHash()).isEqualTo(ACTION);
			assertThat(state.getValidator(FIRST).getPolicyUpdatedAtBlockHeight()).isEqualTo(BLOCK.getHeight());
			assertThat(state.getValidator(FIRST).getPolicyUpdatedAtTimestamp()).isEqualTo(BLOCK.getTimestamp());

			service.setMiningPolicy(state, policy(FIRST, MiningLimitMode.LIMITED, 3000), BLOCK, ACTION);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
			service.setMiningPolicy(state, policy(FIRST, MiningLimitMode.UNLIMITED, 0), BLOCK, ACTION);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(2);
		}
	}

	@Test
	void transactionOrderingMustPromoteBeforeDemotingLastUnlimited() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 2, 1, 100);
			state.addValidator(FIRST, validator(ValidatorStateVersion.V2, MiningLimitMode.UNLIMITED, 0));
			state.addValidator(SECOND, validator(ValidatorStateVersion.V2, MiningLimitMode.LIMITED, 2000));

			assertThatThrownBy(() -> service.setMiningPolicy(
					state, policy(FIRST, MiningLimitMode.LIMITED, 2000), BLOCK, ACTION))
					.hasMessageContaining("last unlimited");
			assertThat(state.getValidator(FIRST).getMiningLimitMode()).isEqualTo(MiningLimitMode.UNLIMITED);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);

			service.setMiningPolicy(state, policy(SECOND, MiningLimitMode.UNLIMITED, 0), BLOCK, ACTION);
			service.setMiningPolicy(state, policy(FIRST, MiningLimitMode.LIMITED, 2000), BLOCK, ACTION);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount()).isEqualTo(1);
			assertThat(state.getValidator(FIRST).getMiningLimitMode()).isEqualTo(MiningLimitMode.LIMITED);
			assertThat(state.getValidator(SECOND).getMiningLimitMode()).isEqualTo(MiningLimitMode.UNLIMITED);
		}
	}

	@Test
	void windowIsOptionalSameSizeIsNoOpAndChangedSizeResetsAtExecutionHeight() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState optional = activeState(storage, 1, 1, 100);
			optional.setMiningWindow(((MiningWindowStateImpl) optional.getMiningWindow()).append(FIRST, 19));
			service.setNetworkParams(optional, networkParams(null), BLOCK, ACTION, true);
			assertThat(optional.getMiningWindow().getOrderedValidatorIdentities()).containsExactly(FIRST);

			WorldState same = activeState(storage, 1, 1, 100);
			same.setMiningWindow(((MiningWindowStateImpl) same.getMiningWindow()).append(FIRST, 19));
			service.setNetworkParams(same, networkParams(100L), BLOCK, ACTION, true);
			assertThat(same.getMiningWindow().getOrderedValidatorIdentities()).containsExactly(FIRST);

			WorldState resized = activeState(storage, 1, 1, 100);
			resized.setMiningWindow(((MiningWindowStateImpl) resized.getMiningWindow()).append(FIRST, 19));
			service.setNetworkParams(resized, networkParams(250L), BLOCK, ACTION, true);
			assertThat(resized.getParams().getValidatorMiningWindowBlocks()).isEqualTo(250);
			assertThat(resized.getMiningWindow().getWindowSize()).isEqualTo(250);
			assertThat(resized.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
			assertThat(resized.getMiningWindow().getValidatorBlockCounts()).isEmpty();
			assertThat(resized.getMiningWindow().getLastUpdatedBlockHeight()).isEqualTo(BLOCK.getHeight());
		}
	}

	@Test
	void invalidWindowAndCounterDriftAreRejectedBeforeMutation() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState state = activeState(storage, 1, 1, 100);
			assertThatThrownBy(() -> service.setNetworkParams(state, networkParams(99L), BLOCK, ACTION, true));
			assertThat(state.getParams().getValidatorMiningWindowBlocks()).isEqualTo(100);

			WorldState drifted = activeState(storage, 2, 0, 100);
			assertThatThrownBy(() -> service.addValidator(
					drifted, add(THIRD, MiningLimitMode.UNLIMITED, 0), BLOCK, ACTION, true))
					.hasMessageContaining("requires an unlimited");
			assertThat(drifted.getValidator(THIRD).exists()).isFalse();
		}
	}

	@Test
	void limitedPoliciesAndWindowResizeMustPreserveAtLeastOneAllowedBlock() throws Exception {
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(databaseDirectory)) {
			WorldState addState = activeState(storage, 1, 1, 100);
			addState.addValidator(FIRST, validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0));
			assertThatThrownBy(() -> service.addValidator(
					addState, add(SECOND, MiningLimitMode.LIMITED, 1), BLOCK, ACTION, true))
					.hasMessageContaining("at least one block");
			assertThat(addState.getValidator(SECOND).exists()).isFalse();
			assertThat(addState.getParams().getCurrentValidatorCount()).isEqualTo(1);

			WorldState policyState = activeState(storage, 2, 2, 100);
			policyState.addValidator(FIRST, validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0));
			policyState.addValidator(SECOND, validator(ValidatorStateVersion.V2, MiningLimitMode.UNLIMITED, 0));
			assertThatThrownBy(() -> service.setMiningPolicy(
					policyState, policy(SECOND, MiningLimitMode.LIMITED, 1), BLOCK, ACTION))
					.hasMessageContaining("at least one block");
			assertThat(policyState.getValidator(SECOND).getMiningLimitMode()).isEqualTo(MiningLimitMode.UNLIMITED);

			WorldState resizeState = activeState(storage, 2, 1, 1_000);
			resizeState.addValidator(FIRST, validator(ValidatorStateVersion.V1, MiningLimitMode.UNLIMITED, 0));
			resizeState.addValidator(SECOND, validator(ValidatorStateVersion.V2, MiningLimitMode.LIMITED, 2_000));
			service.setMiningPolicy(resizeState, policy(SECOND, MiningLimitMode.LIMITED, 10), BLOCK, ACTION);
			assertThatThrownBy(() -> service.setNetworkParams(
					resizeState, networkParams(100L), BLOCK, ACTION, true))
					.hasMessageContaining("at least one block");
			assertThat(resizeState.getParams().getValidatorMiningWindowBlocks()).isEqualTo(1_000);
			assertThat(resizeState.getMiningWindow().getWindowSize()).isEqualTo(1_000);
		}
	}

	private WorldState activeState(PersistentWorldStateTestSupport storage, long validatorCount,
			long unlimitedCount, long window) {
		WorldState state = storage.createEmpty(false);
		state.setParams(NetworkParamsStateImpl.builder()
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
				.currentValidatorCount(validatorCount)
				.currentUnlimitedValidatorCount(unlimitedCount)
				.limitedValidatorMiningSharesBps(LongStream.range(0, validatorCount - unlimitedCount)
						.mapToObj(ignored -> 2_000L).toList())
				.validatorMiningWindowBlocks(window)
				.updatedAtBlockHeight(10)
				.updatedAtTimestamp(CREATED)
				.build());
		state.setMiningWindow(MiningWindowStateImpl.empty(window, 10));
		return state;
	}

	private NetworkParamsStateImpl legacyParams(long validatorCount) {
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
				.currentValidatorCount(validatorCount)
				.updatedAtBlockHeight(0)
				.updatedAtTimestamp(CREATED)
				.build();
	}

	private ValidatorStateImpl validator(ValidatorStateVersion version, MiningLimitMode mode, long share) {
		return ValidatorStateImpl.builder()
				.version(version)
				.originTxHash(ORIGIN)
				.createdAtBlockHeight(7)
				.createdAtTimestamp(CREATED)
				.miningLimitMode(version == ValidatorStateVersion.V2 ? mode : null)
				.maxMiningShareBps(version == ValidatorStateVersion.V2 ? share : 0)
				.policyUpdatedByTxHash(version == ValidatorStateVersion.V2 ? ORIGIN : null)
				.policyUpdatedAtBlockHeight(version == ValidatorStateVersion.V2 ? 7 : 0)
				.policyUpdatedAtTimestamp(version == ValidatorStateVersion.V2 ? CREATED : null)
				.build();
	}

	private TxBipValidatorAddPayloadImpl add(Address address, MiningLimitMode mode, long share) {
		return TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.address(address)
				.miningLimitMode(mode)
				.maxMiningShareBps(share)
				.build();
	}

	private TxBipValidatorRemovePayloadImpl remove(Address address) {
		return TxBipValidatorRemovePayloadImpl.builder().address(address).build();
	}

	private TxBipValidatorMiningPolicySetPayloadImpl policy(Address address, MiningLimitMode mode, long share) {
		return TxBipValidatorMiningPolicySetPayloadImpl.builder()
				.validatorAddress(address)
				.miningLimitMode(mode)
				.maxMiningShareBps(share)
				.build();
	}

	private TxBipNetworkParamsSetPayloadImpl networkParams(Long window) {
		return TxBipNetworkParamsSetPayloadImpl.builder()
				.payloadVersion(TxPayloadVersion.V2)
				.validatorMiningWindowBlocks(window)
				.build();
	}
}
