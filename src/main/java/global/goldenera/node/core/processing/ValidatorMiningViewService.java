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

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService.EffectiveMiningPolicy;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.enums.MiningPolicySource;
import lombok.RequiredArgsConstructor;

/** Builds informational mining-policy values from the canonical head state. */
@Service
@RequiredArgsConstructor
public class ValidatorMiningViewService {

	private final ValidatorMiningPolicyService policyService;

	public ValidatorMiningView evaluate(WorldState worldState, Address address, ValidatorState validator) {
		MiningPolicySource source = policySource(validator.getVersion());
		EffectiveMiningPolicy policy = policyService.resolveEffectivePolicy(validator);
		NetworkParamsState params = worldState.getParams();
		if (params.getVersion() != NetworkParamsStateVersion.V2) {
			return new ValidatorMiningView(source, policy.mode(), policy.maxMiningShareBps(), null, 0, null,
					policy.mode() == MiningLimitMode.UNLIMITED);
		}

		MiningWindowState window = worldState.getMiningWindow();
		long mined = window.getValidatorBlockCounts().getOrDefault(address, 0L);
		if (policy.mode() == MiningLimitMode.UNLIMITED) {
			return new ValidatorMiningView(source, policy.mode(), policy.maxMiningShareBps(), null, mined, null, true);
		}

		long maxBlocks = policyService.calculateMaxBlocks(
				params.getValidatorMiningWindowBlocks(), policy.maxMiningShareBps());
		long remaining = Math.max(0, maxBlocks - mined);
		long candidateHeight = Math.addExact(window.getLastUpdatedBlockHeight(), 1);
		boolean eligible = policyService.isCandidateEligible(worldState, candidateHeight, address);
		return new ValidatorMiningView(source, policy.mode(), policy.maxMiningShareBps(), maxBlocks, mined,
				remaining, eligible);
	}

	public MiningPolicySource policySource(ValidatorStateVersion version) {
		return switch (version) {
			case V1 -> MiningPolicySource.LEGACY_DEFAULT;
			case V2 -> MiningPolicySource.EXPLICIT;
		};
	}

	public record ValidatorMiningView(
			MiningPolicySource miningPolicySource,
			MiningLimitMode miningLimitMode,
			long maxMiningShareBps,
			Long maxBlocksInCurrentWindow,
			long blocksMinedInCurrentWindow,
			Long remainingBlocksInCurrentWindow,
			boolean miningEligible) {
	}
}
