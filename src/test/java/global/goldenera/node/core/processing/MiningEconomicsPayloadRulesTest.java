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

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayloadImpl;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayloadImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.TxPayloadType;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.cryptoj.enums.state.BipType;

class MiningEconomicsPayloadRulesTest {

	private static final Address VALIDATOR = Address.fromHexString(
			"0x0000000000000000000000000000000000000001");

	@Test
	void enforcesPreAndPostForkPayloadMatrix() {
		TxBipValidatorAddPayloadImpl legacyAdd = validatorAdd(TxPayloadVersion.V1, null, null);
		TxBipValidatorAddPayloadImpl newAdd = validatorAdd(TxPayloadVersion.V2, MiningLimitMode.LIMITED, 4000L);
		TxBipNetworkParamsSetPayloadImpl legacyParams = networkParams(TxPayloadVersion.V1, null);
		TxBipNetworkParamsSetPayloadImpl newParams = networkParams(TxPayloadVersion.V2, null);
		TxBipValidatorMiningPolicySetPayloadImpl policy = policy(MiningLimitMode.UNLIMITED, 0);

		assertThatCode(() -> MiningEconomicsPayloadRules.validate(legacyAdd, false)).doesNotThrowAnyException();
		assertThatCode(() -> MiningEconomicsPayloadRules.validate(legacyParams, false)).doesNotThrowAnyException();
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(newAdd, false))
				.hasMessageContaining("not active");
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(newParams, false))
				.hasMessageContaining("not active");
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(policy, false))
				.hasMessageContaining("not active");

		assertThatCode(() -> MiningEconomicsPayloadRules.validate(newAdd, true)).doesNotThrowAnyException();
		assertThatCode(() -> MiningEconomicsPayloadRules.validate(newParams, true)).doesNotThrowAnyException();
		assertThatCode(() -> MiningEconomicsPayloadRules.validate(policy, true)).doesNotThrowAnyException();
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(legacyAdd, true))
				.hasMessageContaining("V2 is required");
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(legacyParams, true))
				.hasMessageContaining("V2 is required");
	}

	@Test
	void validatesCanonicalPoliciesAndWindowBoundaries() {
		assertThatCode(() -> MiningEconomicsPayloadRules.validate(
				validatorAdd(TxPayloadVersion.V2, MiningLimitMode.LIMITED, 1L), true)).doesNotThrowAnyException();
		assertThatCode(() -> MiningEconomicsPayloadRules.validate(
				validatorAdd(TxPayloadVersion.V2, MiningLimitMode.LIMITED, 4000L), true)).doesNotThrowAnyException();
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(
				validatorAdd(TxPayloadVersion.V2, MiningLimitMode.LIMITED, 4001L), true));
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(
				validatorAdd(TxPayloadVersion.V2, MiningLimitMode.UNLIMITED, 1L), true));

		assertThatCode(() -> MiningEconomicsPayloadRules.validate(
				networkParams(TxPayloadVersion.V2, 100L), true)).doesNotThrowAnyException();
		assertThatCode(() -> MiningEconomicsPayloadRules.validate(
				networkParams(TxPayloadVersion.V2, 10_000L), true)).doesNotThrowAnyException();
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(
				networkParams(TxPayloadVersion.V2, 99L), true));
		assertThatThrownBy(() -> MiningEconomicsPayloadRules.validate(
				networkParams(TxPayloadVersion.V2, 10_001L), true));
	}

	@Test
	void mapsNewPayloadToStableBipType() {
		assertThat(TxPayloadType.BIP_VALIDATOR_MINING_POLICY_SET.getCode()).isEqualTo(12);
		assertThat(BipType.fromTxPayloadType(TxPayloadType.BIP_VALIDATOR_MINING_POLICY_SET))
				.isEqualTo(BipType.VALIDATOR_MINING_POLICY_SET);
		assertThat(BipType.VALIDATOR_MINING_POLICY_SET.getCode()).isEqualTo(11);
	}

	private TxBipValidatorAddPayloadImpl validatorAdd(TxPayloadVersion version, MiningLimitMode mode, Long share) {
		return TxBipValidatorAddPayloadImpl.builder()
				.payloadVersion(version)
				.address(VALIDATOR)
				.miningLimitMode(mode)
				.maxMiningShareBps(share)
				.build();
	}

	private TxBipNetworkParamsSetPayloadImpl networkParams(TxPayloadVersion version, Long window) {
		return TxBipNetworkParamsSetPayloadImpl.builder()
				.payloadVersion(version)
				.validatorMiningWindowBlocks(window)
				.build();
	}

	private TxBipValidatorMiningPolicySetPayloadImpl policy(MiningLimitMode mode, long share) {
		return TxBipValidatorMiningPolicySetPayloadImpl.builder()
				.validatorAddress(VALIDATOR)
				.miningLimitMode(mode)
				.maxMiningShareBps(share)
				.build();
	}
}
