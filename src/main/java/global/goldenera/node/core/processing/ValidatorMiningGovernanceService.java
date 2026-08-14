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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.MiningConsensusRules;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayload;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;

/** Applies consensus-critical validator policy and counter transitions. */
@Service
public class ValidatorMiningGovernanceService {

	public void addValidator(WorldState state, TxBipValidatorAddPayload payload, SimpleBlock block, Hash bipHash,
			boolean forkActive) {
		checkArgument(!state.getValidator(payload.getAddress()).exists(), "Validator exists");
		NetworkParamsStateImpl oldParams = requireParams(state, forkActive);

		if (!forkActive) {
			ValidatorStateImpl validator = ValidatorStateImpl.builder()
					.version(ValidatorStateVersion.V1)
					.originTxHash(bipHash)
					.createdAtBlockHeight(block.getHeight())
					.createdAtTimestamp(block.getTimestamp())
					.build();
			NetworkParamsStateImpl newParams = oldParams.incrementValidatorCount(
					bipHash, block.getHeight(), block.getTimestamp());
			state.addValidator(payload.getAddress(), validator);
			state.setParams(newParams);
			return;
		}

		MiningConsensusRules.validatePolicy(payload.getMiningLimitMode(), payload.getMaxMiningShareBps());
		validatePolicyForWindow(oldParams, payload.getMiningLimitMode(), payload.getMaxMiningShareBps());
		long validatorCount = Math.addExact(oldParams.getCurrentValidatorCount(), 1);
		long unlimitedCount = oldParams.getCurrentUnlimitedValidatorCount();
		List<Long> limitedShares = new ArrayList<>(oldParams.getLimitedValidatorMiningSharesBps());
		if (payload.getMiningLimitMode() == MiningLimitMode.UNLIMITED) {
			unlimitedCount = Math.addExact(unlimitedCount, 1);
		} else {
			addLimitedShare(limitedShares, payload.getMaxMiningShareBps());
		}
		NetworkParamsStateImpl newParams = updatedCounts(
				oldParams, validatorCount, unlimitedCount, limitedShares, bipHash, block);
		assertInvariant(newParams);

		ValidatorStateImpl validator = ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(bipHash)
				.createdAtBlockHeight(block.getHeight())
				.createdAtTimestamp(block.getTimestamp())
				.miningLimitMode(payload.getMiningLimitMode())
				.maxMiningShareBps(payload.getMaxMiningShareBps())
				.policyUpdatedByTxHash(bipHash)
				.policyUpdatedAtBlockHeight(block.getHeight())
				.policyUpdatedAtTimestamp(block.getTimestamp())
				.build();
		state.addValidator(payload.getAddress(), validator);
		state.setParams(newParams);
	}

	public void removeValidator(WorldState state, TxBipValidatorRemovePayload payload, SimpleBlock block, Hash bipHash,
			boolean forkActive) {
		ValidatorState validator = state.getValidator(payload.getAddress());
		checkArgument(validator.exists(), "Validator missing");
		NetworkParamsStateImpl oldParams = requireParams(state, forkActive);

		if (!forkActive) {
			NetworkParamsStateImpl newParams = oldParams.decrementValidatorCount(
					bipHash, block.getHeight(), block.getTimestamp());
			state.removeValidator(payload.getAddress());
			state.setParams(newParams);
			return;
		}

		MiningLimitMode oldMode = resolveEffectiveMode(validator);
		long validatorCount = Math.subtractExact(oldParams.getCurrentValidatorCount(), 1);
		long unlimitedCount = oldParams.getCurrentUnlimitedValidatorCount();
		List<Long> limitedShares = new ArrayList<>(oldParams.getLimitedValidatorMiningSharesBps());
		if (oldMode == MiningLimitMode.UNLIMITED) {
			checkArgument(validatorCount == 0 || unlimitedCount > 1,
					"Cannot remove the last unlimited validator while validators remain");
			unlimitedCount = Math.subtractExact(unlimitedCount, 1);
		} else {
			removeLimitedShare(limitedShares, validator.getMaxMiningShareBps());
		}
		NetworkParamsStateImpl newParams = updatedCounts(
				oldParams, validatorCount, unlimitedCount, limitedShares, bipHash, block);
		assertInvariant(newParams);

		state.removeValidator(payload.getAddress());
		state.setParams(newParams);
	}

