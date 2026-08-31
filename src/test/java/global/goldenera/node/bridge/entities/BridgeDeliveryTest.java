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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import global.goldenera.node.shared.entities.Webhook;

class BridgeDeliveryTest {

    @Test
    void bodyCanOnlyBeStoredOnce() {
        BridgeDelivery delivery = new BridgeDelivery(UUID.randomUUID(), new Webhook());

        delivery.setBodyOnce("{\"event\":true}");

        assertThat(delivery.getBody()).isEqualTo("{\"event\":true}");
        assertThatThrownBy(() -> delivery.setBodyOnce("replacement"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimUsesOneBasedAttemptsAndRetryReleasesLease() {
        Instant now = Instant.parse("2026-08-16T12:00:00Z");
        Instant leaseUntil = now.plusSeconds(30);
        Instant retryAt = now.plusSeconds(60);
        BridgeDelivery delivery = new BridgeDelivery(UUID.randomUUID(), new Webhook());
        delivery.setBodyOnce("{}");

        delivery.claim("worker-1", leaseUntil, now);

        assertThat(delivery.getState()).isEqualTo(BridgeDeliveryState.IN_FLIGHT);
        assertThat(delivery.getAttempts()).isOne();
        assertThat(delivery.getLeaseOwner()).isEqualTo("worker-1");
        assertThat(delivery.getLeaseUntil()).isEqualTo(leaseUntil);

        delivery.markRetry(503, "unavailable", retryAt, now.plusSeconds(1));

        assertThat(delivery.getState()).isEqualTo(BridgeDeliveryState.RETRY);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(delivery.getLeaseOwner()).isNull();
        assertThat(delivery.getLeaseUntil()).isNull();
    }

    @Test
    void deliveryCannotBeClaimedUntilImmutableBodyExists() {
        BridgeDelivery delivery = new BridgeDelivery(UUID.randomUUID(), new Webhook());
        Instant now = Instant.parse("2026-08-16T12:00:00Z");

        assertThatThrownBy(() -> delivery.claim("worker-1", now.plusSeconds(30), now))
                .isInstanceOf(IllegalStateException.class);
    }
}
