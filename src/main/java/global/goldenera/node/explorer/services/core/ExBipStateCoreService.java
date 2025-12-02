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

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.entities.ExBipState;
import global.goldenera.node.explorer.repositories.ExBipStateRepository;
import global.goldenera.node.shared.enums.BipStatus;
import global.goldenera.node.shared.enums.BipType;
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
public class ExBipStateCoreService {

	ExBipStateRepository exBipStateRepository;

	@Transactional(readOnly = true)
	public ExBipState getByBipHash(@NonNull Hash bipHash) {
		return getByBipHashOptional(bipHash)
				.orElseThrow(() -> new GENotFoundException("Bip state with hash not found"));
	}

	@Transactional(readOnly = true)
	public Optional<ExBipState> getByBipHashOptional(@NonNull Hash bipHash) {
		return exBipStateRepository.findById(new ExBipState.BipStatePK(bipHash));
	}

	@Transactional(readOnly = true)
	public boolean existsByBipHash(@NonNull Hash bipHash) {
		return exBipStateRepository.existsById(new ExBipState.BipStatePK(bipHash));
	}

	@Transactional(readOnly = true)
	public long getCount() {
		return exBipStateRepository.count();
	}

	@Transactional(readOnly = true)
	public Page<ExBipState> getPage(
			int pageNumber,
			int pageSize,
			Sort.Direction direction,
			BipType type,
			BipStatus status,
			Boolean isActionExecuted,
			Long createdAtBlockHeightFrom,
			Long createdAtBlockHeightTo,
			Long updatedAtBlockHeightFrom,
			Long updatedAtBlockHeightTo,
			Instant createdAtTimestampFrom,
			Instant createdAtTimestampTo,
			Instant updatedAtTimestampFrom,
			Instant updatedAtTimestampTo) {
		PaginationUtil.validatePageRequest(pageNumber, pageSize);
		Specification<ExBipState> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (type != null) {
				predicates.add(cb.equal(root.get("type"), type));
			}
			if (status != null) {
				if (status == BipStatus.EXPIRED) {
					Instant now = Instant.now();
					predicates.add(cb.equal(root.get("status"), BipStatus.PENDING));
					predicates.add(cb.lessThan(root.get("expirationTimestamp"), now));
				} else if (status == BipStatus.PENDING) {
					Instant now = Instant.now();
					predicates.add(cb.equal(root.get("status"), BipStatus.PENDING));
					predicates.add(cb.greaterThanOrEqualTo(root.get("expirationTimestamp"), now));

				} else {
					predicates.add(cb.equal(root.get("status"), status));
				}
			}
			if (isActionExecuted != null) {
				predicates.add(cb.equal(root.get("isActionExecuted"), isActionExecuted));
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

		return exBipStateRepository
				.findAll(spec, PageRequest.of(pageNumber, pageSize,
						direction != null ? Sort.by(direction, "expirationTimestamp")
								: Sort.by("expirationTimestamp")));
	}
}