	public void setMiningPolicy(WorldState state, TxBipValidatorMiningPolicySetPayload payload, SimpleBlock block,
			Hash bipHash) {
		ValidatorState oldValidator = state.getValidator(payload.getValidatorAddress());
		checkArgument(oldValidator.exists(), "Validator missing");
		MiningConsensusRules.validatePolicy(payload.getMiningLimitMode(), payload.getMaxMiningShareBps());
		MiningLimitMode oldMode = resolveEffectiveMode(oldValidator);
		NetworkParamsStateImpl oldParams = requireParams(state, true);
		validatePolicyForWindow(oldParams, payload.getMiningLimitMode(), payload.getMaxMiningShareBps());

		long unlimitedCount = oldParams.getCurrentUnlimitedValidatorCount();
		List<Long> limitedShares = new ArrayList<>(oldParams.getLimitedValidatorMiningSharesBps());
		if (oldMode == MiningLimitMode.LIMITED) {
			removeLimitedShare(limitedShares, oldValidator.getMaxMiningShareBps());
		}
		if (payload.getMiningLimitMode() == MiningLimitMode.LIMITED) {
			addLimitedShare(limitedShares, payload.getMaxMiningShareBps());
		}
		boolean categoryChanged = oldMode != payload.getMiningLimitMode();
		if (oldMode == MiningLimitMode.UNLIMITED && payload.getMiningLimitMode() == MiningLimitMode.LIMITED) {
			checkArgument(unlimitedCount > 1, "Cannot limit the last unlimited validator");
			unlimitedCount = Math.subtractExact(unlimitedCount, 1);
		} else if (oldMode == MiningLimitMode.LIMITED
				&& payload.getMiningLimitMode() == MiningLimitMode.UNLIMITED) {
			unlimitedCount = Math.addExact(unlimitedCount, 1);
		}
		NetworkParamsStateImpl newParams = categoryChanged
				? updatedCounts(oldParams, oldParams.getCurrentValidatorCount(), unlimitedCount, limitedShares, bipHash, block)
				: oldParams.toBuilder()
						.clearLimitedValidatorMiningSharesBps()
						.limitedValidatorMiningSharesBps(limitedShares)
						.build();
		assertInvariant(newParams);

		ValidatorStateImpl validator = ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(oldValidator.getOriginTxHash())
				.createdAtBlockHeight(oldValidator.getCreatedAtBlockHeight())
				.createdAtTimestamp(oldValidator.getCreatedAtTimestamp())
				.miningLimitMode(payload.getMiningLimitMode())
				.maxMiningShareBps(payload.getMaxMiningShareBps())
				.policyUpdatedByTxHash(bipHash)
				.policyUpdatedAtBlockHeight(block.getHeight())
				.policyUpdatedAtTimestamp(block.getTimestamp())
				.build();
		state.addValidator(payload.getValidatorAddress(), validator);
		state.setParams(newParams);
	}

	public void setNetworkParams(WorldState state, TxBipNetworkParamsSetPayload payload, SimpleBlock block,
			Hash bipHash, boolean forkActive) {
		checkArgument(!state.isParamsChangedThisBlock(), "Double params change");
		NetworkParamsStateImpl oldParams = requireParams(state, forkActive);
		Long requestedWindow = payload.getValidatorMiningWindowBlocks();
		if (requestedWindow != null) {
			MiningConsensusRules.validateWindowSize(requestedWindow);
			for (long bps : oldParams.getLimitedValidatorMiningSharesBps()) {
				MiningConsensusRules.validateLimitedPolicyForWindow(requestedWindow, bps);
			}
		}

		NetworkParamsStateImpl newParams = oldParams.updateParams(
				payload, bipHash, block.getHeight(), block.getTimestamp());
		if (forkActive) {
			assertInvariant(newParams);
		}

		boolean resetWindow = forkActive && requestedWindow != null
				&& requestedWindow.longValue() != oldParams.getValidatorMiningWindowBlocks();
		state.setParams(newParams);
		if (resetWindow) {
			state.setMiningWindow(MiningWindowStateImpl.empty(requestedWindow, block.getHeight()));
		}
		state.markParamsAsChanged();
	}

