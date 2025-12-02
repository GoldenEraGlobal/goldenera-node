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
package global.goldenera.node.core.state.serialization.accountnonce.impl;

import java.time.Instant;

import global.goldenera.node.core.state.serialization.accountnonce.AccountNonceStateDecodingStrategy;
import global.goldenera.node.shared.consensus.state.AccountNonceState;
import global.goldenera.node.shared.consensus.state.impl.AccountNonceStateImpl;
import global.goldenera.node.shared.enums.state.AccountNonceStateVersion;
import global.goldenera.rlp.RLPInput;

public class AccountNonceStateV1DecodingStrategy implements AccountNonceStateDecodingStrategy {

	private static final AccountNonceStateVersion VERSION = AccountNonceStateVersion.V1;

	@Override
	public AccountNonceState decode(RLPInput input) {
		long nonce = input.readLongScalar();
		long updatedAtBlockHeight = input.readLongScalar();
		Long updatedAtTimestampMillis = input.readOptionalLongScalar();
		Instant updatedAtTimestamp = updatedAtTimestampMillis != null
				? Instant.ofEpochMilli(updatedAtTimestampMillis)
				: null;

		return AccountNonceStateImpl.builder()
				.version(VERSION)
				.nonce(nonce)
				.updatedAtBlockHeight(updatedAtBlockHeight)
				.updatedAtTimestamp(updatedAtTimestamp)
				.build();
	}

}
