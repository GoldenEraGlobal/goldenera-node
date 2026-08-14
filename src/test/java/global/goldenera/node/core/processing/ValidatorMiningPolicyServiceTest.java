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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ValidatorMiningPolicyServiceTest {

	private static final Address VALIDATOR = address(1);
	private static final Address OTHER = address(2);
	private static final Address BENEFICIARY = address(99);
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
	private final ValidatorMiningPolicyService service = new ValidatorMiningPolicyService();

	@Test
	void quotaUsesFloorAndBigIntegerWithoutIntermediateOverflow() {
		assertThat(service.calculateMaxBlocks(100, 3_333)).isEqualTo(33);
		long expected = BigInteger.valueOf(Long.MAX_VALUE)
				.multiply(BigInteger.valueOf(4_000))
				.divide(BigInteger.valueOf(10_000))
				.longValueExact();
		assertThat(service.calculateMaxBlocks(Long.MAX_VALUE, 4_000)).isEqualTo(expected);
		assertThatThrownBy(() -> service.calculateMaxBlocks(Long.MAX_VALUE, Long.MAX_VALUE))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void limitedCandidateExactlyAtCapIsValidAndFirstAboveCapIsInvalidInIncompleteWindow() {
		WorldState atBoundary = parent(limited(4_000), windowWith(VALIDATOR, 39), 100);
		WorldState aboveBoundary = parent(limited(4_000), windowWith(VALIDATOR, 40), 100);

		assertThatCode(() -> service.validateCandidate(atBoundary, VALIDATOR, 50))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> service.validateCandidate(aboveBoundary, VALIDATOR, 51))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidate count 41 exceeds maximum 40");
		assertThat(service.isCandidateEligible(aboveBoundary, 51, VALIDATOR)).isFalse();
	}

	@Test
	void rejectedCandidateIncrementsLowCardinalityMetric() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ValidatorMiningPolicyService meteredService = new ValidatorMiningPolicyService(registry);
		WorldState aboveBoundary = parent(limited(4_000), windowWith(VALIDATOR, 40), 100);

		assertThatThrownBy(() -> meteredService.validateCandidate(aboveBoundary, VALIDATOR, 51))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(registry.counter("blockchain.mining.share_limit.rejections").count()).isEqualTo(1);
	}

	@Test
	void limitedPolicyMustAllowAtLeastOneBlockInConfiguredWindow() {
		WorldState state = parent(limited(1), MiningWindowStateImpl.empty(100, 10), 100);

		assertThatThrownBy(() -> service.validateCandidate(state, VALIDATOR, 11))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least one block");
	}

	@Test
	void unlimitedAndLegacyV1SkipOnlyShareCheck() {
		MiningWindowState fullHistory = windowWith(VALIDATOR, 100);
		assertThatCode(() -> service.validateCandidate(parent(explicitUnlimited(), fullHistory, 100), VALIDATOR, 101))
				.doesNotThrowAnyException();
		assertThatCode(() -> service.validateCandidate(parent(legacy(), fullHistory, 100), VALIDATOR, 101))
				.doesNotThrowAnyException();
		assertThat(service.resolveEffectivePolicy(legacy()).mode()).isEqualTo(MiningLimitMode.UNLIMITED);
		assertThat(service.resolveEffectivePolicy(legacy()).maxMiningShareBps()).isZero();
	}

	@Test
	void policyChangedInBlockHOnlyAffectsCandidateHPlusOne() {
		MiningWindowState parentWindow = windowWith(VALIDATOR, 40);
		WorldState parentHMinusOne = parent(legacy(), parentWindow, 100);
		assertThatCode(() -> service.validateCandidate(parentHMinusOne, VALIDATOR, 51))
				.doesNotThrowAnyException();

		MiningWindowState stateAtH = service.appendCandidate(parentWindow, VALIDATOR, 51);
		WorldState parentH = parent(limited(4_000), stateAtH, 100);
		assertThatThrownBy(() -> service.validateCandidate(parentH, VALIDATOR, 52))
				.hasMessageContaining("candidate count 42 exceeds maximum 40");
	}

	@Test
	void unknownValidatorVersionFailsClosed() {
		ValidatorState unknown = mock(ValidatorState.class);
		when(unknown.exists()).thenReturn(true);
		when(unknown.getVersion()).thenReturn(null);

		assertThatThrownBy(() -> service.resolveEffectivePolicy(unknown))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("version cannot be null");
	}

	@Test
	void nonCanonicalExplicitPolicyFailsClosed() {
		WorldState state = parent(
				validator(ValidatorStateVersion.V2, MiningLimitMode.UNLIMITED, 1),
				MiningWindowStateImpl.empty(100, 10), 100);

		assertThatThrownBy(() -> service.validateCandidate(state, VALIDATOR, 11))
				.hasMessageContaining("UNLIMITED mining policy requires maxMiningShareBps = 0");
	}

	@Test
	void activeForkRequiresIdentityToRemainAnActiveValidator() {
		WorldState state = parent(explicitUnlimited(), MiningWindowStateImpl.empty(100, 10), 100);
		when(state.getValidator(VALIDATOR)).thenReturn(ValidatorStateImpl.ZERO);

		assertThatThrownBy(() -> service.validateCandidate(state, VALIDATOR, 11))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not an active validator");
	}

	@Test
	void windowTracksIdentityNotCoinbaseAndRemoveReaddDoesNotEraseHistory() {
		WorldState readded = parent(limited(4_000), windowWith(VALIDATOR, 39), 100);
		SimpleBlock block = SimpleBlock.builder()
				.height(20)
				.timestamp(TIME)
				.identity(VALIDATOR)
				.coinbase(BENEFICIARY)
				.build();

		service.appendAcceptedBlock(readded, block, true);

		assertThat(readded.getMiningWindow().getValidatorBlockCounts().get(VALIDATOR)).isEqualTo(40);
		assertThat(readded.getMiningWindow().getValidatorBlockCounts()).doesNotContainKey(BENEFICIARY);
		assertThatThrownBy(() -> service.validateCandidate(readded, VALIDATOR, 21))
				.hasMessageContaining("candidate count 41");
	}

	@Test
	void unlimitedAppendEvictsOldestAndKeepsCountsCanonical() {
		MiningWindowState window = MiningWindowStateImpl.empty(100, 10);
		for (int i = 0; i < 99; i++) {
			window = ((MiningWindowStateImpl) window).append(OTHER, 11 + i);
		}
		window = ((MiningWindowStateImpl) window).append(VALIDATOR, 110);
		WorldState state = parent(explicitUnlimited(), window, 100);

		service.appendAcceptedBlock(state, block(111, VALIDATOR), true);

		assertThat(state.getMiningWindow().getOrderedValidatorIdentities()).hasSize(100);
		assertThat(state.getMiningWindow().getOrderedValidatorIdentities().getFirst()).isEqualTo(OTHER);
		assertThat(state.getMiningWindow().getValidatorBlockCounts().get(OTHER)).isEqualTo(98);
		assertThat(state.getMiningWindow().getValidatorBlockCounts().get(VALIDATOR)).isEqualTo(2);
	}

	@Test
	void activationAndResizeBlocksKeepEmptyWindowAndFollowingBlockIsFirstEntry() {
		WorldState activation = parent(explicitUnlimited(), MiningWindowStateImpl.empty(100, 10), 100);
		service.appendAcceptedBlock(activation, block(10, VALIDATOR), true);
		assertThat(activation.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
		service.appendAcceptedBlock(activation, block(11, VALIDATOR), true);
		assertThat(activation.getMiningWindow().getOrderedValidatorIdentities()).containsExactly(VALIDATOR);

		WorldState resized = parent(explicitUnlimited(), MiningWindowStateImpl.empty(250, 20), 250);
		service.appendAcceptedBlock(resized, block(20, VALIDATOR), true);
		assertThat(resized.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
		service.appendAcceptedBlock(resized, block(21, VALIDATOR), true);
		assertThat(resized.getMiningWindow().getOrderedValidatorIdentities()).containsExactly(VALIDATOR);
	}

	@Test
	void preForkAndGenesisDoNotChangeWindow() {
		WorldState state = parent(explicitUnlimited(), MiningWindowStateImpl.empty(100, 0), 100);
		service.appendAcceptedBlock(state, block(0, VALIDATOR), true);
		service.appendAcceptedBlock(state, block(9, VALIDATOR), false);
		assertThat(state.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
	}

	private WorldState parent(ValidatorState validator, MiningWindowState window, long windowSize) {
		WorldState state = mock(WorldState.class);
		when(state.getParams()).thenReturn(params(windowSize));
		when(state.getValidator(VALIDATOR)).thenReturn(validator);
		when(state.getMiningWindow()).thenReturn(window);
		doAnswer(invocation -> {
			when(state.getMiningWindow()).thenReturn(invocation.getArgument(0));
			return null;
		}).when(state).setMiningWindow(any());
		return state;
	}

	private NetworkParamsStateImpl params(long windowSize) {
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
				.currentValidatorCount(1)
				.currentUnlimitedValidatorCount(1)
				.validatorMiningWindowBlocks(windowSize)
				.updatedAtBlockHeight(10)
				.updatedAtTimestamp(TIME)
				.build();
	}

	private ValidatorStateImpl limited(long bps) {
		return validator(ValidatorStateVersion.V2, MiningLimitMode.LIMITED, bps);
	}

	private ValidatorStateImpl explicitUnlimited() {
		return validator(ValidatorStateVersion.V2, MiningLimitMode.UNLIMITED, 0);
	}

	private ValidatorStateImpl legacy() {
		return validator(ValidatorStateVersion.V1, null, 0);
	}

	private ValidatorStateImpl validator(ValidatorStateVersion version, MiningLimitMode mode, long bps) {
		return ValidatorStateImpl.builder()
				.version(version)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(0)
				.createdAtTimestamp(TIME)
				.miningLimitMode(mode)
				.maxMiningShareBps(bps)
				.policyUpdatedByTxHash(version == ValidatorStateVersion.V2 ? Hash.ZERO : null)
				.policyUpdatedAtBlockHeight(version == ValidatorStateVersion.V2 ? 0 : Long.MIN_VALUE)
				.policyUpdatedAtTimestamp(version == ValidatorStateVersion.V2 ? TIME : null)
				.build();
	}

	private MiningWindowState windowWith(Address identity, int count) {
		MiningWindowState window = MiningWindowStateImpl.empty(100, 10);
		for (int i = 0; i < count; i++) {
			window = ((MiningWindowStateImpl) window).append(identity, 11 + i);
		}
		return window;
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
}
