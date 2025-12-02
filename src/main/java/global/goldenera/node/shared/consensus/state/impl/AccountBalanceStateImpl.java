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

import static com.google.common.base.Preconditions.checkArgument;

import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.node.shared.consensus.state.AccountBalanceState;
import global.goldenera.node.shared.enums.state.AccountBalanceStateVersion;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class AccountBalanceStateImpl implements AccountBalanceState {

	public static final AccountBalanceState ZERO = AccountBalanceStateImpl.builder()
			.version(AccountBalanceStateVersion.getLatest())
			.balance(Wei.ZERO)
			.updatedAtBlockHeight(Long.MIN_VALUE)
			.updatedAtTimestamp(Instant.EPOCH)
			.build();

	AccountBalanceStateVersion version;
	Wei balance;
	long updatedAtBlockHeight;
	Instant updatedAtTimestamp;

	public AccountBalanceStateImpl debit(Wei amount, long blockHeight, Instant time) {
		checkArgument(amount.compareTo(Wei.ZERO) >= 0, "Cannot debit negative amount");
		checkArgument(this.balance.compareTo(amount) >= 0, "Insufficient funds");

		return this.toBuilder()
				.balance(this.balance.subtractExact(amount))
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}

	public AccountBalanceStateImpl credit(Wei amount, long blockHeight, Instant time) {
		checkArgument(amount.compareTo(Wei.ZERO) >= 0, "Cannot credit negative amount");

		return this.toBuilder()
				.balance(this.balance.add(amount))
				.updatedAtBlockHeight(blockHeight)
				.updatedAtTimestamp(time)
				.build();
	}
}