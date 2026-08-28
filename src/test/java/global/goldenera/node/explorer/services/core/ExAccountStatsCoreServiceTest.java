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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.repositories.ExTxRepository;
import global.goldenera.node.explorer.repositories.ExTxRepository.AccountActivityRange;
import global.goldenera.node.explorer.services.core.ExAccountStatsCoreService.AccountStats;

class ExAccountStatsCoreServiceTest {

	private static final Address ADDRESS =
			Address.fromHexString("0x1111111111111111111111111111111111111111");
	private static final Instant FIRST_ACTIVITY = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant LAST_ACTIVITY = Instant.parse("2026-01-02T00:00:00Z");

	@Test
	void inboundOnlyAccountUsesCanonicalActivityRangeInsteadOfNonceTimestamps() {
		ExAuthorityCoreService authorityService = mock(ExAuthorityCoreService.class);
		ExTxCoreService txService = mock(ExTxCoreService.class);
		ExTxRepository txRepository = mock(ExTxRepository.class);
		ExAccountBalanceCoreService balanceService = mock(ExAccountBalanceCoreService.class);
		ExAccountNonceCoreService nonceService = mock(ExAccountNonceCoreService.class);
		AccountActivityRange activity = mock(AccountActivityRange.class);

		when(balanceService.findBalanceByAddressAndTokenAddressOptional(ADDRESS, Address.NATIVE_TOKEN))
				.thenReturn(Optional.of(BigInteger.TEN));
		when(nonceService.getByAddressOptional(ADDRESS)).thenReturn(Optional.empty());
		when(txRepository.findAccountActivityRange(ADDRESS.toArray())).thenReturn(Optional.of(activity));
		when(activity.getFirstActivity()).thenReturn(FIRST_ACTIVITY);
		when(activity.getLastActivity()).thenReturn(LAST_ACTIVITY);
		when(txService.countByRecipient(ADDRESS)).thenReturn(2L);

		ExAccountStatsCoreService service = new ExAccountStatsCoreService(
				authorityService, txService, txRepository, balanceService, nonceService);

		AccountStats stats = service.getByAddress(ADDRESS);

		assertThat(stats.getNonce()).isZero();
		assertThat(stats.getTotalTransactionsReceived()).isEqualTo(2);
		assertThat(stats.getFirstActivity()).isEqualTo(FIRST_ACTIVITY);
		assertThat(stats.getLastActivity()).isEqualTo(LAST_ACTIVITY);
	}
}
