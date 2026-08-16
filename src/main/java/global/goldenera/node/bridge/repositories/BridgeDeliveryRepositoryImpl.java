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
package global.goldenera.node.bridge.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BridgeDeliveryRepositoryImpl implements BridgeDeliveryRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<BridgeDeliveryReservation> reserve(UUID eventId, UUID destinationId, Instant now) {
        UUID deliveryId = UUID.randomUUID();
        List<BridgeDeliveryReservation> reservations = jdbcTemplate.query(
                """
                        INSERT INTO bridge_delivery (
                            delivery_id, event_id, destination_id, body, state, attempts,
                            next_attempt_at, created_at, updated_at, version
                        ) VALUES (?, ?, ?, NULL, 0, 0, ?, ?, ?, 0)
                        ON CONFLICT (destination_id, event_id) DO NOTHING
                        RETURNING id, delivery_id
                        """,
                this::mapReservation,
                deliveryId,
                eventId,
                destinationId,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        return reservations.stream().findFirst();
    }

    @Override
    public boolean setBodyOnce(long id, String body, Instant now) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Bridge delivery body is required");
        }
        return jdbcTemplate.update(
                "UPDATE bridge_delivery SET body = ?, updated_at = ?, version = version + 1 WHERE id = ? AND body IS NULL",
                body,
                Timestamp.from(now),
                id) == 1;
    }

    private BridgeDeliveryReservation mapReservation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new BridgeDeliveryReservation(
                resultSet.getLong("id"),
                resultSet.getObject("delivery_id", UUID.class));
    }
}
