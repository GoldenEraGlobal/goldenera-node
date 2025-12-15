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
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Service
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExBlockHeaderCoreService {

    /**
     * Combined SQL query to get all unique affected addresses from both
     * explorer_transfer and explorer_tx tables. Uses UNION ALL with outer DISTINCT
     * for maximum efficiency - each subquery uses covering indexes for index-only
     * scans.
     */
    private static final String AFFECTED_ADDRESSES_BY_HEIGHT_SQL = """
            SELECT DISTINCT addr FROM (
                SELECT v.addr
                FROM explorer_transfer t
                CROSS JOIN LATERAL (VALUES (t.from_address), (t.to_address)) AS v(addr)
                WHERE t.block_height = :blockHeight AND v.addr IS NOT NULL
                UNION ALL
                SELECT v.addr
                FROM explorer_tx tx
                CROSS JOIN LATERAL (VALUES (tx.sender), (tx.recipient)) AS v(addr)
                WHERE tx.block_height = :blockHeight AND v.addr IS NOT NULL
            ) AS combined
            """;

    private static final String AFFECTED_ADDRESSES_BY_HASH_SQL = """
            SELECT DISTINCT addr FROM (
                SELECT v.addr
                FROM explorer_transfer t
                CROSS JOIN LATERAL (VALUES (t.from_address), (t.to_address)) AS v(addr)
                WHERE t.block_hash = :blockHash AND v.addr IS NOT NULL
                UNION ALL
                SELECT v.addr
                FROM explorer_tx tx
                CROSS JOIN LATERAL (VALUES (tx.sender), (tx.recipient)) AS v(addr)
                WHERE tx.block_hash = :blockHash AND v.addr IS NOT NULL
            ) AS combined
            """;

    static final long MAX_BLOCK_HEADER_RANGE = 1000;

    ExBlockHeaderRepository exBlockHeaderRepository;
    EntityManager entityManager;

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
            Address identity,
            Instant timestampFrom,
            Instant timestampTo,
            Integer minNumberOfTxs) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);
        Specification<ExBlockHeader> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (coinbase != null) {
                predicates.add(cb.equal(root.get("coinbase"), coinbase.toArray()));
            }
            if (identity != null) {
                predicates.add(cb.equal(root.get("identity"), identity.toArray()));
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
            Address identity,
            Integer minNumberOfTxs) {
        PaginationUtil.validateRangeRequest(fromHeight, toHeight, MAX_BLOCK_HEADER_RANGE);

        Specification<ExBlockHeader> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.between(root.get("height"), fromHeight, toHeight));

            if (coinbase != null) {
                predicates.add(cb.equal(root.get("coinbase"), coinbase.toArray()));
            }
            if (identity != null) {
                predicates.add(cb.equal(root.get("identity"), identity.toArray()));
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

    // ==================== AFFECTED ADDRESSES ====================

    /**
     * Gets all unique addresses affected by a block (as sender or recipient).
     * Queries both explorer_transfer (for transfers) and explorer_tx (for BIP
     * votes, creates, etc).
     * Ultra-fast implementation using combined UNION query with covering indexes.
     * 
     * @param height
     *            block height
     * @return list of unique affected addresses
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Address> getAffectedAddressesByHeight(long height) {
        List<byte[]> rawAddresses = entityManager.createNativeQuery(AFFECTED_ADDRESSES_BY_HEIGHT_SQL)
                .setParameter("blockHeight", height)
                .getResultList();
        List<Address> addresses = new ArrayList<>(rawAddresses.size());
        for (byte[] raw : rawAddresses) {
            addresses.add(Address.wrap(raw));
        }
        return addresses;
    }

    /**
     * Gets all unique addresses affected by a block (as sender or recipient).
     * Queries both explorer_transfer (for transfers) and explorer_tx (for BIP
     * votes, creates, etc).
     * Direct hash-based query for maximum performance (single DB call).
     * 
     * @param hash
     *            block hash
     * @return list of unique affected addresses
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Address> getAffectedAddressesByHash(@NonNull Hash hash) {
        List<byte[]> rawAddresses = entityManager.createNativeQuery(AFFECTED_ADDRESSES_BY_HASH_SQL)
                .setParameter("blockHash", hash.toArray())
                .getResultList();
        List<Address> addresses = new ArrayList<>(rawAddresses.size());
        for (byte[] raw : rawAddresses) {
            addresses.add(Address.wrap(raw));
        }
        return addresses;
    }
}
