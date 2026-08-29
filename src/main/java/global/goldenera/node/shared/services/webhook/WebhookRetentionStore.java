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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.enums.WebhookType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "ge.general",
		name = { "postgresql-enable", "webhook-enable" },
		havingValue = "true")
@ConditionalOnProperty(
		prefix = "ge.webhook.retention",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class WebhookRetentionStore {

	private static final int DELIVERED = 2;
	private static final int DEAD = 4;

	private final JdbcTemplate jdbcTemplate;

	@Transactional
	public CleanupCounts cleanupBatch(Instant cutoff, int limit) {
		Timestamp cutoffTimestamp = Timestamp.from(cutoff);
		int universalDeliveries = deleteTerminalDeliveries(
				"universal_webhook_delivery", cutoffTimestamp, limit);
		int bridgeDeliveries = deleteTerminalDeliveries("bridge_delivery", cutoffTimestamp, limit);
		int sourceEvents = jdbcTemplate.update("""
				WITH candidates AS (
				    SELECT event.id
				    FROM universal_webhook_source_event event
				    WHERE event.routed_at IS NOT NULL
				      AND event.routed_at < ?
				      AND NOT EXISTS (
				          SELECT 1
				          FROM universal_webhook_delivery delivery
				          WHERE delivery.source_event_id = event.id
				      )
				    ORDER BY event.id
				    FOR UPDATE OF event SKIP LOCKED
				    LIMIT ?
				)
				DELETE FROM universal_webhook_source_event event
				USING candidates
				WHERE event.id = candidates.id
				""", cutoffTimestamp, limit);
		int completeReorgCorrelations = jdbcTemplate.update("""
				WITH candidates AS (
				    SELECT pending.tx_hash
				    FROM bridge_reorg_pending pending
				    WHERE pending.updated_at < ?
				      AND pending.canonical_reverted = TRUE
				      AND pending.raw_tx IS NOT NULL
				      AND pending.readd_epoch IS NOT NULL
				      AND pending.readd_sequence IS NOT NULL
				      AND pending.readd_event_key IS NOT NULL
				    ORDER BY pending.updated_at, pending.tx_hash
				    FOR UPDATE OF pending SKIP LOCKED
				    LIMIT ?
				)
				DELETE FROM bridge_reorg_pending pending
				USING candidates
				WHERE pending.tx_hash = candidates.tx_hash
				""", cutoffTimestamp, limit);
		return new CleanupCounts(
				universalDeliveries, bridgeDeliveries, sourceEvents, completeReorgCorrelations);
	}

	@Transactional(readOnly = true)
	public JournalConsumers journalConsumers(LifecycleJournalStream stream) {
		boolean universalRequired = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1
				    FROM webhook destination
				    JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				    JOIN webhook_event subscription ON subscription.webhook_id = destination.id
				    WHERE destination.type = ?
				      AND destination.enabled = TRUE
				      AND api_key_row.enabled = TRUE
				)
				""", Boolean.class, WebhookType.BLOCKCHAIN.getCode()));
		boolean bridgeRequired = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1
				    FROM bridge_subscription subscription
				    JOIN webhook destination ON destination.id = subscription.destination_id
				    JOIN api_key api_key_row ON api_key_row.id = destination.created_by_api_key_id
				    WHERE subscription.enabled = TRUE
				      AND destination.enabled = TRUE
				      AND api_key_row.enabled = TRUE
				)
				""", Boolean.class));
		return new JournalConsumers(
				universalRequired,
				cursor("""
						SELECT journal_epoch, last_sequence
						FROM universal_webhook_journal_cursor
						WHERE consumer_source = ? AND journal_stream = ?
						""", WebhookType.BLOCKCHAIN.getCode(), stream.code()),
				bridgeRequired,
				cursor("""
						SELECT journal_epoch, last_sequence
						FROM bridge_lifecycle_cursor
						WHERE stream = ?
						""", stream.code()));
	}

	private int deleteTerminalDeliveries(String table, Timestamp cutoff, int limit) {
		if (!table.equals("universal_webhook_delivery") && !table.equals("bridge_delivery")) {
			throw new IllegalArgumentException("Unsupported webhook delivery table");
		}
		return jdbcTemplate.update("""
				WITH candidates AS (
				    SELECT delivery.id
				    FROM %s delivery
				    WHERE delivery.state IN (?, ?)
				      AND delivery.updated_at < ?
				    ORDER BY delivery.id
				    FOR UPDATE OF delivery SKIP LOCKED
				    LIMIT ?
				)
				DELETE FROM %s delivery
				USING candidates
				WHERE delivery.id = candidates.id
				""".formatted(table, table), DELIVERED, DEAD, cutoff, limit);
	}

	private Optional<JournalCursor> cursor(String sql, Object... arguments) {
		List<JournalCursor> cursors = jdbcTemplate.query(
				sql,
				(resultSet, rowNumber) -> new JournalCursor(
						resultSet.getObject("journal_epoch", UUID.class),
						resultSet.getLong("last_sequence")),
				arguments);
		return cursors.stream().findFirst();
	}

	public record CleanupCounts(
			int universalDeliveries,
			int bridgeDeliveries,
			int sourceEvents,
			int completeReorgCorrelations) {

		public int total() {
			return universalDeliveries + bridgeDeliveries + sourceEvents + completeReorgCorrelations;
		}
	}

	public record JournalCursor(UUID epoch, long sequence) {
	}

	public record JournalConsumers(
			boolean universalRequired,
			Optional<JournalCursor> universal,
			boolean bridgeRequired,
			Optional<JournalCursor> bridge) {
	}
}
