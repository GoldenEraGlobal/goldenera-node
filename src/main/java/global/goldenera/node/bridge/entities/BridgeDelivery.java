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
package global.goldenera.node.bridge.entities;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;
import java.util.UUID;

import global.goldenera.node.bridge.converters.BridgeDeliveryStateConverter;
import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import global.goldenera.node.shared.entities.Webhook;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "bridge_delivery", indexes = {
        @Index(name = "idx_bridge_delivery_claim", columnList = "state,next_attempt_at,lease_until,id"),
        @Index(name = "idx_bridge_delivery_destination_order", columnList = "destination_id,id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uc_bridge_delivery_delivery_id", columnNames = "delivery_id"),
        @UniqueConstraint(name = "uc_bridge_delivery_destination_event", columnNames = { "destination_id", "event_id" })
})
@Getter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
@FieldDefaults(level = PRIVATE)
public class BridgeDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bridge_delivery_seq_gen")
    @SequenceGenerator(name = "bridge_delivery_seq_gen", sequenceName = "bridge_delivery_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    Long id;

    @Version
    @Column(name = "version", nullable = false)
    Long version;

    @Column(name = "delivery_id", columnDefinition = "uuid", nullable = false, updatable = false)
    UUID deliveryId;

    @Column(name = "event_id", columnDefinition = "uuid", nullable = false, updatable = false)
    UUID eventId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_id", nullable = false, updatable = false)
    Webhook destination;

    @Column(name = "body", columnDefinition = "TEXT")
    String body;

    @Convert(converter = BridgeDeliveryStateConverter.class)
    @Column(name = "state", nullable = false)
    BridgeDeliveryState state;

    @Column(name = "attempts", nullable = false)
    int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    Instant nextAttemptAt;

    @Column(name = "lease_owner", length = 128)
    String leaseOwner;

    @Column(name = "lease_until")
    Instant leaseUntil;

    @Column(name = "last_http_status")
    Integer lastHttpStatus;

    @Column(name = "last_error", length = 2048)
    String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    public BridgeDelivery(UUID eventId, Webhook destination) {
        this.deliveryId = UUID.randomUUID();
        this.eventId = eventId;
        this.destination = destination;
        this.state = BridgeDeliveryState.READY;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
        this.createdAt = this.nextAttemptAt;
        this.updatedAt = this.nextAttemptAt;
    }

    @PrePersist
    void initialize() {
        Instant now = Instant.now();
        if (deliveryId == null) {
            deliveryId = UUID.randomUUID();
        }
        if (state == null) {
            state = BridgeDeliveryState.READY;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    public void setBodyOnce(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Bridge delivery body is required");
        }
        if (this.body != null) {
            throw new IllegalStateException("Bridge delivery body is immutable once set");
        }
        this.body = body;
    }

    public void claim(String owner, Instant until, Instant now) {
        if (owner == null || owner.isBlank() || until == null || now == null || !until.isAfter(now)) {
            throw new IllegalArgumentException("A valid bridge delivery lease is required");
        }
        if (body == null) {
            throw new IllegalStateException("Cannot claim a bridge delivery before its body is stored");
        }
        state = BridgeDeliveryState.IN_FLIGHT;
        attempts++;
        leaseOwner = owner;
        leaseUntil = until;
        updatedAt = now;
    }

    public void markDelivered(Integer httpStatus, Instant now) {
        state = BridgeDeliveryState.DELIVERED;
        lastHttpStatus = httpStatus;
        lastError = null;
        nextAttemptAt = now;
        clearLease(now);
    }

    public void markRetry(Integer httpStatus, String error, Instant retryAt, Instant now) {
        if (retryAt == null || now == null) {
            throw new IllegalArgumentException("Retry timestamps are required");
        }
        state = BridgeDeliveryState.RETRY;
        lastHttpStatus = httpStatus;
        lastError = error;
        nextAttemptAt = retryAt;
        clearLease(now);
    }

    public void markDead(Integer httpStatus, String error, Instant now) {
        state = BridgeDeliveryState.DEAD;
        lastHttpStatus = httpStatus;
        lastError = error;
        nextAttemptAt = now;
        clearLease(now);
    }

    private void clearLease(Instant now) {
        leaseOwner = null;
        leaseUntil = null;
        updatedAt = now;
    }
}
