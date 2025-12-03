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
package global.goldenera.node.shared.consensus.state.impl;

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.enums.state.NetworkParamsStateVersion;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class NetworkParamsStateImpl implements NetworkParamsState {

	public static final NetworkParamsState ZERO = NetworkParamsStateImpl.builder()
			.version(NetworkParamsStateVersion.getLatest())
			.blockReward(Wei.ZERO)
			.blockRewardPoolAddress(Address.ZERO)
			.targetMiningTimeMs(0)
			.asertHalfLifeBlocks(0)
			.asertAnchorHeight(0)
			.minDifficulty(BigInteger.ZERO)
			.minTxBaseFee(Wei.ZERO)
			.minTxByteFee(Wei.ZERO)
			.updatedByTxHash(Hash.ZERO)
			.currentAuthorityCount(0)
			.updatedAtBlockHeight(Long.MIN_VALUE)
			.updatedAtTimestamp(Instant.EPOCH)
			.build();

	NetworkParamsStateVersion version;
	Wei blockReward;
	Address blockRewardPoolAddress;
	long targetMiningTimeMs;
	long asertHalfLifeBlocks;
	long asertAnchorHeight;
	BigInteger minDifficulty;
	Wei minTxBaseFee;
	Wei minTxByteFee;

	// Hash of the transaction that LAST MODIFIED these parameters.
	Hash updatedByTxHash;

	long currentAuthorityCount;
	long updatedAtBlockHeight;
	Instant updatedAtTimestamp;

	public NetworkParamsStateImpl incrementAuthorityCount(Hash txHash, long blockHeight, Instant time) {
		return this.toBuilder()
				.currentAuthorityCount(this.currentAuthorityCount + 1)
				.updatedByTxHash(txHash)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}

	public NetworkParamsStateImpl decrementAuthorityCount(Hash txHash, long blockHeight, Instant time) {
		if (this.currentAuthorityCount <= 1) {
			throw new IllegalStateException("Cannot remove last authority");
		}
		return this.toBuilder()
				.currentAuthorityCount(this.currentAuthorityCount - 1)
				.updatedByTxHash(txHash)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}

	public NetworkParamsStateImpl updateParams(TxBipNetworkParamsSetPayload p, Hash txHash, long blockHeight,
			Instant time) {
		boolean changeAsertAnchorHeight = p.getAsertHalfLifeBlocks() != null || p.getTargetMiningTimeMs() != null;

		return this.toBuilder()
				.blockReward(p.getBlockReward() != null ? p.getBlockReward() : this.blockReward)
				.blockRewardPoolAddress(p.getBlockRewardPoolAddress() != null ? p.getBlockRewardPoolAddress()
						: this.blockRewardPoolAddress)
				.targetMiningTimeMs(
						p.getTargetMiningTimeMs() != null ? p.getTargetMiningTimeMs() : this.targetMiningTimeMs)
				.asertHalfLifeBlocks(
						p.getAsertHalfLifeBlocks() != null ? p.getAsertHalfLifeBlocks() : this.asertHalfLifeBlocks)
				.asertAnchorHeight(changeAsertAnchorHeight ? blockHeight : this.asertAnchorHeight)
				.minDifficulty(p.getMinDifficulty() != null ? p.getMinDifficulty() : this.minDifficulty)
				.minTxBaseFee(p.getMinTxBaseFee() != null ? p.getMinTxBaseFee() : this.minTxBaseFee)
				.minTxByteFee(p.getMinTxByteFee() != null ? p.getMinTxByteFee() : this.minTxByteFee)
				.updatedByTxHash(txHash)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}
}