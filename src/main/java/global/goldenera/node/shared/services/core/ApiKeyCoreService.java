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
package global.goldenera.node.shared.services.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.events.ApiKeyUpdatedEvent;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.repositories.ApiKeyCoreRepository;
import global.goldenera.node.shared.utils.PaginationUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Service
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ApiKeyCoreService {

    ApiKeyCoreRepository apiKeyCoreRepository;
    ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<ApiKey> getPage(
            int pageNumber,
            int pageSize,
            Sort.Direction direction,
            String labelLike,
            Boolean enabled,
            Boolean expired) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);

        Specification<ApiKey> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }

            if (expired != null) {
                Instant now = Instant.now();
                if (expired) {
                    predicates.add(cb.lessThan(root.get("expiresAt"), now));
                } else {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("expiresAt"), now));
                }
            }

            if (labelLike != null && !labelLike.trim().isBlank()) {
                String pattern = "%" + labelLike.toLowerCase() + "%";
                Predicate labelMatch = cb.like(cb.lower(root.get("label")), pattern);
                predicates.add(labelMatch);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(pageNumber, pageSize,
                direction != null ? Sort.by(direction, "createdAt") : Sort.by("createdAt"));
        return apiKeyCoreRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public long getCount() {
        return apiKeyCoreRepository.count();
    }

    @Transactional(readOnly = true)
    public ApiKey getById(@NonNull Long id) {
        return getByIdOptional(id)
                .orElseThrow(() -> new GENotFoundException("ApiKey with id " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> getByIdOptional(@NonNull Long id) {
        return apiKeyCoreRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public ApiKey getByKeyPrefix(
            @NonNull String keyPrefix) {
        return getByKeyPrefixOptional(keyPrefix)
                .orElseThrow(() -> new GENotFoundException("ApiKey with key prefix " + keyPrefix + " not found"));
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> getByKeyPrefixOptional(@NonNull String keyPrefix) {
        return apiKeyCoreRepository.findByKeyPrefix(keyPrefix);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiKey create(@NonNull ApiKey apiKey) {
        ApiKey createdApiKey = apiKeyCoreRepository.persist(apiKey);
        eventPublisher.publishEvent(new ApiKeyUpdatedEvent(this, ApiKeyUpdatedEvent.UpdateType.CREATE_API_KEY,
                apiKey.getId(), Optional.of(createdApiKey)));
        return createdApiKey;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiKey update(@NonNull ApiKey apiKey) {
        ApiKey updatedApiKey = apiKeyCoreRepository.update(apiKey);
        eventPublisher.publishEvent(new ApiKeyUpdatedEvent(this, ApiKeyUpdatedEvent.UpdateType.UPDATE_API_KEY,
                apiKey.getId(), Optional.of(updatedApiKey)));
        return updatedApiKey;
    }

}
