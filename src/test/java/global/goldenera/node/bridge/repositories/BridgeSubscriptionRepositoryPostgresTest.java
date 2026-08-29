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

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.webhook.BridgeLifecycleProjectionCursorStore;
import global.goldenera.node.bridge.webhook.BridgeLifecycleProjectionCursorStore.Cursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.enums.WebhookType;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class BridgeSubscriptionRepositoryPostgresTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("goldenera")
			.withUsername("goldenera")
			.withPassword("goldenera");

	private static JdbcTemplate jdbc;
	private static TransactionTemplate transactions;
	private static BridgeSubscriptionRepositoryImpl repository;

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
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		repository = new BridgeSubscriptionRepositoryImpl(jdbc);
	}

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE bridge_lifecycle_cursor, bridge_subscription, webhook, api_key CASCADE");
	}

	@Test
	void concurrentSubscribeReturnsOneStableSubscription() throws Exception {
		long apiKeyId = 701L;
		String destinationKey = "https://consumer.example/bridge";
		UUID destinationId = insertDestination(apiKeyId, destinationKey);
		Address address = Address.fromHexString("0x1111111111111111111111111111111111111111");
		UUID epoch = UUID.randomUUID();
		CountDownLatch start = new CountDownLatch(1);

		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<UUID> first = executor.submit(() -> subscribe(
					start, apiKeyId, destinationKey, destinationId, address, epoch, UUID.randomUUID(), 100L));
			Future<UUID> second = executor.submit(() -> subscribe(
					start, apiKeyId, destinationKey, destinationId, address, epoch, UUID.randomUUID(), 100L));
			start.countDown();

			assertThat(first.get()).isEqualTo(second.get());
		}

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bridge_subscription", Long.class)).isOne();
	}

	@Test
	void reEnableUsesCurrentLowerCanonicalHeight() {
		long apiKeyId = 702L;
		String destinationKey = "https://consumer.example/re-enable";
		UUID destinationId = insertDestination(apiKeyId, destinationKey);
		Address address = Address.fromHexString("0x2222222222222222222222222222222222222222");
		UUID oldEpoch = UUID.randomUUID();
		UUID subscriptionId = transactions.execute(status -> repository.upsertEnabled(
				UUID.randomUUID(), destinationId, Network.MAINNET, address,
				oldEpoch, 10L, oldEpoch, 20L, 1_000L, Instant.now()));
		UUID idempotent = transactions.execute(status -> repository.upsertEnabled(
				UUID.randomUUID(), destinationId, Network.MAINNET, address,
				UUID.randomUUID(), 30L, UUID.randomUUID(), 40L, 1_100L, Instant.now()));
		assertThat(idempotent).isEqualTo(subscriptionId);
		assertThat(jdbc.queryForObject(
				"SELECT active_after_canonical_height FROM bridge_subscription WHERE id = ?",
				Long.class,
				subscriptionId)).isEqualTo(1_000L);
		jdbc.update("UPDATE bridge_subscription SET enabled = FALSE WHERE id = ?", subscriptionId);
		UUID currentEpoch = UUID.randomUUID();

		UUID reenabled = transactions.execute(status -> repository.upsertEnabled(
				UUID.randomUUID(), destinationId, Network.MAINNET, address,
				currentEpoch, 30L, currentEpoch, 40L, 900L, Instant.now()));

		assertThat(reenabled).isEqualTo(subscriptionId);
		var row = jdbc.queryForMap("SELECT * FROM bridge_subscription WHERE id = ?", subscriptionId);
		assertThat(row.get("enabled")).isEqualTo(true);
		assertThat(row.get("active_after_canonical_height")).isEqualTo(900L);
		assertThat(row.get("active_from_canonical_epoch")).isEqualTo(currentEpoch);
		assertThat(row.get("active_from_canonical_sequence")).isEqualTo(30L);
	}

	@Test
	void bridgeFloorRecoveryUsesCompareAndSet() {
		UUID epoch = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO bridge_lifecycle_cursor (stream, journal_epoch, last_sequence, updated_at)
				VALUES (?, ?, 3, now())
				""", LifecycleJournalStream.CANONICAL.code(), epoch);
		jdbc.update("""
				INSERT INTO bridge_reorg_pending (tx_hash, canonical_reverted, revert_sequence, updated_at)
				VALUES (?, TRUE, 3, now())
				""", Hash.ZERO.toArray());
		BridgeLifecycleProjectionCursorStore cursorStore = new BridgeLifecycleProjectionCursorStore(jdbc);
		LifecycleJournalHead head = new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 20L, 10L, 20L, Hash.ZERO);

		assertThat(cursorStore.recoverFloor(head, new Cursor(epoch, 2L))).isFalse();
		assertThat(cursorStore.recoverFloor(head, new Cursor(epoch, 3L))).isTrue();
		assertThat(cursorStore.current(LifecycleJournalStream.CANONICAL))
				.isEqualTo(new Cursor(epoch, 9L));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bridge_reorg_pending", Long.class)).isZero();
	}

	private UUID subscribe(
			CountDownLatch start,
			long apiKeyId,
			String destinationKey,
			UUID destinationId,
			Address address,
			UUID epoch,
			UUID candidateId,
			long height) throws InterruptedException {
		start.await();
		return transactions.execute(status -> {
			repository.lockDestination(apiKeyId, destinationKey);
			return repository.upsertEnabled(
					candidateId, destinationId, Network.MAINNET, address,
					epoch, 10L, epoch, 20L, height, Instant.now());
		});
	}

	private UUID insertDestination(long apiKeyId, String destinationKey) {
		byte[] secret = new byte[32];
		jdbc.update("""
				INSERT INTO api_key
				(id, created_at, enabled, key_prefix, label, secret_key, webhook_secret_key, version)
				VALUES (?, ?, TRUE, ?, ?, ?, ?, 0)
				""", apiKeyId, Timestamp.from(Instant.now()), "key-" + apiKeyId, "key-" + apiKeyId,
				secret, secret);
		UUID destinationId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO webhook
				(id, created_at, enabled, label, type, url, bridge_destination_key, version, created_by_api_key_id)
				VALUES (?, ?, TRUE, ?, ?, ?, ?, 0, ?)
				""", destinationId, Timestamp.from(Instant.now()), "hook-" + apiKeyId,
				WebhookType.BRIDGE.getCode(), destinationKey, destinationKey, apiKeyId);
		return destinationId;
	}
}
