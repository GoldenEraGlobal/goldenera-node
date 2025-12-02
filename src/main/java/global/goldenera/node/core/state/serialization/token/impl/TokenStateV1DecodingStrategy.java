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
package global.goldenera.node.core.state.serialization.token.impl;

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.state.serialization.token.TokenStateDecodingStrategy;
import global.goldenera.node.shared.consensus.state.TokenState;
import global.goldenera.node.shared.consensus.state.impl.TokenStateImpl;
import global.goldenera.node.shared.enums.state.TokenStateVersion;
import global.goldenera.rlp.RLPInput;

public class TokenStateV1DecodingStrategy implements TokenStateDecodingStrategy {

	private static final TokenStateVersion VERSION = TokenStateVersion.V1;

	@Override
	public TokenState decode(RLPInput input) {
		String name = input.readString();
		String smallestUnitName = input.readString();
		int numberOfDecimals = input.readIntScalar();
		String websiteUrl = input.readOptionalString();
		String logoUrl = input.readOptionalString();
		BigInteger maxSupply = input.readOptionalBigIntegerScalar();
		boolean userBurnable = input.readIntScalar() == 1;
		Hash originTxHash = Hash.wrap(input.readBytes32());
		Hash updatedByTxHash = Hash.wrap(input.readBytes32());
		Wei totalSupply = Wei.valueOf(input.readBigIntegerScalar());
		long updatedAtBlockHeight = input.readLongScalar();
		Long updatedAtTimestampMillis = input.readOptionalLongScalar();
		Instant updatedAtTimestamp = updatedAtTimestampMillis != null
				? Instant.ofEpochMilli(updatedAtTimestampMillis)
				: null;

		return TokenStateImpl.builder()
				.version(VERSION)
				.name(name)
				.smallestUnitName(smallestUnitName)
				.numberOfDecimals(numberOfDecimals)
				.websiteUrl(websiteUrl)
				.logoUrl(logoUrl)
				.maxSupply(maxSupply)
				.originTxHash(originTxHash)
				.updatedByTxHash(updatedByTxHash)
				.totalSupply(totalSupply)
				.updatedAtBlockHeight(updatedAtBlockHeight)
				.updatedAtTimestamp(updatedAtTimestamp)
				.userBurnable(userBurnable)
				.build();
	}

}
