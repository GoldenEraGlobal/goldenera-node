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
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.entities.ExTransfer;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.explorer.repositories.ExTransferRepository;
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
public class ExTransferCoreService {

        ExTransferRepository exTransferRepository;

        @Transactional(readOnly = true)
        public ExTransfer getById(@NonNull Long id) {
                return exTransferRepository.findById(id)
                                .orElseThrow(() -> new GENotFoundException("Transfer not found"));
        }

        @Transactional(readOnly = true)
        public List<ExTransfer> getByTxHash(@NonNull Hash txHash) {
                return exTransferRepository.findAllByTxHash(txHash.toArray());
        }

        @Transactional(readOnly = true)
        public Page<ExTransfer> getPage(
                        int pageNumber,
                        int pageSize,
                        Sort.Direction direction,
                        Address address,
                        Long blockHeight,
                        Instant timestampFrom,
                        Instant timestampTo,
                        TransferType type,
                        Hash blockHash,
                        Hash txHash,
                        Address from,
                        Address to,
                        Address tokenAddress) {
                PaginationUtil.validatePageRequest(pageNumber, pageSize);
                Specification<ExTransfer> spec = (root, query, cb) -> {
                        List<Predicate> predicates = new ArrayList<>();

                        if (type != null) {
                                predicates.add(cb.equal(root.get("type"), type));
                        }
                        if (blockHash != null) {
                                predicates.add(cb.equal(root.get("blockHash"), blockHash));
                        }
                        if (txHash != null) {
                                predicates.add(cb.equal(root.get("txHash"), txHash));
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
                        if (address != null) {
                                Predicate fromMatch = cb.equal(root.get("from"), address);
                                Predicate toMatch = cb.equal(root.get("to"), address);
                                predicates.add(cb.or(fromMatch, toMatch));
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
                return exTransferRepository.findAll(spec, pageable);
        }

        /**
         * Bulk page query supporting multiple addresses for from, to, tokenAddress
         * filters.
         * Uses IN clause for efficient database queries.
         */
        @Transactional(readOnly = true)
        public Page<ExTransfer> getPageBulk(
                        int pageNumber,
                        int pageSize,
                        Sort.Direction direction,
                        Set<Address> addresses,
                        Long blockHeight,
                        Instant timestampFrom,
                        Instant timestampTo,
                        TransferType type,
                        Hash blockHash,
                        Hash txHash,
                        Set<Address> fromAddresses,
                        Set<Address> toAddresses,
                        Set<Address> tokenAddresses) {
                PaginationUtil.validatePageRequest(pageNumber, pageSize);
                Specification<ExTransfer> spec = (root, query, cb) -> {
                        List<Predicate> predicates = new ArrayList<>();

                        if (type != null) {
                                predicates.add(cb.equal(root.get("type"), type));
                        }
                        if (blockHash != null) {
                                predicates.add(cb.equal(root.get("blockHash"), blockHash));
                        }
                        if (txHash != null) {
                                predicates.add(cb.equal(root.get("txHash"), txHash));
                        }
                        if (fromAddresses != null && !fromAddresses.isEmpty()) {
                                predicates.add(root.get("from").in(fromAddresses));
                        }
                        if (toAddresses != null && !toAddresses.isEmpty()) {
                                predicates.add(root.get("to").in(toAddresses));
                        }
                        if (tokenAddresses != null && !tokenAddresses.isEmpty()) {
                                predicates.add(root.get("tokenAddress").in(tokenAddresses));
                        }
                        if (addresses != null && !addresses.isEmpty()) {
                                Predicate fromMatch = root.get("from").in(addresses);
                                Predicate toMatch = root.get("to").in(addresses);
                                predicates.add(cb.or(fromMatch, toMatch));
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
                return exTransferRepository.findAll(spec, pageable);
        }

        @Transactional(readOnly = true)
        public long countByTo(@NonNull Address address) {
                return exTransferRepository.count((root, query, cb) -> cb.equal(root.get("to"), address));
        }

        @Transactional(readOnly = true)
        public boolean existsById(@NonNull Long id) {
                return exTransferRepository.existsById(id);
        }

        @Transactional(readOnly = true)
        public long countByFrom(@NonNull Address address) {
                return exTransferRepository.count((root, query, cb) -> cb.equal(root.get("from"), address));
        }

        @Transactional(readOnly = true)
        public long getCount() {
                return exTransferRepository.count();
        }

        @Transactional(readOnly = true)
        public List<ExTransfer> getAllByBlockHash(@NonNull Hash blockHash) {
                return exTransferRepository.findAllByBlockHash(blockHash.toArray());
        }
}
