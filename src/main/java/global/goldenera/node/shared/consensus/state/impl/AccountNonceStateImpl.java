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

import java.time.Instant;

import global.goldenera.node.shared.consensus.state.AccountNonceState;
import global.goldenera.node.shared.enums.state.AccountNonceStateVersion;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class AccountNonceStateImpl implements AccountNonceState {

	public static final AccountNonceState ZERO = AccountNonceStateImpl.builder()
			.version(AccountNonceStateVersion.getLatest())
			.nonce(-1L)
			.updatedAtBlockHeight(Long.MIN_VALUE)
			.updatedAtTimestamp(Instant.EPOCH)
			.build();

	AccountNonceStateVersion version;
	long nonce;
	long updatedAtBlockHeight;
	Instant updatedAtTimestamp;

	public AccountNonceStateImpl increaseNonce(long blockHeight, Instant time) {
		return this.toBuilder()
				.nonce(this.nonce + 1)
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}
}