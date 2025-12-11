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

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.enums.WebhookEventType;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.entities.WebhookEvent;
import global.goldenera.node.shared.events.WebhookEventsUpdateEvent;
import global.goldenera.node.shared.events.WebhookUpdateEvent;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.repositories.WebhookCoreRepository;
import global.goldenera.node.shared.repositories.WebhookEventCoreRepository;
import global.goldenera.node.shared.utils.PaginationUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Service
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WebhookCoreService {

    WebhookCoreRepository webhookCoreRepository;
    WebhookEventCoreRepository webhookEventCoreRepository;

    JdbcTemplate jdbcTemplate;
    ApplicationEventPublisher applicationEventPublisher;

    @Transactional(readOnly = true)
    public Page<Webhook> getPage(
            int pageNumber,
            int pageSize,
            Sort.Direction direction,
            Long createdByApiKeyId,
            String labelLike) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);
        Specification<Webhook> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (createdByApiKeyId != null) {
                predicates.add(cb.equal(root.get("createdByApiKey").get("id"), createdByApiKeyId));
            }

            if (labelLike != null && !labelLike.trim().isBlank()) {
                String pattern = "%" + labelLike.toLowerCase() + "%";
                Predicate labelMatch = cb.like(cb.lower(root.get("label")), pattern);
                predicates.add(labelMatch);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return webhookCoreRepository.findAll(
                spec, PageRequest.of(pageNumber, pageSize,
                        direction != null ? Sort.by(direction, "createdAt") : Sort.by("createdAt")));
    }

    @Transactional(readOnly = true)
    public Page<WebhookEvent> getEventPage(
            int pageNumber,
            int pageSize,
            Sort.Direction direction,
            ApiKey createdByApiKey,
            UUID webhookId,
            WebhookEventType type,
            Address addressFilter,
            Address tokenAddressFilter) {
        PaginationUtil.validatePageRequest(pageNumber, pageSize);
        if (createdByApiKey != null) {
            Webhook webhook = getById(webhookId);
            if (!webhook.getCreatedByApiKey().equals(createdByApiKey)) {
                throw new GENotFoundException("Webhook with id " + webhookId.toString() + " not found");
            }
        }
        Specification<WebhookEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (webhookId != null) {
                predicates.add(cb.equal(root.get("webhook").get("id"), webhookId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (addressFilter != null) {
                predicates.add(cb.equal(root.get("addressFilter"), addressFilter));
            }
            if (tokenAddressFilter != null) {
                predicates.add(cb.equal(root.get("tokenAddressFilter"), tokenAddressFilter));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return webhookEventCoreRepository.findAll(
                spec, PageRequest.of(pageNumber, pageSize,
                        direction != null ? Sort.by(direction, "createdAt") : Sort.by("createdAt")));
    }

    @Transactional(readOnly = true)
    public long getCount() {
        return webhookCoreRepository.count();
    }

    @Transactional(readOnly = true)
    public Webhook getById(@NonNull UUID id) {
        return getByIdOptional(id)
                .orElseThrow(() -> new GENotFoundException("Webhook with id " + id.toString() + " not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Webhook> getByIdOptional(@NonNull UUID id) {
        return webhookCoreRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public WebhookEvent getEventById(@NonNull Long id) {
        return getEventByIdOptional(id)
                .orElseThrow(() -> new GENotFoundException("WebhookEvent with id " + id.toString() + " not found"));
    }

    @Transactional(readOnly = true)
    public Optional<WebhookEvent> getEventByIdOptional(@NonNull Long id) {
        return webhookEventCoreRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Webhook> getAllEnabledWebhooksWithEvents() {
        return webhookCoreRepository.findAllEnabledWithEvents();
    }

    @Transactional(readOnly = true)
    public Optional<Webhook> findWebhookByIdWithEvents(@NonNull UUID id) {
        return webhookCoreRepository.findByIdWithEvents(id);
    }

    @Transactional(readOnly = true)
    public List<Webhook> findEnabledByApiKeyIdWithEvents(@NonNull Long apiKeyId) {
        return webhookCoreRepository.findEnabledByApiKeyIdWithEvents(apiKeyId);
    }

    @Transactional(readOnly = true)
    public boolean existsById(@NonNull UUID id) {
        return webhookCoreRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public long getCountByApiKeyId(@NonNull Long apiKeyId) {
        return webhookCoreRepository.countByApiKeyId(apiKeyId);
    }

    @Transactional(readOnly = true)
    public boolean existsEventById(@NonNull Long id) {
        return webhookEventCoreRepository.existsById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Webhook create(@NonNull Webhook webhook) {
        Webhook createdWebhook = webhookCoreRepository.persist(webhook);
        applicationEventPublisher
                .publishEvent(
                        new WebhookUpdateEvent(this, WebhookUpdateEvent.UpdateType.CREATE_WEBHOOK,
                                createdWebhook.getId(), Optional.of(createdWebhook)));
        return createdWebhook;
    }

    @Transactional(rollbackFor = Exception.class)
    public Webhook update(@NonNull Webhook webhook) {
        Webhook updatedWebhook = webhookCoreRepository.update(webhook);
        applicationEventPublisher
                .publishEvent(
                        new WebhookUpdateEvent(this, WebhookUpdateEvent.UpdateType.UPDATE_WEBHOOK,
                                updatedWebhook.getId(), Optional.of(updatedWebhook)));
        return updatedWebhook;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(@NonNull UUID webhookId) {
        if (!existsById(webhookId)) {
            throw new GENotFoundException("Webhook with id " + webhookId.toString() + " not found");
        }
        webhookCoreRepository.deleteById(webhookId);
        applicationEventPublisher
                .publishEvent(new WebhookUpdateEvent(this, WebhookUpdateEvent.UpdateType.DELETE_WEBHOOK, webhookId,
                        Optional.empty()));
    }

    @Transactional(rollbackFor = Exception.class)
    public int createBulkFilters(Webhook webhook, List<WebhookEventFilter> filtersToCreate) {
        if (filtersToCreate == null || filtersToCreate.isEmpty()) {
            return 0;
        }

        Set<WebhookEventFilter> seenFilters = new HashSet<>(filtersToCreate.size());
        List<Integer> eventTypesList = new ArrayList<>();
        List<byte[]> eventAddressesList = new ArrayList<>();
        List<byte[]> eventTokenAddressesList = new ArrayList<>();

        for (WebhookEventFilter filter : filtersToCreate) {
            if (filter == null) {
                continue;
            }
            if (seenFilters.add(filter)) {
                eventTypesList.add(filter.getType().getCode());
                eventAddressesList.add(filter.getAddressFilter() != null ? filter.getAddressFilter().toArray() : null);
                eventTokenAddressesList
                        .add(filter.getTokenAddressFilter() != null ? filter.getTokenAddressFilter().toArray() : null);
            }
        }

        if (seenFilters.isEmpty()) {
            return 0;
        }

        Integer[] eventTypes = eventTypesList.toArray(new Integer[0]);
        byte[][] eventAddresses = eventAddressesList.toArray(new byte[0][]);
        byte[][] eventTokenAddresses = eventTokenAddressesList.toArray(new byte[0][]);

        String sql = """
                INSERT INTO webhook_event
                    (webhook_id, type, address_filter, token_address_filter, created_at)
                SELECT
                    ? AS webhook_id,
                    d.type,
                    d.address,
                    d.token,
                    NOW() AS created_at
                FROM
                    unnest(
                        ?::integer[],
                        ?::bytea[],
                        ?::bytea[]
                    ) AS d(type, address, token)
                ON CONFLICT (webhook_id, type, address_filter, token_address_filter)
                DO NOTHING;
                """;

        int result = jdbcTemplate.update(connection -> {
            Array typesArray = connection.createArrayOf("integer", eventTypes);
            Array addressesArray = connection.createArrayOf("bytea", eventAddresses);
            Array tokenAddressesArray = connection.createArrayOf("bytea", eventTokenAddresses);

            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setObject(1, webhook.getId());
            ps.setArray(2, typesArray);
            ps.setArray(3, addressesArray);
            ps.setArray(4, tokenAddressesArray);

            return ps;
        });
        applicationEventPublisher
                .publishEvent(new WebhookEventsUpdateEvent(this, WebhookEventsUpdateEvent.UpdateType.ADD_EVENTS,
                        webhook.getId(),
                        webhook, seenFilters.stream().toList()));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteBulkFilters(Webhook webhook, List<WebhookEventFilter> filtersToDelete) {
        if (filtersToDelete == null || filtersToDelete.isEmpty()) {
            return 0;
        }

        Set<WebhookEventFilter> seenFilters = new HashSet<>(filtersToDelete.size());
        List<Integer> eventTypesList = new ArrayList<>();
        List<byte[]> eventAddressesList = new ArrayList<>();
        List<byte[]> eventTokenAddressesList = new ArrayList<>();

        for (WebhookEventFilter filter : filtersToDelete) {
            if (filter == null) {
                continue;
            }
            if (seenFilters.add(filter)) {
                eventTypesList.add(filter.getType().getCode());
                eventAddressesList.add(filter.getAddressFilter() != null ? filter.getAddressFilter().toArray() : null);
                eventTokenAddressesList
                        .add(filter.getTokenAddressFilter() != null ? filter.getTokenAddressFilter().toArray() : null);
            }
        }

        if (seenFilters.isEmpty()) {
            return 0;
        }

        Integer[] eventTypes = eventTypesList.toArray(new Integer[0]);
        byte[][] eventAddresses = eventAddressesList.toArray(new byte[0][]);
        byte[][] eventTokenAddresses = eventTokenAddressesList.toArray(new byte[0][]);

        String sql = """
                DELETE FROM webhook_event f
                USING unnest(
                    ?::integer[],
                    ?::bytea[],
                    ?::bytea[]
                ) AS d(type, address, token_address)
                WHERE
                    f.webhook_id = ?
                    AND f.type = d.type
                    AND f.address_filter IS NOT DISTINCT FROM d.address
                    AND f.token_address_filter IS NOT DISTINCT FROM d.token_address
                """;
        int result = jdbcTemplate.update(connection -> {
            Array typesArray = connection.createArrayOf("integer", eventTypes);
            Array addressesArray = connection.createArrayOf("bytea", eventAddresses);
            Array tokenAddressesArray = connection.createArrayOf("bytea", eventTokenAddresses);

            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setArray(1, typesArray);
            ps.setArray(2, addressesArray);
            ps.setArray(3, tokenAddressesArray);
            ps.setObject(4, webhook.getId());

            return ps;
        });
        applicationEventPublisher
                .publishEvent(new WebhookEventsUpdateEvent(this, WebhookEventsUpdateEvent.UpdateType.REMOVE_EVENTS,
                        webhook.getId(),
                        webhook, seenFilters.stream().toList()));
        return result;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(of = { "type", "addressFilter", "tokenAddressFilter" })
    public static class WebhookEventFilter {
        WebhookEventType type;
        Address addressFilter;
        Address tokenAddressFilter;
    }
}
