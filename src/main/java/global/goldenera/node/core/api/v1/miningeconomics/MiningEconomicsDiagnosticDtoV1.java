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
package global.goldenera.node.core.api.v1.miningeconomics;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.state.MiningWindowStateVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.shared.enums.MiningPolicySource;

/**
 * One read-only, canonical-head-bound view of mining economics state.
 */
public record MiningEconomicsDiagnosticDtoV1(
		Anchor anchor,
		Network network,
		ForkName activeFork,
		long activeForkActivationHeight,
		ActiveConsensusLimits activeConsensusLimits,
		NetworkParams networkParams,
		List<ValidatorPolicy> validatorPolicies,
		MiningWindow miningWindow) {

	public record Anchor(long height, Hash blockHash, Hash stateRoot) {
	}

	public record ActiveConsensusLimits(
			long maxHeaderSizeInBytes,
			long maxTransactionSizeInBytes,
			long maxBlockSizeInBytes,
			long maxTransactionsPerBlock) {
	}

	public record NetworkParams(
			NetworkParamsStateVersion version,
			Wei blockReward,
			Address blockRewardPoolAddress,
			long targetMiningTimeMs,
			long asertHalfLifeBlocks,
			long asertAnchorHeight,
			BigInteger minDifficulty,
			Wei minTxBaseFee,
			Wei minTxByteFee,
			long currentAuthorityCount,
			long currentValidatorCount,
			long currentUnlimitedValidatorCount,
			long validatorMiningWindowBlocks,
			List<Long> limitedValidatorMiningSharesBps,
			Hash updatedByTxHash,
			long updatedAtBlockHeight,
			Instant updatedAtTimestamp,
			CanonicalEncoding canonicalEncoding) {
	}

	public record ValidatorPolicy(
			Address identity,
			ValidatorStateVersion version,
			MiningPolicySource policySource,
			MiningLimitMode mode,
			long maxMiningShareBps,
			Long maxBlocksInCurrentWindow,
			long blocksMinedInCurrentWindow,
			Long remainingBlocksInCurrentWindow,
			boolean miningEligible,
			Hash policyUpdatedByTxHash,
			Long policyUpdatedAtBlockHeight,
			Instant policyUpdatedAtTimestamp,
			CanonicalEncoding canonicalEncoding) {
	}

	public record MiningWindow(
			boolean present,
			MiningWindowStateVersion version,
			long windowSize,
			long entryCount,
			long lastUpdatedBlockHeight,
			List<Address> orderedValidatorIdentities,
			Map<Address, Long> validatorBlockCounts,
			CanonicalEncoding canonicalEncoding) {
	}

	/**
	 * The digest always covers every canonical byte. The bytes themselves are
	 * included only when they fit the diagnostic response bound.
	 */
	public record CanonicalEncoding(
			String digestAlgorithm,
			int byteLength,
			Hash digest,
			String bytesHex) {
	}
}
