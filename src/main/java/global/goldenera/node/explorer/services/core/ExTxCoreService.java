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
import global.goldenera.node.explorer.entities.ExTx;
import global.goldenera.node.explorer.repositories.ExTxRepository;
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
public class ExTxCoreService {

        ExTxRepository exTxRepository;

        @Transactional(readOnly = true)
        public ExTx getByHash(@NonNull Hash hash) {
                return getByHashOptional(hash)
                                .orElseThrow(() -> new GENotFoundException("Tx not found"));
        }

        @Transactional(readOnly = true)
        public Optional<ExTx> getByHashOptional(@NonNull Hash hash) {
                return exTxRepository.findByHash(hash.toArray());
        }

        @Transactional(readOnly = true)
        public Page<ExTx> getPage(
                        int pageNumber,
                        int pageSize,
                        Sort.Direction direction,
                        Address address,
                        Long blockHeight,
                        Instant timestampFrom,
                        Instant timestampTo,
                        TxType type,
                        Hash blockHash,
                        Address sender,
                        Address recipient,
                        Address tokenAddress,
                        Hash referenceHash) {
                PaginationUtil.validatePageRequest(pageNumber, pageSize);
                Specification<ExTx> spec = (root, query, cb) -> {
                        List<Predicate> predicates = new ArrayList<>();

                        if (type != null) {
                                predicates.add(cb.equal(root.get("type"), type));
                        }
                        if (blockHash != null) {
                                predicates.add(cb.equal(root.get("blockHash"), blockHash));
                        }
                        if (sender != null) {
                                predicates.add(cb.equal(root.get("sender"), sender));
                        }
                        if (recipient != null) {
                                predicates.add(cb.equal(root.get("recipient"), recipient));
                        }
                        if (tokenAddress != null) {
                                predicates.add(cb.equal(root.get("tokenAddress"), tokenAddress));
                        }
                        if (referenceHash != null) {
                                predicates.add(cb.equal(root.get("referenceHash"), referenceHash));
                        }
                        if (address != null) {
                                Predicate senderMatch = cb.equal(root.get("sender"), address);
                                Predicate recipientMatch = cb.equal(root.get("recipient"), address);
                                predicates.add(cb.or(senderMatch, recipientMatch));
                        }
                        if (blockHeight != null) {
                                predicates.add(cb.equal(root.get("blockHeight"), blockHeight));
                        }
                        if (timestampFrom != null) {
                                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), timestampFrom));
                        }
                        if (timestampTo != null) {
                                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), timestampTo));
                        }
                        return cb.and(predicates.toArray(new Predicate[0]));
                };
                PageRequest pageable = PageRequest.of(pageNumber, pageSize,
                                direction != null ? Sort.by(direction, "timestamp") : Sort.by("timestamp"));
                return exTxRepository.findAll(spec, pageable);
        }

        @Transactional(readOnly = true)
        public long countByRecipient(@NonNull Address address) {
                return exTxRepository.countByRecipient(address);
        }

        @Transactional(readOnly = true)
        public boolean existsByHash(@NonNull Hash hash) {
                return exTxRepository.existsById(new ExTx.ExTxPK(hash));
        }

        @Transactional(readOnly = true)
        public long countBySender(@NonNull Address address) {
                return exTxRepository.countBySender(address);
        }

        @Transactional(readOnly = true)
        public long getCount() {
                return exTxRepository.count();
        }

        @Transactional(readOnly = true)
        public List<ExTx> getAllByBlockHash(@NonNull Hash blockHash) {
                return exTxRepository.findAllByBlockHash(blockHash.toArray());
        }

        @Transactional(readOnly = true)
        public List<ExTx> getAllByBlockHash(@NonNull Hash blockHash, int startIndex, int count) {
                return exTxRepository.findAllByBlockHash(blockHash.toArray(), startIndex, count);
        }

        @Transactional(readOnly = true)
        public Long getConfirmationsByHash(@NonNull Hash hash) {
                return getConfirmationsByHashOptional(hash)
                                .orElseThrow(() -> new GENotFoundException(
                                                "Tx is not in the canonical chain or not found."));
        }

        @Transactional(readOnly = true)
        public Optional<Long> getConfirmationsByHashOptional(@NonNull Hash hash) {
                Long confirmations = exTxRepository.findConfirmationsByHash(hash.toArray())
                                .orElse(null);

                if (confirmations == null) {
                        return Optional.empty();
                }

                if (confirmations < 0) {
                        return Optional.empty();
                }
                return Optional.of(confirmations);
        }
}
