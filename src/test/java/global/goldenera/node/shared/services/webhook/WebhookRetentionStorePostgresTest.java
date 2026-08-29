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

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.WebhookRetentionStore.CleanupCounts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class WebhookRetentionStorePostgresTest {

	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final Instant OLD = NOW.minus(100, ChronoUnit.DAYS);
	private static final Instant RECENT = NOW.minus(10, ChronoUnit.DAYS);

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("goldenera")
			.withUsername("goldenera")
			.withPassword("goldenera");

	private static JdbcTemplate jdbc;

	@BeforeAll
	static void migrate() throws Exception {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(
							"db/changelog/db.changelog-master.yaml", resources, database)) {
				liquibase.update();
			}
		}
		jdbc = new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE universal_webhook_delivery, universal_webhook_source_event, bridge_delivery, bridge_reorg_pending, bridge_subscription, webhook_event, webhook, api_key CASCADE");
	}

	@Test
	void deletesOnlyOldTerminalRowsAndTheirUnreferencedSources() {
		UUID destination = insertDestination();
		long oldTerminalSource = insertSource(1L, OLD, true);
		long oldNonterminalSource = insertSource(2L, OLD, true);
		long recentTerminalSource = insertSource(3L, RECENT, true);
		long oldUnreferencedSource = insertSource(4L, OLD, true);
		insertUniversalDelivery(destination, oldTerminalSource, 2, OLD);
		insertUniversalDelivery(destination, oldNonterminalSource, 3, OLD);
		insertUniversalDelivery(destination, recentTerminalSource, 4, RECENT);
		insertBridgeDelivery(destination, 2, OLD);
		insertBridgeDelivery(destination, 3, OLD);
		insertBridgeDelivery(destination, 4, RECENT);
		insertCorrelation(true, true, OLD);
		insertCorrelation(true, false, OLD);
		insertCorrelation(true, true, RECENT);

		CleanupCounts deleted = new WebhookRetentionStore(jdbc)
				.cleanupBatch(NOW.minus(90, ChronoUnit.DAYS), 100);

		assertThat(deleted.universalDeliveries()).isEqualTo(1);
		assertThat(deleted.bridgeDeliveries()).isEqualTo(1);
		assertThat(deleted.sourceEvents()).isEqualTo(2);
		assertThat(deleted.completeReorgCorrelations()).isEqualTo(1);
		assertThat(ids("universal_webhook_source_event"))
				.containsExactlyInAnyOrder(oldNonterminalSource, recentTerminalSource)
				.doesNotContain(oldTerminalSource, oldUnreferencedSource);
		assertThat(states("universal_webhook_delivery")).containsExactlyInAnyOrder(3, 4);
		assertThat(states("bridge_delivery")).containsExactlyInAnyOrder(3, 4);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bridge_reorg_pending", Long.class)).isEqualTo(2L);
	}

	private UUID insertDestination() {
		byte[] secret = new byte[32];
		jdbc.update("""
				INSERT INTO api_key
				(id, created_at, enabled, key_prefix, label, secret_key, webhook_secret_key, version)
				VALUES (501, ?, TRUE, 'retention', 'retention', ?, ?, 0)
				""", Timestamp.from(NOW), secret, secret);
		UUID destination = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO webhook
				(id, created_at, enabled, label, type, url, version, created_by_api_key_id)
				VALUES (?, ?, TRUE, 'retention-hook', ?, 'https://example.invalid/retention', 0, 501)
				""", destination, Timestamp.from(NOW), WebhookType.BLOCKCHAIN.getCode());
		return destination;
	}

	private long insertSource(long sequence, Instant timestamp, boolean routed) {
		return jdbc.queryForObject("""
				INSERT INTO universal_webhook_source_event
				(event_id, source, source_sequence, event_type, payload, occurred_at, routed_at, created_at)
				VALUES (?, 0, ?, 0, '{}', ?, ?, ?)
				RETURNING id
				""", Long.class, UUID.randomUUID(), sequence, Timestamp.from(timestamp),
				routed ? Timestamp.from(timestamp) : null, Timestamp.from(timestamp));
	}

	private void insertUniversalDelivery(UUID destination, long source, int state, Instant timestamp) {
		jdbc.update("""
				INSERT INTO universal_webhook_delivery
				(delivery_id, source_event_id, destination_id, state, attempts, next_attempt_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, ?, ?, ?)
				""", UUID.randomUUID(), source, destination, state,
				Timestamp.from(timestamp), Timestamp.from(timestamp), Timestamp.from(timestamp));
	}

	private void insertBridgeDelivery(UUID destination, int state, Instant timestamp) {
		jdbc.update("""
				INSERT INTO bridge_delivery
				(delivery_id, event_id, destination_id, body, state, attempts,
				 next_attempt_at, created_at, updated_at, version)
				VALUES (?, ?, ?, '{}', ?, 0, ?, ?, ?, 0)
				""", UUID.randomUUID(), UUID.randomUUID(), destination, state,
				Timestamp.from(timestamp), Timestamp.from(timestamp), Timestamp.from(timestamp));
	}

	private void insertCorrelation(boolean reverted, boolean readded, Instant timestamp) {
		UUID epoch = readded ? UUID.randomUUID() : null;
		jdbc.update("""
				INSERT INTO bridge_reorg_pending
				(tx_hash, canonical_reverted, revert_sequence, readd_sequence, readd_epoch,
				 readd_event_key, raw_tx, first_seen_height, first_seen_at, updated_at)
				VALUES (?, ?, 1, ?, ?, ?, ?, 1, ?, ?)
				""", UUID.randomUUID().toString().getBytes(), reverted,
				readded ? 2L : null, epoch, readded ? UUID.randomUUID() : null,
				readded ? new byte[] { 1 } : null,
				Timestamp.from(timestamp), Timestamp.from(timestamp));
	}

	private List<Long> ids(String table) {
		return jdbc.queryForList("SELECT id FROM " + table + " ORDER BY id", Long.class);
	}

	private List<Integer> states(String table) {
		return jdbc.queryForList("SELECT state FROM " + table + " ORDER BY id", Integer.class);
	}
}
