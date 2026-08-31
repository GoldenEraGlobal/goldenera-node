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
package global.goldenera.node.bridge.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.bridge.entities.BridgeDelivery;
import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import global.goldenera.node.bridge.exceptions.BridgeCapabilityException;
import global.goldenera.node.bridge.repositories.BridgeDeliveryRepository;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.utils.PaginationUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BridgeDeliveryService {

    private final ObjectProvider<BridgeDeliveryRepository> repository;

    @Transactional(readOnly = true)
    public Page<BridgeDelivery> getPage(
            int pageNumber, int pageSize, Sort.Direction direction, DeliveryFilter filter, ApiKey apiKey) {
        if (!apiKey.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)) {
            throw new GEValidationException("You do not have permission to audit bridge deliveries");
        }
        return queryPage(pageNumber, pageSize, direction, filter, apiKey.getId());
    }

    @Transactional(readOnly = true)
    public Page<BridgeDelivery> getAdminPage(
            int pageNumber, int pageSize, Sort.Direction direction, DeliveryFilter filter, Long apiKeyId) {
        return queryPage(pageNumber, pageSize, direction, filter, apiKeyId);
    }

    private Page<BridgeDelivery> queryPage(
            int pageNumber, int pageSize, Sort.Direction direction, DeliveryFilter filter, Long apiKeyId) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);
        if (filter.createdFrom() != null && filter.createdTo() != null
                && filter.createdFrom().isAfter(filter.createdTo())) {
            throw new GEValidationException("createdFrom must not be after createdTo");
        }
        BridgeDeliveryRepository deliveries = repository.getIfAvailable();
        if (deliveries == null) {
            throw new BridgeCapabilityException("Bridge delivery audit requires PostgreSQL to be enabled");
        }
        Specification<BridgeDelivery> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("destination").get("type"), WebhookType.BRIDGE));
            if (apiKeyId != null) {
                predicates.add(cb.equal(root.get("destination").get("createdByApiKey").get("id"), apiKeyId));
            }
            if (filter.destinationId() != null) {
                predicates.add(cb.equal(root.get("destination").get("id"), filter.destinationId()));
            }
            if (filter.deliveryId() != null) {
                predicates.add(cb.equal(root.get("deliveryId"), filter.deliveryId()));
            }
            if (filter.eventId() != null) {
                predicates.add(cb.equal(root.get("eventId"), filter.eventId()));
            }
            if (filter.state() != null) {
                predicates.add(cb.equal(root.get("state"), filter.state()));
            }
            if (filter.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdFrom()));
            }
            if (filter.createdTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.createdTo()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return deliveries.findAll(spec, PageRequest.of(pageNumber, pageSize,
                PaginationUtil.stableSort(direction, "createdAt", "id")));
    }

    public record DeliveryFilter(UUID destinationId, UUID deliveryId, UUID eventId,
            BridgeDeliveryState state, Instant createdFrom, Instant createdTo) {
    }
}
