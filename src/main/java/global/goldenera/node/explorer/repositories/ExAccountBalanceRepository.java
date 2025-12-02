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
package global.goldenera.node.explorer.repositories;

import java.math.BigInteger;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.entities.ExAccountBalance;
import io.hypersistence.utils.spring.repository.BaseJpaRepository;

@Repository
public interface ExAccountBalanceRepository
		extends BaseJpaRepository<ExAccountBalance, ExAccountBalance.ExAccountBalancePK>,
		ListPagingAndSortingRepository<ExAccountBalance, ExAccountBalance.ExAccountBalancePK>,
		JpaSpecificationExecutor<ExAccountBalance> {

	@Query(value = """
			    SELECT balance
			    FROM explorer_account_balance
			    WHERE address = :address
			    AND token_address = :tokenAddress
			    LIMIT 1
			""", nativeQuery = true)
	Optional<BigInteger> findBalanceByAddressAndToken(
			@Param("address") byte[] address,
			@Param("tokenAddress") byte[] tokenAddress);

	long countByAddress(Address address);
}
