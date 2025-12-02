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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.explorer.entities.ExMemTransfer;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.explorer.repositories.ExMemTransferRepository;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.utils.PaginationUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class ExMemTransferCoreService {

	ExMemTransferRepository exMemTransferRepository;

	@Transactional(readOnly = true)
	public ExMemTransfer getByHash(@NonNull Hash hash) {
		return getByHashOptional(hash)
				.orElseThrow(() -> new GENotFoundException("Mempool Transfer not found"));
	}

	@Transactional(readOnly = true)
	public Optional<ExMemTransfer> getByHashOptional(@NonNull Hash hash) {
		return exMemTransferRepository.findById(new ExMemTransfer.ExMemTransferPK(hash));
	}

	@Transactional(readOnly = true)
	public Page<ExMemTransfer> getPage(
			int pageNumber,
			int pageSize,
			Sort.Direction direction,
			Address address,
			Instant addedAtFrom,
			Instant addedAtTo,
			TransferType transferType,
			TxType txType,
			Address from,
			Address to,
			Address tokenAddress,
			Hash referenceHash) {
		PaginationUtil.validatePageRequest(pageNumber, pageSize);
		Specification<ExMemTransfer> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();

			if (transferType != null) {
				predicates.add(cb.equal(root.get("transferType"), transferType));
			}
			if (txType != null) {
				predicates.add(cb.equal(root.get("txType"), txType));
			}
			if (from != null) {
				predicates.add(cb.equal(root.get("from"), from));
			}
			if (to != null) {
				predicates.add(cb.equal(root.get("to"), to));
			}
			if (tokenAddress != null) {
				predicates.add(cb.equal(root.get("tokenAddress"), tokenAddress));
			}
			if (referenceHash != null) {
				predicates.add(cb.equal(root.get("referenceHash"), referenceHash));
			}
			if (address != null) {
				Predicate fromMatch = cb.equal(root.get("from"), address);
				Predicate toMatch = cb.equal(root.get("to"), address);
				predicates.add(cb.or(fromMatch, toMatch));
			}
			if (addedAtFrom != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("addedAt"), addedAtFrom));
			}
			if (addedAtTo != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("addedAt"), addedAtTo));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
		PageRequest pageable = PageRequest.of(pageNumber, pageSize,
				direction != null ? Sort.by(direction, "addedAt") : Sort.by("addedAt"));
		return exMemTransferRepository.findAll(spec, pageable);
	}

	@Transactional(readOnly = true)
	public long countByTo(@NonNull Address address) {
		return exMemTransferRepository.count((root, query, cb) -> cb.equal(root.get("to"), address));
	}

	@Transactional(readOnly = true)
	public boolean existsByHash(@NonNull Hash hash) {
		return exMemTransferRepository.existsById(new ExMemTransfer.ExMemTransferPK(hash));
	}

	@Transactional(readOnly = true)
	public long countByFrom(@NonNull Address address) {
		return exMemTransferRepository.count((root, query, cb) -> cb.equal(root.get("from"), address));
	}

	@Transactional(readOnly = true)
	public long getCount() {
		return exMemTransferRepository.count();
	}
}