	public MiningLimitMode resolveEffectiveMode(ValidatorState validator) {
		checkArgument(validator != null && validator.exists(), "Validator missing");
		return switch (validator.getVersion()) {
			case V1 -> MiningLimitMode.UNLIMITED;
			case V2 -> {
				MiningConsensusRules.validatePolicy(validator.getMiningLimitMode(), validator.getMaxMiningShareBps());
				yield validator.getMiningLimitMode();
			}
		};
	}

	public void assertInvariant(NetworkParamsState params) {
		checkArgument(params.getVersion() == NetworkParamsStateVersion.V2,
				"Mining economics requires NetworkParamsState V2");
		long validatorCount = params.getCurrentValidatorCount();
		long unlimitedCount = params.getCurrentUnlimitedValidatorCount();
		checkArgument(validatorCount >= 0, "Validator count cannot be negative");
		checkArgument(unlimitedCount >= 0 && unlimitedCount <= validatorCount,
				"Unlimited validator counter is inconsistent");
		checkArgument(validatorCount == 0 || unlimitedCount >= 1,
				"A non-empty validator set requires an unlimited validator");
		List<Long> limitedShares = params.getLimitedValidatorMiningSharesBps();
		checkArgument(limitedShares.size() == validatorCount - unlimitedCount,
				"LIMITED validator policy summary is inconsistent");
		long previous = 0;
		for (long bps : limitedShares) {
			MiningConsensusRules.validateLimitedPolicyForWindow(params.getValidatorMiningWindowBlocks(), bps);
			checkArgument(bps >= previous, "LIMITED validator policy summary must be sorted");
			previous = bps;
		}
	}

	private NetworkParamsStateImpl requireParams(WorldState state, boolean forkActive) {
		NetworkParamsState params = state.getParams();
		checkArgument(params instanceof NetworkParamsStateImpl, "Unsupported network params implementation");
		if (forkActive) {
			checkArgument(params.getVersion() == NetworkParamsStateVersion.V2,
					"Mining economics state is not activated");
			assertInvariant(params);
		} else {
			checkArgument(params.getVersion() == NetworkParamsStateVersion.V1,
					"NetworkParamsState V2 is not valid before MINING_ECONOMICS");
		}
		return (NetworkParamsStateImpl) params;
	}

	private NetworkParamsStateImpl updatedCounts(NetworkParamsStateImpl oldParams, long validatorCount,
			long unlimitedCount, List<Long> limitedShares, Hash bipHash, SimpleBlock block) {
		return oldParams.toBuilder()
				.currentValidatorCount(validatorCount)
				.currentUnlimitedValidatorCount(unlimitedCount)
				.clearLimitedValidatorMiningSharesBps()
				.limitedValidatorMiningSharesBps(limitedShares)
				.updatedByTxHash(bipHash)
				.updatedAtBlockHeight(block.getHeight())
				.updatedAtTimestamp(block.getTimestamp())
				.build();
	}

	private void validatePolicyForWindow(NetworkParamsState params, MiningLimitMode mode, long bps) {
		if (mode == MiningLimitMode.LIMITED) {
			MiningConsensusRules.validateLimitedPolicyForWindow(params.getValidatorMiningWindowBlocks(), bps);
		}
	}

	private void addLimitedShare(List<Long> limitedShares, long bps) {
		int index = Collections.binarySearch(limitedShares, bps);
		limitedShares.add(index < 0 ? -index - 1 : index, bps);
	}

	private void removeLimitedShare(List<Long> limitedShares, long bps) {
		int index = Collections.binarySearch(limitedShares, bps);
		checkArgument(index >= 0, "LIMITED validator policy summary is inconsistent");
		limitedShares.remove(index);
	}
}
