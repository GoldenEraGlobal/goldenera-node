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

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.converters.BridgeNetworkConverter;
import global.goldenera.node.shared.converters.AddressConverter;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "bridge_subscription", indexes = {
        @Index(name = "idx_bridge_subscription_route", columnList = "network,address,enabled"),
        @Index(name = "idx_bridge_subscription_destination", columnList = "destination_id,enabled")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uc_bridge_subscription_destination_network_address",
                columnNames = { "destination_id", "network", "address" })
})
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
@FieldDefaults(level = PRIVATE)
public class BridgeSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", nullable = false, updatable = false)
    UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_id", nullable = false, updatable = false)
    Webhook destination;

    @Column(name = "network", nullable = false, updatable = false)
    @Convert(converter = BridgeNetworkConverter.class)
    Network network;

    @Column(name = "address", nullable = false, updatable = false, columnDefinition = "BYTEA")
    @Convert(converter = AddressConverter.class)
    Address address;

    @Column(name = "enabled", nullable = false)
    boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    public BridgeSubscription(Webhook destination, Network network, Address address) {
        this.destination = destination;
        this.network = network;
        this.address = address;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void initialize() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
