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
import global.goldenera.node.explorer.entities.ExBlockHeader;
import global.goldenera.node.explorer.repositories.ExBlockHeaderRepository;
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
public class ExBlockHeaderCoreService {

    ExBlockHeaderRepository exBlockHeaderRepository;

    @Transactional(readOnly = true)
    public ExBlockHeader getByHash(@NonNull Hash hash) {
        return getByHashOptional(hash)
                .orElseThrow(() -> new GENotFoundException("Block with hash not found"));
    }

    @Transactional(readOnly = true)
    public Optional<ExBlockHeader> getByHashOptional(@NonNull Hash hash) {
        return exBlockHeaderRepository.findById(new ExBlockHeader.ExBlockHeaderPK(hash));
    }

    @Transactional(readOnly = true)
    public ExBlockHeader getByHeight(long height) {
        return getByHeightOptional(height)
                .orElseThrow(() -> new GENotFoundException("Block with height not found"));
    }

    @Transactional(readOnly = true)
    public Optional<ExBlockHeader> getByHeightOptional(long height) {
        if (height < 0L) {
            return Optional.empty();
        }
        return exBlockHeaderRepository.findByHeight(height);
    }

    @Transactional(readOnly = true)
    public ExBlockHeader getLatest() {
        return getLatestOptional()
                .orElseThrow(() -> new GENotFoundException("Latest canonical block not found"));
    }

    @Transactional(readOnly = true)
    public Optional<ExBlockHeader> getLatestOptional() {
        return exBlockHeaderRepository.findLatest();
    }

    @Transactional(readOnly = true)
    public Page<ExBlockHeader> getPage(
            int pageNumber,
            int pageSize,
            Sort.Direction direction,
            Address coinbase,
            Instant timestampFrom,
            Instant timestampTo,
            Integer minNumberOfTxs) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);
        Specification<ExBlockHeader> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (coinbase != null) {
                predicates.add(cb.equal(root.get("coinbase"), coinbase.toArray()));
            }
            if (timestampFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), timestampFrom));
            }
            if (timestampTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), timestampTo));
            }
            if (minNumberOfTxs != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("numberOfTxs"), minNumberOfTxs));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(pageNumber, pageSize,
                direction != null ? Sort.by(direction, "height") : Sort.by("height"));
        return exBlockHeaderRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public boolean existsByHash(@NonNull Hash hash) {
        return exBlockHeaderRepository.existsById(new ExBlockHeader.ExBlockHeaderPK(hash));
    }

    @Transactional(readOnly = true)
    public boolean existsByHeight(long height) {
        return exBlockHeaderRepository.existsByHeight(height);
    }

    @Transactional(readOnly = true)
    public long getCumulativeDifficultyByHash(@NonNull Hash hash) {
        return getCumulativeDifficultyByHashOptional(hash)
                .orElseThrow(() -> new GENotFoundException("Block with hash not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Long> getCumulativeDifficultyByHashOptional(@NonNull Hash hash) {
        return exBlockHeaderRepository.findCumulativeDifficultyByHash(hash.toArray());
    }

    @Transactional(readOnly = true)
    public List<ExBlockHeader> findByHeightRange(
            long fromHeight,
            long toHeight,
            Address coinbase,
            Integer minNumberOfTxs) {
        PaginationUtil.validateRangeRequest(fromHeight, toHeight);

        Specification<ExBlockHeader> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.between(root.get("height"), fromHeight, toHeight));

            if (coinbase != null) {
                predicates.add(cb.equal(root.get("coinbase"), coinbase.toArray()));
            }
            if (minNumberOfTxs != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("numberOfTxs"), minNumberOfTxs));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return exBlockHeaderRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "height"));
    }

    @Transactional(readOnly = true)
    public long getCount() {
        return exBlockHeaderRepository.count();
    }
}
