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

import static com.google.common.base.Preconditions.checkArgument;

import global.goldenera.cryptoj.common.MiningConsensusRules;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayload;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.node.Constants;
import global.goldenera.node.Constants.ForkName;

/** Consensus fork matrix for versioned mining-economics governance payloads. */
public final class MiningEconomicsPayloadRules {

	private MiningEconomicsPayloadRules() {
	}

	public static void validateAtHeight(TxPayload payload, long blockHeight) {
		validate(payload, Constants.isForkActive(ForkName.MINING_ECONOMICS, blockHeight));
	}

	public static void validate(TxPayload payload, boolean forkActive) {
		checkArgument(payload != null, "BIP payload is required");
		switch (payload.getPayloadType()) {
			case BIP_VALIDATOR_ADD -> validateValidatorAdd((TxBipValidatorAddPayload) payload, forkActive);
			case BIP_NETWORK_PARAMS_SET -> validateNetworkParams((TxBipNetworkParamsSetPayload) payload, forkActive);
			case BIP_VALIDATOR_MINING_POLICY_SET -> validatePolicySet(
					(TxBipValidatorMiningPolicySetPayload) payload, forkActive);
			default -> {
				// Payloads unrelated to this fork retain their existing rules.
			}
		}
	}

	private static void validateValidatorAdd(TxBipValidatorAddPayload payload, boolean forkActive) {
		checkArgument(payload.getAddress() != null, "Validator address is required");
		if (!forkActive) {
			checkArgument(payload.getPayloadVersion() == TxPayloadVersion.V1,
					"ValidatorAdd V2 is not active before MINING_ECONOMICS");
			checkArgument(payload.getMiningLimitMode() == null && payload.getMaxMiningShareBps() == null,
					"Legacy ValidatorAdd cannot contain a mining policy");
			return;
		}

		checkArgument(payload.getPayloadVersion() == TxPayloadVersion.V2,
				"ValidatorAdd V2 is required after MINING_ECONOMICS");
		checkArgument(payload.getMiningLimitMode() != null && payload.getMaxMiningShareBps() != null,
				"ValidatorAdd V2 requires an explicit mining policy");
		MiningConsensusRules.validatePolicy(payload.getMiningLimitMode(), payload.getMaxMiningShareBps());
	}

	private static void validateNetworkParams(TxBipNetworkParamsSetPayload payload, boolean forkActive) {
		if (!forkActive) {
			checkArgument(payload.getPayloadVersion() == TxPayloadVersion.V1,
					"NetworkParamsSet V2 is not active before MINING_ECONOMICS");
			checkArgument(payload.getValidatorMiningWindowBlocks() == null,
					"Legacy NetworkParamsSet cannot contain a mining window");
			return;
		}

		checkArgument(payload.getPayloadVersion() == TxPayloadVersion.V2,
				"NetworkParamsSet V2 is required after MINING_ECONOMICS");
		if (payload.getValidatorMiningWindowBlocks() != null) {
			MiningConsensusRules.validateWindowSize(payload.getValidatorMiningWindowBlocks());
		}
	}

	private static void validatePolicySet(TxBipValidatorMiningPolicySetPayload payload, boolean forkActive) {
		checkArgument(forkActive, "ValidatorMiningPolicySet is not active before MINING_ECONOMICS");
		checkArgument(payload.getPayloadVersion() == TxPayloadVersion.V1,
				"Unsupported ValidatorMiningPolicySet payload version");
		checkArgument(payload.getValidatorAddress() != null, "Validator address is required");
		MiningConsensusRules.validatePolicy(payload.getMiningLimitMode(), payload.getMaxMiningShareBps());
	}
}
