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
package global.goldenera.node.core.state.serialization.networkparams.impl;

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.state.serialization.networkparams.NetworkParamsStateDecodingStrategy;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.consensus.state.impl.NetworkParamsStateImpl;
import global.goldenera.node.shared.enums.state.NetworkParamsStateVersion;
import global.goldenera.rlp.RLPInput;

public class NetworkParamsStateV1DecodingStrategy implements NetworkParamsStateDecodingStrategy {

	private static final NetworkParamsStateVersion VERSION = NetworkParamsStateVersion.V1;

	@Override
	public NetworkParamsState decode(RLPInput input) {
		Wei blockReward = Wei.valueOf(input.readBigIntegerScalar());
		Address blockRewardPoolAddress = Address.wrap(input.readBytes());
		long targetMiningTimeMs = input.readLongScalar();
		long asertHalfLifeBlocks = input.readLongScalar();
		long asertAnchorHeight = input.readLongScalar();
		BigInteger minDifficulty = input.readBigIntegerScalar();
		Wei minTxBaseFee = Wei.valueOf(input.readBigIntegerScalar());
		Wei minTxByteFee = Wei.valueOf(input.readBigIntegerScalar());
		Hash updatedByTxHash = Hash.wrap(input.readBytes32());
		long currentAuthorityCount = input.readLongScalar();
		long updatedAtBlockHeight = input.readLongScalar();
		Instant updatedAtTimestamp = Instant.ofEpochMilli(input.readLongScalar());

		return NetworkParamsStateImpl.builder()
				.version(VERSION)
				.blockReward(blockReward)
				.blockRewardPoolAddress(blockRewardPoolAddress)
				.targetMiningTimeMs(targetMiningTimeMs)
				.asertHalfLifeBlocks(asertHalfLifeBlocks)
				.asertAnchorHeight(asertAnchorHeight)
				.minDifficulty(minDifficulty)
				.minTxBaseFee(minTxBaseFee)
				.minTxByteFee(minTxByteFee)
				.updatedByTxHash(updatedByTxHash)
				.currentAuthorityCount(currentAuthorityCount)
				.updatedAtBlockHeight(updatedAtBlockHeight)
				.updatedAtTimestamp(updatedAtTimestamp)
				.build();
	}

}
