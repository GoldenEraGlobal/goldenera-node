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

import java.math.BigInteger;

import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;

import global.goldenera.cryptoj.common.MiningConsensusRules;
import global.goldenera.cryptoj.common.MiningWindowStateValidation;
import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.MiningWindowStateVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.Constants;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Consensus mining-share validation and rolling-window transitions. */
@Service
public class ValidatorMiningPolicyService {
	private final Counter shareLimitRejections;

	public ValidatorMiningPolicyService() {
		this.shareLimitRejections = null;
	}

	@Autowired
	public ValidatorMiningPolicyService(MeterRegistry registry) {
		this.shareLimitRejections = registry.counter("blockchain.mining.share_limit.rejections");
	}

	public void validateCandidate(WorldState parentState, long candidateHeight, Address minerIdentity) {
		if (!tracksCandidate(candidateHeight)) {
			return;
		}
		validateCandidate(parentState, minerIdentity, candidateHeight);
	}

	public boolean isCandidateEligible(WorldState parentState, long candidateHeight, Address minerIdentity) {
		if (!tracksCandidate(candidateHeight)) {
			return true;
		}
		return evaluateCandidate(parentState, minerIdentity, candidateHeight).eligible();
	}

	void validateCandidate(WorldState parentState, Address minerIdentity, long candidateHeight) {
		CandidateEvaluation evaluation = evaluateCandidate(parentState, minerIdentity, candidateHeight);
		if (!evaluation.eligible() && shareLimitRejections != null) {
			shareLimitRejections.increment();
		}
		checkArgument(evaluation.eligible(),
				"Mining share exceeded for %s: candidate count %s exceeds maximum %s",
				minerIdentity.toChecksumAddress(), evaluation.candidateCount(), evaluation.maxBlocks());
	}

	private CandidateEvaluation evaluateCandidate(WorldState parentState, Address minerIdentity,
			long candidateHeight) {
		checkArgument(minerIdentity != null && !minerIdentity.equals(Address.ZERO),
				"Miner identity cannot be null or zero");
		NetworkParamsState params = parentState.getParams();
		checkArgument(params.getVersion() == NetworkParamsStateVersion.V2,
				"Mining economics requires NetworkParamsState V2 in parent state");
		MiningConsensusRules.validateWindowSize(params.getValidatorMiningWindowBlocks());
		MiningWindowState parentWindow = parentState.getMiningWindow();
		checkArgument(parentWindow.getWindowSize() == params.getValidatorMiningWindowBlocks(),
				"Mining window size does not match parent network params");
		MiningWindowStateValidation.validate(parentWindow);

		ValidatorState validator = parentState.getValidator(minerIdentity);
		checkArgument(validator.exists(), "Miner is not an active validator: %s", minerIdentity.toChecksumAddress());
		EffectiveMiningPolicy policy = resolveEffectivePolicy(validator);
		MiningWindowState candidateWindow = appendCandidate(parentWindow, minerIdentity, candidateHeight);
		if (policy.mode() == MiningLimitMode.UNLIMITED) {
			return CandidateEvaluation.eligibleWithoutQuota();
		}

		long maxBlocks = calculateMaxBlocks(
				params.getValidatorMiningWindowBlocks(), policy.maxMiningShareBps());
		checkArgument(maxBlocks >= 1,
				"LIMITED mining policy must allow at least one block in the configured window");
		long candidateCount = candidateWindow.getValidatorBlockCounts().getOrDefault(minerIdentity, 0L);
		return new CandidateEvaluation(candidateCount <= maxBlocks, candidateCount, maxBlocks);
	}

	public void appendAcceptedBlock(WorldState worldState, SimpleBlock block) {
		appendAcceptedBlock(worldState, block,
				Constants.isForkActive(ForkName.MINING_ECONOMICS, block.getHeight()));
	}

	void appendAcceptedBlock(WorldState worldState, SimpleBlock block, boolean forkActive) {
		if (block.getHeight() == 0 || !forkActive) {
			return;
		}
		MiningWindowState window = worldState.getMiningWindow();
		if (isEmptyTransitionAtHeight(window, block.getHeight())) {
			return;
		}
		checkArgument(block.getIdentity() != null && !block.getIdentity().equals(Address.ZERO),
				"Accepted post-fork block requires a non-zero miner identity");
		worldState.setMiningWindow(appendCandidate(window, block.getIdentity(), block.getHeight()));
	}

	MiningWindowStateImpl appendCandidate(MiningWindowState window, Address minerIdentity, long candidateHeight) {
		MiningWindowStateValidation.validate(window);
		checkArgument(window.getVersion() == MiningWindowStateVersion.V1,
				"Unsupported MiningWindowState version: %s", window.getVersion());
		return new MiningWindowStateImpl(
				window.getVersion(),
				window.getWindowSize(),
				window.getOrderedValidatorIdentities(),
				window.getValidatorBlockCounts(),
				window.getLastUpdatedBlockHeight())
				.append(minerIdentity, candidateHeight);
	}

	public EffectiveMiningPolicy resolveEffectivePolicy(ValidatorState validator) {
		checkArgument(validator != null && validator.exists(), "Validator missing");
		ValidatorStateVersion version = validator.getVersion();
		checkArgument(version != null, "ValidatorState version cannot be null");
		return switch (version) {
			case V1 -> new EffectiveMiningPolicy(MiningLimitMode.UNLIMITED, 0);
			case V2 -> {
				MiningConsensusRules.validatePolicy(
						validator.getMiningLimitMode(), validator.getMaxMiningShareBps());
				yield new EffectiveMiningPolicy(
						validator.getMiningLimitMode(), validator.getMaxMiningShareBps());
			}
		};
	}

	public long calculateMaxBlocks(long configuredWindowSize, long maxMiningShareBps) {
		checkArgument(configuredWindowSize >= 0, "Window size cannot be negative");
		checkArgument(maxMiningShareBps >= 0, "Mining share cannot be negative");
		return BigInteger.valueOf(configuredWindowSize)
				.multiply(BigInteger.valueOf(maxMiningShareBps))
				.divide(BigInteger.valueOf(MiningConsensusRules.BASIS_POINTS_DENOMINATOR))
				.longValueExact();
	}

	boolean tracksCandidate(long candidateHeight) {
		Long activationHeight = Constants.getSettings().forkActivationBlocks().get(ForkName.MINING_ECONOMICS);
		return activationHeight != null && candidateHeight > activationHeight;
	}

	private boolean isEmptyTransitionAtHeight(MiningWindowState window, long blockHeight) {
		return window.getLastUpdatedBlockHeight() == blockHeight
				&& window.getOrderedValidatorIdentities().isEmpty()
				&& window.getValidatorBlockCounts().isEmpty();
	}

	public record EffectiveMiningPolicy(MiningLimitMode mode, long maxMiningShareBps) {
	}

	private record CandidateEvaluation(boolean eligible, long candidateCount, long maxBlocks) {
		private static CandidateEvaluation eligibleWithoutQuota() {
			return new CandidateEvaluation(true, 0, 0);
		}
	}
}
