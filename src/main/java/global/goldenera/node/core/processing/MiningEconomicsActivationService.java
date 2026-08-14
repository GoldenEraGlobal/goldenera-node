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

import java.util.List;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.MiningConsensusRules;
import global.goldenera.cryptoj.common.MiningWindowStateValidation;
import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.enums.state.MiningWindowStateVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.node.Constants;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;

/** Applies the one-time, branch-specific state transition for the fork. */
@Service
public class MiningEconomicsActivationService {

	public void applyIfNeeded(WorldState worldState, long blockHeight) {
		boolean active = Constants.isForkActive(ForkName.MINING_ECONOMICS, blockHeight);
		if (!active) {
			return;
		}
		NetworkParamsState params = worldState.getParams();
		if (params.getVersion() == NetworkParamsStateVersion.V1) {
			migrate(worldState, params, blockHeight,
					Constants.getSettings().genesisNetworkValidatorMiningWindowBlocks());
			return;
		}
		verifyMigratedState(worldState, params);
	}

	public void assertHeadReady(WorldState worldState, long headHeight) {
		if (!Constants.isForkActive(ForkName.MINING_ECONOMICS, headHeight)) {
			return;
		}
		NetworkParamsState params = worldState.getParams();
		if (params.getVersion() != NetworkParamsStateVersion.V2) {
			throw new GEValidationException("Canonical head is missing the mining economics activation transition");
		}
		verifyMigratedState(worldState, params);
	}

	void applyIfNeeded(WorldState worldState, long blockHeight, boolean forkActive, long configuredWindow) {
		if (!forkActive) {
			return;
		}
		NetworkParamsState params = worldState.getParams();
		if (params.getVersion() == NetworkParamsStateVersion.V1) {
			migrate(worldState, params, blockHeight, configuredWindow);
			return;
		}
		verifyMigratedState(worldState, params);
	}

	private void migrate(WorldState worldState, NetworkParamsState params, long blockHeight, long configuredWindow) {
		MiningConsensusRules.validateWindowSize(configuredWindow);
		NetworkParamsStateImpl migrated = NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.blockReward(params.getBlockReward())
				.blockRewardPoolAddress(params.getBlockRewardPoolAddress())
				.targetMiningTimeMs(params.getTargetMiningTimeMs())
				.asertHalfLifeBlocks(params.getAsertHalfLifeBlocks())
				.asertAnchorHeight(params.getAsertAnchorHeight())
				.minDifficulty(params.getMinDifficulty())
				.minTxBaseFee(params.getMinTxBaseFee())
				.minTxByteFee(params.getMinTxByteFee())
				.updatedByTxHash(params.getUpdatedByTxHash())
				.currentAuthorityCount(params.getCurrentAuthorityCount())
				.currentValidatorCount(params.getCurrentValidatorCount())
				.currentUnlimitedValidatorCount(params.getCurrentValidatorCount())
				.validatorMiningWindowBlocks(configuredWindow)
				.updatedAtBlockHeight(params.getUpdatedAtBlockHeight())
				.updatedAtTimestamp(params.getUpdatedAtTimestamp())
				.build();
		worldState.setParams(migrated);
		worldState.setMiningWindow(MiningWindowStateImpl.empty(configuredWindow, blockHeight));
	}

	private void verifyMigratedState(WorldState worldState, NetworkParamsState params) {
		if (params.getVersion() != NetworkParamsStateVersion.V2) {
			throw new GEValidationException("Unsupported NetworkParamsState version at mining economics fork: "
					+ params.getVersion());
		}
		MiningConsensusRules.validateWindowSize(params.getValidatorMiningWindowBlocks());
		long validatorCount = params.getCurrentValidatorCount();
		long unlimitedCount = params.getCurrentUnlimitedValidatorCount();
		if (validatorCount < 0 || unlimitedCount < 0 || unlimitedCount > validatorCount
				|| (validatorCount > 0 && unlimitedCount == 0)) {
			throw new GEValidationException("Invalid unlimited validator counter in mining economics state");
		}
		List<Long> limitedShares = params.getLimitedValidatorMiningSharesBps();
		if (limitedShares.size() != validatorCount - unlimitedCount) {
			throw new GEValidationException("LIMITED validator policy summary is inconsistent");
		}
		long previousShare = -1;
		for (long share : limitedShares) {
			MiningConsensusRules.validateLimitedPolicyForWindow(
					params.getValidatorMiningWindowBlocks(), share);
			if (share < previousShare) {
				throw new GEValidationException("LIMITED validator policy summary is not canonical");
			}
			previousShare = share;
		}
		MiningWindowState miningWindow = worldState.getMiningWindow();
		MiningWindowStateValidation.validate(miningWindow);
		if (miningWindow.getVersion() != MiningWindowStateVersion.V1
				|| miningWindow.getWindowSize() != params.getValidatorMiningWindowBlocks()) {
			throw new GEValidationException("Mining economics state is missing or inconsistent");
		}
	}
}
