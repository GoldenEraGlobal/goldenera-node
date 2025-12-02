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
package global.goldenera.node.explorer.services.core;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.entities.ExAccountNonce;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@Service
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExAccountStatsCoreService {

	ExAuthorityCoreService authorityCoreService;
	ExTxCoreService txCoreService;
	ExAccountBalanceCoreService accountBalanceCoreService;
	ExAccountNonceCoreService accountNonceCoreService;

	@Transactional(readOnly = true)
	public AccountStats getByAddress(@NonNull Address address) {
		Wei balance = Wei.valueOf(accountBalanceCoreService.findBalanceByAddressAndTokenAddressOptional(address,
				Address.NATIVE_TOKEN).orElse(BigInteger.ZERO));

		Optional<ExAccountNonce> nonceOpt = accountNonceCoreService.getByAddressOptional(address);
		long nonce = nonceOpt.map(ExAccountNonce::getNonce).orElse(0L);
		Instant firstActivity = nonceOpt.map(ExAccountNonce::getCreatedAtTimestamp).orElse(null);
		Instant lastActivity = nonceOpt.map(ExAccountNonce::getUpdatedAtTimestamp).orElse(null);

		boolean isAuthority = authorityCoreService.existsByNodeIdentity(address);

		long totalTransactionsReceived = txCoreService.countByRecipient(address);
		long totalTransactionsSent = txCoreService.countBySender(address);
		long distinctTokenCount = accountBalanceCoreService.countByAddress(address);

		return new AccountStats(
				address,
				balance,
				nonce,
				isAuthority,
				totalTransactionsReceived,
				totalTransactionsSent,
				distinctTokenCount,
				firstActivity,
				lastActivity);
	}

	@Getter
	@Setter
	@AllArgsConstructor
	@NoArgsConstructor
	@ToString
	@FieldDefaults(level = PRIVATE)
	public static class AccountStats {
		Address address;
		Wei balanceInNativeToken;
		long nonce;
		boolean isAuthority;
		long totalTransactionsReceived;

		long totalTransactionsSent;
		long distinctTokenCount;
		Instant firstActivity;
		Instant lastActivity;
	}
}
