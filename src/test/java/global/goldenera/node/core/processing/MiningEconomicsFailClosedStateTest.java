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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.MiningWindowStateVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.exceptions.CryptoJFailedException;
import global.goldenera.cryptoj.serialization.state.miningwindow.MiningWindowStateDecoder;
import global.goldenera.cryptoj.serialization.state.networkparams.NetworkParamsStateDecoder;
import global.goldenera.cryptoj.serialization.state.networkparams.NetworkParamsStateEncoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateDecoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateEncoder;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.rlp.RLP;

class MiningEconomicsFailClosedStateTest {

	private static final Address VALIDATOR = Address.fromHexString(
			"0x0000000000000000000000000000000000000001");
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void allConsensusStateDecodersRejectUnknownVersions() {
		Bytes miningWindow = RLP.encode(output -> {
			output.startList();
			output.writeIntScalar(99);
			output.writeLongScalar(100);
			output.startList();
			output.endList();
			output.startList();
			output.endList();
			output.writeLongScalar(10);
			output.endList();
		});
		assertThatThrownBy(() -> MiningWindowStateDecoder.INSTANCE.decode(miningWindow))
				.isInstanceOf(CryptoJFailedException.class)
				.hasMessageContaining("Unknown MiningWindowStateVersion code");

		Bytes validator = withUnknownVersion(ValidatorStateEncoder.INSTANCE.encode(validator()));
		assertThatThrownBy(() -> ValidatorStateDecoder.INSTANCE.decode(validator))
				.isInstanceOf(CryptoJFailedException.class)
				.hasMessageContaining("Unknown ValidatorStateVersion code");

		Bytes params = withUnknownVersion(NetworkParamsStateEncoder.INSTANCE.encode(params(2, 1, 100)));
		assertThatThrownBy(() -> NetworkParamsStateDecoder.INSTANCE.decode(params))
				.isInstanceOf(CryptoJFailedException.class)
				.hasMessageContaining("Unknown NetworkParamsStateVersion code");
	}

	@Test
	void miningWindowDecoderRejectsNonCanonicalOrderAndCountDrift() {
		Address second = Address.fromHexString("0x0000000000000000000000000000000000000002");
		Bytes nonCanonical = encodedWindow(List.of(VALIDATOR, second), List.of(second, VALIDATOR), List.of(1L, 1L));
		assertThatThrownBy(() -> MiningWindowStateDecoder.INSTANCE.decode(nonCanonical))
				.isInstanceOf(CryptoJFailedException.class)
				.hasMessageContaining("canonical address order");

		Bytes countDrift = encodedWindow(List.of(VALIDATOR, VALIDATOR), List.of(VALIDATOR), List.of(1L));
		assertThatThrownBy(() -> MiningWindowStateDecoder.INSTANCE.decode(countDrift))
				.isInstanceOf(CryptoJFailedException.class)
				.hasMessageContaining("count map does not match");
	}

	@Test
	void canonicalHeadReadinessRejectsWindowMismatchAndCounterDrift() {
		MiningEconomicsActivationService service = new MiningEconomicsActivationService();
		WorldState mismatch = state(params(2, 1, 250), MiningWindowStateImpl.empty(100, 10));
		assertThatThrownBy(() -> service.applyIfNeeded(mismatch, 11, true, 250))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("missing or inconsistent");

		for (NetworkParamsStateImpl corrupt : List.of(
				params(1, 2, 100), params(2, 0, 100))) {
			WorldState state = state(corrupt, MiningWindowStateImpl.empty(100, 10));
			assertThatThrownBy(() -> service.applyIfNeeded(state, 11, true, 100))
					.isInstanceOf(GEValidationException.class)
					.hasMessageContaining("Invalid unlimited validator counter");
		}
	}

	@Test
	void policyValidationRejectsCorruptInMemoryWindowBeforeEligibilityDecision() {
		MiningWindowStateImpl corrupt = new MiningWindowStateImpl(
				MiningWindowStateVersion.V1, 100, List.of(VALIDATOR, VALIDATOR), Map.of(VALIDATOR, 1L), 12);
		WorldState state = state(params(2, 1, 100), corrupt);
		when(state.getValidator(VALIDATOR)).thenReturn(validator());

		assertThatThrownBy(() -> new ValidatorMiningPolicyService().isCandidateEligible(state, 13, VALIDATOR))
				.isInstanceOf(CryptoJFailedException.class)
				.hasMessageContaining("count map does not match");
	}

	private Bytes encodedWindow(List<Address> identities, List<Address> countOrder, List<Long> counts) {
		return RLP.encode(output -> {
			output.startList();
			output.writeIntScalar(1);
			output.writeLongScalar(100);
			output.startList();
			identities.forEach(output::writeBytes);
			output.endList();
			output.startList();
			for (int index = 0; index < countOrder.size(); index++) {
				output.startList();
				output.writeBytes(countOrder.get(index));
				output.writeLongScalar(counts.get(index));
				output.endList();
			}
			output.endList();
			output.writeLongScalar(12);
			output.endList();
		});
	}

	private Bytes withUnknownVersion(Bytes encoded) {
		byte[] bytes = encoded.toArray();
		int prefix = bytes[0] & 0xff;
		int firstField = prefix <= 0xf7 ? 1 : 1 + (prefix - 0xf7);
		bytes[firstField] = 99;
		return Bytes.wrap(bytes);
	}

	private WorldState state(NetworkParamsStateImpl params, MiningWindowStateImpl window) {
		WorldState state = mock(WorldState.class);
		when(state.getParams()).thenReturn(params);
		when(state.getMiningWindow()).thenReturn(window);
		return state;
	}

	private ValidatorStateImpl validator() {
		return ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(1)
				.createdAtTimestamp(TIME)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(4_000)
				.policyUpdatedByTxHash(Hash.ZERO)
				.policyUpdatedAtBlockHeight(1)
				.policyUpdatedAtTimestamp(TIME)
				.build();
	}

	private NetworkParamsStateImpl params(long validators, long unlimited, long window) {
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
				.currentValidatorCount(validators)
				.currentUnlimitedValidatorCount(unlimited)
				.limitedValidatorMiningSharesBps(Collections.nCopies(
						Math.toIntExact(Math.max(0, validators - unlimited)), 4_000L))
				.validatorMiningWindowBlocks(window)
				.updatedAtBlockHeight(10)
				.updatedAtTimestamp(TIME)
				.build();
	}
}
