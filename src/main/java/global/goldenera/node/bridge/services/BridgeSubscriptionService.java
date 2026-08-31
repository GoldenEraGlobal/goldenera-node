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

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.entities.BridgeSubscription;
import global.goldenera.node.bridge.enums.BridgeSubscriptionStatus;
import global.goldenera.node.bridge.exceptions.BridgeCapabilityException;
import global.goldenera.node.bridge.repositories.BridgeSubscriptionRepository;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.utils.PaginationUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BridgeSubscriptionService {

    private final ObjectProvider<BridgeSubscriptionRepository> repository;
    private final GeneralProperties generalProperties;

    @Transactional(readOnly = true)
    public Page<BridgeSubscription> getPage(
            int pageNumber, int pageSize, Sort.Direction direction, Address address,
            BridgeSubscriptionStatus status, ApiKey apiKey) {
        if (!apiKey.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)) {
            throw new GEValidationException("You do not have permission to manage bridge subscriptions");
        }
        return queryPage(pageNumber, pageSize, direction, apiKey.getId(), address, status, generalProperties.getNetwork());
    }

    @Transactional(readOnly = true)
    public Page<BridgeSubscription> getAdminPage(
            int pageNumber, int pageSize, Sort.Direction direction, Long apiKeyId, Address address,
            BridgeSubscriptionStatus status) {
        return queryPage(pageNumber, pageSize, direction, apiKeyId, address, status, null);
    }

    private Page<BridgeSubscription> queryPage(
            int pageNumber, int pageSize, Sort.Direction direction, Long apiKeyId, Address address,
            BridgeSubscriptionStatus status, Network network) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);
        BridgeSubscriptionRepository subscriptions = repository.getIfAvailable();
        if (subscriptions == null) {
            throw new BridgeCapabilityException("Bridge subscriptions require PostgreSQL to be enabled");
        }
        Specification<BridgeSubscription> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("destination").get("type"), WebhookType.BRIDGE));
            BridgeSubscriptionStatus effectiveStatus = status == null ? BridgeSubscriptionStatus.ACTIVE : status;
            if (effectiveStatus != BridgeSubscriptionStatus.ALL) {
                predicates.add(cb.equal(root.get("enabled"), effectiveStatus == BridgeSubscriptionStatus.ACTIVE));
            }
            if (apiKeyId != null) {
                predicates.add(cb.equal(root.get("destination").get("createdByApiKey").get("id"), apiKeyId));
            }
            if (address != null) {
                predicates.add(cb.equal(root.get("address"), address));
            }
            if (network != null) {
                predicates.add(cb.equal(root.get("network"), network));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return subscriptions.findAll(spec, PageRequest.of(pageNumber, pageSize,
                PaginationUtil.stableSort(direction, "createdAt", "id")));
    }
}
