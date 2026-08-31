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
package global.goldenera.node.bridge.webhook;

import static lombok.AccessLevel.PRIVATE;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Service
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(name = "ge.general.postgresql-enable", havingValue = "true")
public class BridgeDeliveryStore {

	JdbcTemplate jdbcTemplate;

	@Transactional
	public List<ClaimedDelivery> claimAvailable(String owner, Instant now, Duration leaseDuration, int limit) {
		if (owner == null || owner.isBlank() || now == null || leaseDuration == null
				|| leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("A valid bridge delivery lease is required");
		}
		if (limit <= 0) {
			return List.of();
		}
		Instant leaseUntil = now.plus(leaseDuration);
		return jdbcTemplate.query("""
				WITH candidates AS (
				    SELECT delivery.id
				    FROM bridge_delivery delivery
				    JOIN webhook destination ON destination.id = delivery.destination_id
				    JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				    WHERE delivery.body IS NOT NULL
				      AND destination.enabled = TRUE
				      AND api_key_row.enabled = TRUE
				      AND (
				          (delivery.state IN (?, ?) AND delivery.next_attempt_at <= ?)
				          OR (delivery.state = ? AND delivery.lease_until < ?)
				      )
				      AND NOT EXISTS (
				          SELECT 1
				          FROM bridge_delivery older
				          WHERE older.destination_id = delivery.destination_id
				            AND older.id < delivery.id
				            AND older.state NOT IN (?, ?)
				      )
				    ORDER BY delivery.id
				    FOR UPDATE OF delivery SKIP LOCKED
				    LIMIT ?
				), claimed AS (
				    UPDATE bridge_delivery delivery
				    SET state = ?, attempts = attempts + 1, lease_owner = ?, lease_until = ?,
				        updated_at = ?, version = version + 1
				    FROM candidates
				    WHERE delivery.id = candidates.id
				    RETURNING delivery.*
				)
				SELECT claimed.delivery_id, claimed.event_id, claimed.id, claimed.attempts,
				       claimed.body, COALESCE(destination.bridge_destination_key, destination.url) AS url,
				       COALESCE(destination.secret_key, api_key_row.webhook_secret_key) AS encrypted_secret
				FROM claimed
				JOIN webhook destination ON destination.id = claimed.destination_id
				JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				ORDER BY claimed.id
				""", this::mapClaim,
				BridgeDeliveryState.READY.getCode(), BridgeDeliveryState.RETRY.getCode(), Timestamp.from(now),
				BridgeDeliveryState.IN_FLIGHT.getCode(), Timestamp.from(now),
				BridgeDeliveryState.DELIVERED.getCode(), BridgeDeliveryState.DEAD.getCode(), limit,
				BridgeDeliveryState.IN_FLIGHT.getCode(), owner, Timestamp.from(leaseUntil), Timestamp.from(now));
	}

	@Transactional
	public boolean markDelivered(UUID deliveryId, String owner, int attempt, int httpStatus, Instant now) {
		return jdbcTemplate.update("""
				UPDATE bridge_delivery
				SET state = ?, last_http_status = ?, last_error = NULL, next_attempt_at = ?,
				    lease_owner = NULL, lease_until = NULL, updated_at = ?, version = version + 1
				WHERE delivery_id = ? AND state = ? AND lease_owner = ? AND attempts = ?
				""", BridgeDeliveryState.DELIVERED.getCode(), httpStatus, Timestamp.from(now), Timestamp.from(now),
				deliveryId, BridgeDeliveryState.IN_FLIGHT.getCode(), owner, attempt) == 1;
	}

	@Transactional
	public boolean markRetry(UUID deliveryId, String owner, int attempt, Integer httpStatus, String error,
			Instant nextAttemptAt, Instant now) {
		return updateFailure(deliveryId, owner, attempt, BridgeDeliveryState.RETRY,
				httpStatus, error, nextAttemptAt, now);
	}

	@Transactional
	public boolean markDead(UUID deliveryId, String owner, int attempt, Integer httpStatus, String error, Instant now) {
		return updateFailure(deliveryId, owner, attempt, BridgeDeliveryState.DEAD, httpStatus, error, now, now);
	}

	private boolean updateFailure(
			UUID deliveryId,
			String owner,
			int attempt,
			BridgeDeliveryState state,
			Integer httpStatus,
			String error,
			Instant nextAttemptAt,
			Instant now) {
		return jdbcTemplate.update("""
				UPDATE bridge_delivery
				SET state = ?, last_http_status = ?, last_error = ?, next_attempt_at = ?,
				    lease_owner = NULL, lease_until = NULL, updated_at = ?, version = version + 1
				WHERE delivery_id = ? AND state = ? AND lease_owner = ? AND attempts = ?
				""", state.getCode(), httpStatus, truncate(error), Timestamp.from(nextAttemptAt), Timestamp.from(now),
				deliveryId, BridgeDeliveryState.IN_FLIGHT.getCode(), owner, attempt) == 1;
	}

	@Transactional
	public int releaseLeases(String owner, Instant now) {
		return jdbcTemplate.update("""
				UPDATE bridge_delivery
				SET state = ?, next_attempt_at = ?, lease_owner = NULL, lease_until = NULL,
				    updated_at = ?, version = version + 1
				WHERE state = ? AND lease_owner = ?
				""", BridgeDeliveryState.RETRY.getCode(), Timestamp.from(now), Timestamp.from(now),
				BridgeDeliveryState.IN_FLIGHT.getCode(), owner);
	}

	private ClaimedDelivery mapClaim(ResultSet resultSet, int rowNumber) throws SQLException {
		byte[] encryptedSecret = resultSet.getBytes("encrypted_secret");
		return new ClaimedDelivery(
				resultSet.getObject("delivery_id", UUID.class),
				resultSet.getObject("event_id", UUID.class),
				resultSet.getLong("id"),
				resultSet.getInt("attempts"),
				resultSet.getString("body"),
				resultSet.getString("url"),
				encryptedSecret == null ? null : Bytes.wrap(encryptedSecret));
	}

	private String truncate(String error) {
		if (error == null || error.length() <= 2048) {
			return error;
		}
		return error.substring(0, 2048);
	}

	@Getter
	@AllArgsConstructor
	@FieldDefaults(level = PRIVATE, makeFinal = true)
	public static class ClaimedDelivery {
		UUID deliveryId;
		UUID eventId;
		long sequence;
		int attempt;
		String body;
		String url;
		Bytes encryptedSecret;
	}
}
