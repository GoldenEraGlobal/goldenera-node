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

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.entities.ExAccountBalance;
import global.goldenera.node.explorer.repositories.ExAccountBalanceRepository;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.utils.PaginationUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Service
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExAccountBalanceCoreService {

	ExAccountBalanceRepository addressBalanceCoreRepository;

	@Transactional(readOnly = true)
	public ExAccountBalance getByAddressAndTokenAddress(@NonNull Address address, @NonNull Address tokenAddress) {
		return getByAddressAndTokenAddressOptional(address, tokenAddress)
				.orElseThrow(() -> new GENotFoundException("Account balance for token address not found"));
	}

	@Transactional(readOnly = true)
	public Optional<ExAccountBalance> getByAddressAndTokenAddressOptional(@NonNull Address address,
			@NonNull Address tokenAddress) {
		return addressBalanceCoreRepository
				.findById(new ExAccountBalance.ExAccountBalancePK(address, tokenAddress));
	}

	@Transactional(readOnly = true)
	public Optional<BigInteger> findBalanceByAddressAndTokenAddressOptional(@NonNull Address address,
			@NonNull Address tokenAddress) {
		return addressBalanceCoreRepository.findBalanceByAddressAndToken(address.toArray(),
				tokenAddress.toArray());
	}

	@Transactional(readOnly = true)
	public Page<ExAccountBalance> getPage(
			int pageNumber,
			int pageSize,
			Sort.Direction direction,
			Address address,
			Address tokenAddress,
			Wei balanceGreaterThan,
			Wei balanceLessThan,
			Long createdAtBlockHeightFrom,
			Long createdAtBlockHeightTo,
			Long updatedAtBlockHeightFrom,
			Long updatedAtBlockHeightTo,
			Instant createdAtTimestampFrom,
			Instant createdAtTimestampTo,
			Instant updatedAtTimestampFrom,
			Instant updatedAtTimestampTo) {
		PaginationUtil.validatePageRequest(pageNumber, pageSize);
		Specification<ExAccountBalance> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (address != null) {
				predicates.add(cb.equal(root.get("address"), address));
			}
			if (tokenAddress != null) {
				predicates.add(cb.equal(root.get("tokenAddress"), tokenAddress));
			}
			if (balanceGreaterThan != null) {
				predicates.add(cb.greaterThan(root.get("balance"), balanceGreaterThan));
			}
			if (balanceLessThan != null) {
				predicates.add(cb.lessThan(root.get("balance"), balanceLessThan));
			}
			if (createdAtBlockHeightFrom != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("createdAtBlockHeight"), createdAtBlockHeightFrom));
			}
			if (createdAtBlockHeightTo != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("createdAtBlockHeight"), createdAtBlockHeightTo));
			}
			if (updatedAtBlockHeightFrom != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("updatedAtBlockHeight"), updatedAtBlockHeightFrom));
			}
			if (updatedAtBlockHeightTo != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("updatedAtBlockHeight"), updatedAtBlockHeightTo));
			}
			if (createdAtTimestampFrom != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("createdAtTimestamp"), createdAtTimestampFrom));
			}
			if (createdAtTimestampTo != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("createdAtTimestamp"), createdAtTimestampTo));
			}
			if (updatedAtTimestampFrom != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("updatedAtTimestamp"), updatedAtTimestampFrom));
			}
			if (updatedAtTimestampTo != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("updatedAtTimestamp"), updatedAtTimestampTo));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
		PageRequest pageable = PageRequest.of(pageNumber, pageSize,
				direction != null ? Sort.by(direction, "balance") : Sort.by("balance"));
		return addressBalanceCoreRepository.findAll(spec, pageable);
	}

	@Transactional(readOnly = true)
	public long countByAddress(@NonNull Address address) {
		return addressBalanceCoreRepository.countByAddress(address);
	}

	@Transactional(readOnly = true)
	public long getCount() {
		return addressBalanceCoreRepository.count();
	}
}
