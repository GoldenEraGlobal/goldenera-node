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
package global.goldenera.node.shared.services.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.shared.enums.WebhookEventType;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ge.general.postgresql-enable", havingValue = "true")
public class DurableUniversalWebhookStore {
	private static final int READY = 0;
	private static final int IN_FLIGHT = 1;
	private static final int DELIVERED = 2;
	private static final int RETRY = 3;
	private static final int DEAD = 4;
	private static final int ROUTER_LOCK_NAMESPACE = 0x55485731;

	private final JdbcTemplate jdbcTemplate;

	@Transactional(readOnly = true)
	public long sourceCursor(WebhookType source) {
		requireUniversalSource(source);
		Long cursor = jdbcTemplate.queryForObject(
				"SELECT last_source_sequence FROM universal_webhook_source_cursor WHERE source = ?",
				Long.class, source.getCode());
		return cursor == null ? 0L : cursor;
	}

	@Transactional(readOnly = true)
	public boolean hasEligibleRules(
			WebhookType source, UUID originEpoch, Integer originStream, Long originSequence) {
		requireUniversalSource(source);
		Boolean eligible = jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1
				    FROM webhook_event subscription
				    JOIN webhook destination ON destination.id = subscription.webhook_id
				    JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				    WHERE destination.type = ?
				      AND destination.enabled = TRUE
				      AND api_key_row.enabled = TRUE
				      AND (
				          ?::integer IS NULL
				          OR (?::integer = 0
				              AND subscription.universal_canonical_epoch = ?::uuid
				              AND subscription.universal_canonical_after_sequence < ?::bigint)
				          OR (?::integer = 1
				              AND subscription.universal_mempool_epoch = ?::uuid
				              AND subscription.universal_mempool_after_sequence < ?::bigint)
				      )
				)
				""", Boolean.class,
				source.getCode(),
				originStream,
				originStream, originEpoch, originSequence,
				originStream, originEpoch, originSequence);
		return Boolean.TRUE.equals(eligible);
	}

	@Transactional(readOnly = true)
	public boolean hasEligibleExplorerRules(long blockHeight) {
		Boolean eligible = jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1
				    FROM webhook_event subscription
				    JOIN webhook destination ON destination.id = subscription.webhook_id
				    JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				    WHERE destination.type = ?
				      AND destination.enabled = TRUE
				      AND api_key_row.enabled = TRUE
				      AND subscription.universal_explorer_after_height < ?
				)
				""", Boolean.class, WebhookType.EXPLORER.getCode(), blockHeight);
		return Boolean.TRUE.equals(eligible);
	}

	@Transactional
	public void resetRuleActivation(
			UUID destinationId,
			long sourceEventId,
			UUID canonicalEpoch,
			long canonicalSequence,
			UUID mempoolEpoch,
			long mempoolSequence,
			long explorerHeight) {
		jdbcTemplate.update("""
				UPDATE webhook_event
				SET universal_active_after_source_sequence = ?,
				    universal_canonical_epoch = ?,
				    universal_canonical_after_sequence = ?,
				    universal_mempool_epoch = ?,
				    universal_mempool_after_sequence = ?,
				    universal_explorer_after_height = ?
				WHERE webhook_id = ?
				""", sourceEventId, canonicalEpoch, canonicalSequence, mempoolEpoch, mempoolSequence,
				explorerHeight, destinationId);
	}

	@Transactional(readOnly = true)
	public JournalCursor journalCursor(WebhookType consumerSource, int journalStream) {
		requireUniversalSource(consumerSource);
		return jdbcTemplate.queryForObject("""
				SELECT journal_epoch, last_sequence
				FROM universal_webhook_journal_cursor
				WHERE consumer_source = ? AND journal_stream = ?
				""", (resultSet, rowNumber) -> new JournalCursor(
				resultSet.getObject("journal_epoch", UUID.class), resultSet.getLong("last_sequence")),
				consumerSource.getCode(), journalStream);
	}

	@Transactional
	public boolean advanceJournalCursor(
			WebhookType consumerSource, int journalStream, UUID journalEpoch,
			long expectedSequence, long newSequence, Instant now) {
		requireUniversalSource(consumerSource);
		return jdbcTemplate.update("""
				UPDATE universal_webhook_journal_cursor
				SET last_sequence = ?, updated_at = ?
				WHERE consumer_source = ? AND journal_stream = ?
				  AND journal_epoch = ? AND last_sequence = ?
				""", newSequence, Timestamp.from(now), consumerSource.getCode(), journalStream,
				journalEpoch, expectedSequence) == 1;
	}

	@Transactional
	public void reanchorJournalLineage(
			WebhookType consumerSource, int journalStream, UUID journalEpoch, long sequence, Instant now) {
		requireUniversalSource(consumerSource);
		jdbcTemplate.update("""
				UPDATE universal_webhook_journal_cursor
				SET journal_epoch = ?, last_sequence = ?, updated_at = ?
				WHERE consumer_source = ? AND journal_stream = ?
				""", journalEpoch, sequence, Timestamp.from(now), consumerSource.getCode(), journalStream);
		String epochColumn = journalStream == 0 ? "universal_canonical_epoch" : "universal_mempool_epoch";
		String sequenceColumn = journalStream == 0
				? "universal_canonical_after_sequence"
				: "universal_mempool_after_sequence";
		jdbcTemplate.update("""
				UPDATE webhook_event subscription
				SET %s = ?, %s = ?
				FROM webhook destination
				WHERE destination.id = subscription.webhook_id
				  AND destination.enabled = TRUE
				  AND destination.type = ?
				""".formatted(epochColumn, sequenceColumn),
				journalEpoch, sequence, consumerSource.getCode());
	}

	@Transactional
	public boolean recoverJournalFloor(
			WebhookType consumerSource,
			int journalStream,
			UUID journalEpoch,
			long expectedSequence,
			long recoveredSequence,
			Instant now) {
		requireUniversalSource(consumerSource);
		if (recoveredSequence < expectedSequence) {
			throw new IllegalArgumentException("Recovered webhook journal cursor cannot move backwards");
		}
		return jdbcTemplate.update("""
				UPDATE universal_webhook_journal_cursor
				SET last_sequence = ?, updated_at = ?
				WHERE consumer_source = ? AND journal_stream = ?
				  AND journal_epoch = ? AND last_sequence = ?
				""",
				recoveredSequence,
				Timestamp.from(now),
				consumerSource.getCode(),
				journalStream,
				journalEpoch,
				expectedSequence) == 1;
	}

	@Transactional
	public long append(
			UUID eventId,
			WebhookType source,
			WebhookEventType eventType,
			WebhookTxStatus txStatus,
			String payload,
			Address addressA,
			Address addressB,
			Address tokenAddress,
			Instant occurredAt) {
		return append(eventId, source, eventType, txStatus, payload, addressA, addressB, tokenAddress,
				occurredAt, null, null, null, null);
	}

	@Transactional
	public long append(
			UUID eventId,
			WebhookType source,
			WebhookEventType eventType,
			WebhookTxStatus txStatus,
			String payload,
			Address addressA,
			Address addressB,
			Address tokenAddress,
			Instant occurredAt,
			UUID originEpoch,
			Integer originStream,
			Long originSequence,
			Long originBlockHeight) {
		requireUniversalSource(source);
		Long lastSourceSequence = jdbcTemplate.queryForObject("""
				SELECT last_source_sequence
				FROM universal_webhook_source_cursor
				WHERE source = ?
				FOR UPDATE
				""", Long.class, source.getCode());
		Long existingId = jdbcTemplate.query("""
				SELECT id
				FROM universal_webhook_source_event
				WHERE event_id = ?
				""", resultSet -> resultSet.next() ? resultSet.getLong(1) : null, eventId);
		if (existingId != null) {
			return existingId;
		}
		long sourceSequence = Math.incrementExact(lastSourceSequence == null ? 0L : lastSourceSequence);
		jdbcTemplate.update("""
				UPDATE universal_webhook_source_cursor
				SET last_source_sequence = ?, updated_at = ?
				WHERE source = ?
				""", sourceSequence, Timestamp.from(Instant.now()), source.getCode());
		List<Long> inserted = jdbcTemplate.query("""
				INSERT INTO universal_webhook_source_event
				(event_id, source, source_sequence, event_type, tx_status, origin_epoch, origin_stream, origin_sequence,
				 origin_block_height,
				 payload, address_a, address_b,
				 token_address, occurred_at, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (event_id) DO NOTHING
				RETURNING id
				""", (resultSet, rowNumber) -> resultSet.getLong(1),
				eventId,
				source.getCode(),
				sourceSequence,
				eventType.getCode(),
				txStatus == null ? null : txStatus.getCode(),
				originEpoch,
				originStream,
				originSequence,
				originBlockHeight,
				payload,
				bytes(addressA),
				bytes(addressB),
				bytes(tokenAddress),
				Timestamp.from(occurredAt),
				Timestamp.from(Instant.now()));

		return inserted.getFirst();
	}

	@Transactional
	public int routePending(int limit, Instant now) {
		List<Long> eventIds = new ArrayList<>(limit);
		for (int source = 0; source <= 1 && eventIds.size() < limit; source++) {
			Boolean locked = jdbcTemplate.queryForObject(
					"SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class, ROUTER_LOCK_NAMESPACE, source);
			if (!Boolean.TRUE.equals(locked)) {
				continue;
			}
			int remaining = limit - eventIds.size();
			eventIds.addAll(jdbcTemplate.query("""
					SELECT event.id
					FROM universal_webhook_source_event event
					WHERE event.source = ? AND event.routed_at IS NULL
					ORDER BY event.source_sequence
					FOR UPDATE
					LIMIT ?
					""", (resultSet, rowNumber) -> resultSet.getLong(1), source, remaining));
		}
		for (Long eventId : eventIds) {
			route(eventId, now);
		}
		return eventIds.size();
	}

	private void route(long sourceEventId, Instant now) {
		jdbcTemplate.update("""
				INSERT INTO universal_webhook_delivery
				(delivery_id, source_event_id, destination_id, state, attempts,
				 next_attempt_at, created_at, updated_at)
				SELECT
				    md5(destination.id::text || ':' || event.event_id::text)::uuid,
				    event.id,
				    destination.id,
				    0,
				    0,
				    ?,
				    ?,
				    ?
					FROM universal_webhook_source_event event
					JOIN webhook destination
					  ON destination.type = event.source
					 AND destination.enabled = TRUE
				JOIN api_key api_key_row
				  ON api_key_row.id = destination.created_by_api_key_id
				 AND api_key_row.enabled = TRUE
				JOIN webhook_event subscription
				  ON subscription.webhook_id = destination.id
				 AND subscription.type = event.event_type
				WHERE event.id = ?
				  AND subscription.universal_active_after_source_sequence < event.source_sequence
				  AND (event.source <> 1 OR event.origin_block_height IS NULL
				       OR subscription.universal_explorer_after_height < event.origin_block_height)
				  AND (
				      event.origin_stream IS NULL
				      OR (event.origin_stream = 0
				          AND subscription.universal_canonical_epoch = event.origin_epoch
				          AND subscription.universal_canonical_after_sequence < event.origin_sequence)
				      OR (event.origin_stream = 1
				          AND subscription.universal_mempool_epoch = event.origin_epoch
				          AND subscription.universal_mempool_after_sequence < event.origin_sequence)
				  )
				  AND (
				      event.event_type <> 1
				      OR (
				          subscription.address_filter IS NOT NULL
				          AND subscription.address_filter IN (event.address_a, event.address_b)
				          AND (subscription.token_address_filter IS NULL
				               OR subscription.token_address_filter = event.token_address)
				      )
				  )
				GROUP BY destination.id, event.id, event.event_id
				ON CONFLICT (destination_id, source_event_id) DO NOTHING
				""", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), sourceEventId);
		jdbcTemplate.update(
				"UPDATE universal_webhook_source_event SET routed_at = ? WHERE id = ? AND routed_at IS NULL",
				Timestamp.from(now), sourceEventId);
	}

	@Transactional
	public List<ClaimedDelivery> claimAvailable(String owner, Instant now, Duration leaseDuration, int limit) {
		Instant leaseUntil = now.plus(leaseDuration);
		return jdbcTemplate.query("""
				WITH candidates AS (
				    SELECT delivery.id
				    FROM universal_webhook_delivery delivery
				    JOIN universal_webhook_source_event event ON event.id = delivery.source_event_id
				    JOIN webhook destination ON destination.id = delivery.destination_id
				    JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				    WHERE destination.enabled = TRUE
				      AND api_key_row.enabled = TRUE
				      AND (
				          (delivery.state IN (0, 3) AND delivery.next_attempt_at <= ?)
				          OR (delivery.state = 1 AND delivery.lease_until < ?)
				      )
				      AND NOT EXISTS (
				          SELECT 1
				          FROM universal_webhook_delivery older
				          JOIN universal_webhook_source_event older_event ON older_event.id = older.source_event_id
				          WHERE older.destination_id = delivery.destination_id
				            AND older.state NOT IN (2, 4)
				            AND (
				                older_event.source_sequence < event.source_sequence
				                OR (older_event.source_sequence = event.source_sequence AND older.id < delivery.id)
				            )
				      )
				    ORDER BY event.source, event.source_sequence, delivery.id
				    FOR UPDATE OF delivery SKIP LOCKED
				    LIMIT ?
				), claimed AS (
				    UPDATE universal_webhook_delivery delivery
				    SET state = 1,
				        attempts = attempts + 1,
				        lease_owner = ?,
				        lease_until = ?,
				        updated_at = ?
				    FROM candidates
				    WHERE delivery.id = candidates.id
				    RETURNING delivery.*
				)
				SELECT claimed.delivery_id, event.event_id, claimed.id, claimed.attempts,
				       event.payload, destination.url,
				       COALESCE(destination.secret_key, api_key_row.webhook_secret_key) AS encrypted_secret
				FROM claimed
				JOIN universal_webhook_source_event event ON event.id = claimed.source_event_id
				JOIN webhook destination ON destination.id = claimed.destination_id
				JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				ORDER BY claimed.id
				""", this::mapClaim,
				Timestamp.from(now), Timestamp.from(now), limit, owner,
				Timestamp.from(leaseUntil), Timestamp.from(now));
	}

	@Transactional
	public boolean markDelivered(UUID deliveryId, String owner, int httpStatus, Instant now) {
		return jdbcTemplate.update("""
				UPDATE universal_webhook_delivery
				SET state = ?, last_http_status = ?, last_error = NULL, delivered_at = ?,
				    lease_owner = NULL, lease_until = NULL, updated_at = ?
				WHERE delivery_id = ? AND state = ? AND lease_owner = ?
				""", DELIVERED, httpStatus, Timestamp.from(now), Timestamp.from(now),
				deliveryId, IN_FLIGHT, owner) == 1;
	}

	@Transactional
	public boolean markRetry(UUID deliveryId, String owner, Integer httpStatus, String error,
			Instant retryAt, Instant now) {
		return updateFailure(deliveryId, owner, RETRY, httpStatus, error, retryAt, now, false);
	}

	@Transactional
	public boolean markDead(UUID deliveryId, String owner, Integer httpStatus, String error, Instant now) {
		return updateFailure(deliveryId, owner, DEAD, httpStatus, error, now, now, true);
	}

	private boolean updateFailure(UUID deliveryId, String owner, int state, Integer httpStatus, String error,
			Instant nextAttemptAt, Instant now, boolean dead) {
		return jdbcTemplate.update("""
				UPDATE universal_webhook_delivery
				SET state = ?, last_http_status = ?, last_error = ?, next_attempt_at = ?, dead_at = ?,
				    lease_owner = NULL, lease_until = NULL, updated_at = ?
				WHERE delivery_id = ? AND state = ? AND lease_owner = ?
				""", state, httpStatus, truncate(error), Timestamp.from(nextAttemptAt),
				dead ? Timestamp.from(now) : null, Timestamp.from(now), deliveryId, IN_FLIGHT, owner) == 1;
	}

	private ClaimedDelivery mapClaim(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ClaimedDelivery(
				resultSet.getObject("delivery_id", UUID.class),
				resultSet.getObject("event_id", UUID.class),
				resultSet.getLong("id"),
				resultSet.getInt("attempts"),
				resultSet.getString("payload"),
				resultSet.getString("url"),
				resultSet.getBytes("encrypted_secret"));
	}

	private byte[] bytes(Address address) {
		return address == null ? null : address.toArray();
	}

	private String truncate(String error) {
		if (error == null || error.length() <= 2048) {
			return error;
		}
		return error.substring(0, 2048);
	}

	private void requireUniversalSource(WebhookType source) {
		if (source == WebhookType.BRIDGE) {
			throw new IllegalArgumentException("Bridge webhook events use their dedicated delivery pipeline");
		}
	}

	public record ClaimedDelivery(
			UUID deliveryId,
			UUID eventId,
			long sequence,
			int attempt,
			String payload,
			String url,
			byte[] encryptedSecret) {
	}

	public record JournalCursor(UUID epoch, long sequence) {
	}
}
