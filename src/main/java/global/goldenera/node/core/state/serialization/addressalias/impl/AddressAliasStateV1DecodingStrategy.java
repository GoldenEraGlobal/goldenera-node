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
package global.goldenera.node.core.state.serialization.addressalias.impl;

import java.time.Instant;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.state.serialization.addressalias.AddressAliasStateDecodingStrategy;
import global.goldenera.node.shared.consensus.state.AddressAliasState;
import global.goldenera.node.shared.consensus.state.impl.AddressAliasStateImpl;
import global.goldenera.node.shared.enums.state.AddressAliasStateVersion;
import global.goldenera.rlp.RLPInput;

public class AddressAliasStateV1DecodingStrategy implements AddressAliasStateDecodingStrategy {

	private static final AddressAliasStateVersion VERSION = AddressAliasStateVersion.V1;

	@Override
	public AddressAliasState decode(RLPInput input) {
		Address address = Address.wrap(input.readBytes());
		long createdAtBlockHeight = input.readLongScalar();
		Long createdAtTimestampMillis = input.readOptionalLongScalar();
		Instant createdAtTimestamp = createdAtTimestampMillis != null
				? Instant.ofEpochMilli(createdAtTimestampMillis)
				: null;
		Hash originTxHash = Hash.wrap(input.readBytes32());

		return AddressAliasStateImpl.builder()
				.version(VERSION)
				.address(address)
				.createdAtBlockHeight(createdAtBlockHeight)
				.createdAtTimestamp(createdAtTimestamp)
				.originTxHash(originTxHash)
				.build();
	}

}
