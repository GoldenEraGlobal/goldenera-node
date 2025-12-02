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
import global.goldenera.node.explorer.entities.ExAddressAlias;
import global.goldenera.node.explorer.repositories.ExAddressAliasRepository;
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
public class ExAddressAliasCoreService {

    ExAddressAliasRepository exAddressAliasRepository;

    @Transactional(readOnly = true)
    public ExAddressAlias getByAlias(@NonNull String alias) {
        return getByAliasOptional(alias)
                .orElseThrow(() -> new GENotFoundException("AddressAlias not found"));
    }

    @Transactional(readOnly = true)
    public Optional<ExAddressAlias> getByAliasOptional(@NonNull String alias) {
        return exAddressAliasRepository.findById(alias);
    }

    @Transactional(readOnly = true)
    public List<ExAddressAlias> getByAddress(@NonNull Address address) {
        return exAddressAliasRepository.findByAddress(address);
    }

    @Transactional(readOnly = true)
    public Page<ExAddressAlias> getPage(
            int pageNumber,
            int pageSize,
            Sort.Direction direction,
            String aliasLike,
            Address address,
            Hash originTxHash,
            Long createdAtBlockHeightFrom,
            Long createdAtBlockHeightTo,
            Instant createdAtTimestampFrom,
            Instant createdAtTimestampTo) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);
        Specification<ExAddressAlias> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (address != null) {
                predicates.add(cb.equal(root.get("address"), address));
            }
            if (originTxHash != null) {
                predicates.add(cb.equal(root.get("originTxHash"), originTxHash));
            }
            if (aliasLike != null && !aliasLike.trim().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("alias")), "%" + aliasLike.toLowerCase() + "%"));
            }
            if (createdAtBlockHeightFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAtBlockHeight"), createdAtBlockHeightFrom));
            }
            if (createdAtBlockHeightTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAtBlockHeight"), createdAtBlockHeightTo));
            }
            if (createdAtTimestampFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAtTimestamp"), createdAtTimestampFrom));
            }
            if (createdAtTimestampTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAtTimestamp"), createdAtTimestampTo));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return exAddressAliasRepository.findAll(spec, PageRequest.of(pageNumber, pageSize,
                direction != null ? Sort.by(direction, "alias") : Sort.by("alias")));
    }

    @Transactional(readOnly = true)
    public long getCount() {
        return exAddressAliasRepository.count();
    }

}
