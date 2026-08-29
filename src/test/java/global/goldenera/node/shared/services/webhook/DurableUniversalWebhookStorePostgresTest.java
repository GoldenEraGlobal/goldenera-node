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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookEventDtoV1;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookEventType;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.ClaimedDelivery;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.JournalCursor;
import global.goldenera.node.shared.repositories.WebhookCoreRepository;
import global.goldenera.node.shared.repositories.WebhookEventCoreRepository;
import global.goldenera.node.shared.services.core.WebhookCoreService;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class DurableUniversalWebhookStorePostgresTest {
	private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
	private static final String DURABLE_CHANGELOG =
			"db/changelog/changesets/010-durable-universal-webhook.yaml";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:17-alpine")
					.withDatabaseName("goldenera")
					.withUsername("goldenera")
					.withPassword("goldenera");

	private static JdbcTemplate jdbc;
	private static TransactionTemplate transactions;

	@BeforeAll
	static void migrate() throws Exception {
		apply(MASTER_CHANGELOG);
		apply(DURABLE_CHANGELOG);
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	private static void apply(String changelog) throws Exception {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(changelog, resources, database)) {
				liquibase.update();
			}
		}
	}

	@BeforeEach
	void resetData() {
		jdbc.execute("TRUNCATE universal_webhook_delivery, universal_webhook_source_event, webhook_event, webhook, api_key CASCADE");
		jdbc.update("UPDATE universal_webhook_source_cursor SET last_source_sequence = 0, updated_at = now()");
		jdbc.update("UPDATE universal_webhook_journal_cursor SET journal_epoch = NULL, last_sequence = 0, updated_at = now()");
	}

	@Test
	void durableDedupeActivationRetryRestartAndFifo() {
		Instant now = Instant.parse("2026-08-29T00:00:00Z");
		UUID firstDestination = insertDestination(101L, WebhookType.BLOCKCHAIN, "first");
		DurableUniversalWebhookStore store = new DurableUniversalWebhookStore(jdbc);

		UUID firstEvent = UUID.randomUUID();
		long firstId = appendBlock(store, firstEvent, now);
		assertThat(appendBlock(store, firstEvent, now)).isEqualTo(firstId);
		long secondId = appendBlock(store, UUID.randomUUID(), now.plusSeconds(1));
		assertThat(secondId).isGreaterThan(firstId);
		assertThat(store.routePending(100, now.plusSeconds(2))).isEqualTo(2);
		assertThat(count("universal_webhook_delivery")).isEqualTo(2);

		List<ClaimedDelivery> firstClaim = store.claimAvailable(
				"worker-a", now.plusSeconds(3), Duration.ofMinutes(2), 10);
		assertThat(firstClaim).singleElement().extracting(ClaimedDelivery::eventId).isEqualTo(firstEvent);
		DurableUniversalWebhookStore restarted = new DurableUniversalWebhookStore(jdbc);
		assertThat(restarted.claimAvailable(
				"worker-b", now.plusSeconds(4), Duration.ofMinutes(2), 10)).isEmpty();

		assertThat(store.markDelivered(firstClaim.getFirst().deliveryId(), "worker-a", 200, now.plusSeconds(5))).isTrue();
		ClaimedDelivery second = restarted.claimAvailable(
				"worker-b", now.plusSeconds(6), Duration.ofMinutes(2), 10).getFirst();
		Instant retryAt = now.plusSeconds(30);
		assertThat(restarted.markRetry(
				second.deliveryId(), "worker-b", 503, "temporary", retryAt, now.plusSeconds(7))).isTrue();
		assertThat(restarted.claimAvailable(
				"worker-c", now.plusSeconds(20), Duration.ofMinutes(2), 10)).isEmpty();
		ClaimedDelivery retried = restarted.claimAvailable(
				"worker-c", retryAt, Duration.ofMinutes(2), 10).getFirst();
		assertThat(retried.attempt()).isEqualTo(2);
		assertThat(restarted.markDead(
				retried.deliveryId(), "worker-c", 400, "permanent", retryAt.plusSeconds(1))).isTrue();

		UUID journalEpoch = UUID.randomUUID();
		long secondBoundary = restarted.sourceCursor(WebhookType.BLOCKCHAIN);
		UUID secondDestination = insertDestination(
				102L, WebhookType.BLOCKCHAIN, "second", secondBoundary, journalEpoch, 2L);
		restarted.append(UUID.randomUUID(), WebhookType.BLOCKCHAIN, WebhookEventType.NEW_BLOCK,
				null, "{\"type\":\"NEW_BLOCK\"}", null, null, null, retryAt.plusSeconds(3),
				journalEpoch, 0, 1L, null);
		UUID thirdEvent = UUID.randomUUID();
		restarted.append(thirdEvent, WebhookType.BLOCKCHAIN, WebhookEventType.NEW_BLOCK,
				null, "{\"type\":\"NEW_BLOCK\"}", null, null, null, retryAt.plusSeconds(3),
				journalEpoch, 0, 3L, null);
		restarted.routePending(100, retryAt.plusSeconds(4));
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM universal_webhook_delivery
				WHERE destination_id = ?
				""", Long.class, secondDestination)).isOne();
		assertThat(jdbc.queryForObject("""
				SELECT event.event_id
				FROM universal_webhook_delivery delivery
				JOIN universal_webhook_source_event event ON event.id = delivery.source_event_id
				WHERE delivery.destination_id = ?
				""", UUID.class, secondDestination)).isEqualTo(thirdEvent);

		Address watched = Address.fromHexString("0x1111111111111111111111111111111111111111");
		UUID oldAddressEvent = UUID.randomUUID();
		restarted.append(
				oldAddressEvent, WebhookType.BLOCKCHAIN, WebhookEventType.ADDRESS_ACTIVITY,
				WebhookTxStatus.PENDING, "{\"type\":\"ADDRESS_ACTIVITY\"}", watched, null, null,
				retryAt.plusSeconds(5));
		insertAddressRule(firstDestination, watched, restarted.sourceCursor(WebhookType.BLOCKCHAIN));
		restarted.routePending(100, retryAt.plusSeconds(6));
		UUID newAddressEvent = UUID.randomUUID();
		restarted.append(
				newAddressEvent, WebhookType.BLOCKCHAIN, WebhookEventType.ADDRESS_ACTIVITY,
				WebhookTxStatus.PENDING, "{\"type\":\"ADDRESS_ACTIVITY\"}", watched, null, null,
				retryAt.plusSeconds(7));
		restarted.routePending(100, retryAt.plusSeconds(8));
		assertThat(jdbc.queryForList("""
				SELECT event.event_id
				FROM universal_webhook_delivery delivery
				JOIN universal_webhook_source_event event ON event.id = delivery.source_event_id
				WHERE delivery.destination_id = ? AND event.event_type = ?
				""", UUID.class, firstDestination, WebhookEventType.ADDRESS_ACTIVITY.getCode()))
				.containsExactly(newAddressEvent);
	}

	@Test
	void subscribePathPersistsPerRuleActivationBoundaryWithCorrectBindTypes() {
		UUID destinationId = insertDestination(103L, WebhookType.BLOCKCHAIN, "subscribe-path");
		jdbc.update("DELETE FROM webhook_event WHERE webhook_id = ?", destinationId);
		UUID canonicalEpoch = UUID.randomUUID();
		UUID mempoolEpoch = UUID.randomUUID();
		WebhookCoreRepository webhookRepository = mock(WebhookCoreRepository.class);
		WebhookEventCoreRepository eventRepository = mock(WebhookEventCoreRepository.class);
		Webhook webhook = mock(Webhook.class);
		ApiKey apiKey = mock(ApiKey.class);
		when(apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)).thenReturn(true);
		when(webhook.getId()).thenReturn(destinationId);
		when(webhook.getType()).thenReturn(WebhookType.BLOCKCHAIN);
		when(webhook.getCreatedByApiKey()).thenReturn(apiKey);
		when(webhookRepository.findById(destinationId)).thenReturn(java.util.Optional.of(webhook));
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(
				new LifecycleJournalHead(LifecycleJournalStream.CANONICAL, canonicalEpoch, 30L, 1L, 10L, Hash.ZERO));
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(
				new LifecycleJournalHead(LifecycleJournalStream.MEMPOOL, mempoolEpoch, 40L, 1L, 10L, Hash.ZERO));
		WebhookCoreService core = new WebhookCoreService(
				webhookRepository, eventRepository, jdbc, mock(ApplicationEventPublisher.class));
		DurableUniversalWebhookStore store = new DurableUniversalWebhookStore(jdbc);
		long expectedSourceBoundary = store.sourceCursor(WebhookType.BLOCKCHAIN);
		WebhookEventService service = new WebhookEventService(core, store, journal, mock(ChainQuery.class));

		service.subscribe(destinationId, apiKey, List.of(
				new WebhookEventDtoV1(WebhookEventType.NEW_BLOCK, null, null)));

		var row = jdbc.queryForMap("SELECT * FROM webhook_event WHERE webhook_id = ?", destinationId);
		assertThat(row.get("universal_active_after_source_sequence")).isEqualTo(expectedSourceBoundary);
		assertThat(row.get("universal_canonical_epoch")).isEqualTo(canonicalEpoch);
		assertThat(row.get("universal_canonical_after_sequence")).isEqualTo(30L);
		assertThat(row.get("universal_mempool_epoch")).isEqualTo(mempoolEpoch);
		assertThat(row.get("universal_mempool_after_sequence")).isEqualTo(40L);
	}

	@Test
	void concurrentConnectionsAllocateMonotonicPerSourceSequences() throws Exception {
		DurableUniversalWebhookStore first = new DurableUniversalWebhookStore(jdbc);
		DurableUniversalWebhookStore second = new DurableUniversalWebhookStore(jdbc);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<Long> firstInsert = executor.submit(() -> {
				start.await();
				return transactions.execute(status -> appendBlock(first, UUID.randomUUID(), Instant.now()));
			});
			Future<Long> secondInsert = executor.submit(() -> {
				start.await();
				return transactions.execute(status -> appendBlock(second, UUID.randomUUID(), Instant.now()));
			});
			start.countDown();
			firstInsert.get();
			secondInsert.get();
		}

		assertThat(jdbc.queryForList("""
				SELECT source_sequence
				FROM universal_webhook_source_event
				WHERE source = ?
				ORDER BY source_sequence
				""", Long.class, WebhookType.BLOCKCHAIN.getCode())).containsExactly(1L, 2L);
		assertThat(new DurableUniversalWebhookStore(jdbc).sourceCursor(WebhookType.BLOCKCHAIN)).isEqualTo(2L);
	}

	@Test
	void floorRecoveryUsesCompareAndSetAndNeverMovesCursorBackwards() {
		UUID epoch = UUID.randomUUID();
		jdbc.update("""
				UPDATE universal_webhook_journal_cursor
				SET journal_epoch = ?, last_sequence = 3, updated_at = now()
				WHERE consumer_source = ? AND journal_stream = ?
				""", epoch, WebhookType.BLOCKCHAIN.getCode(), LifecycleJournalStream.CANONICAL.code());
		DurableUniversalWebhookStore store = new DurableUniversalWebhookStore(jdbc);

		assertThat(store.recoverJournalFloor(
				WebhookType.BLOCKCHAIN, LifecycleJournalStream.CANONICAL.code(),
				epoch, 2L, 9L, Instant.now())).isFalse();
		assertThat(store.recoverJournalFloor(
				WebhookType.BLOCKCHAIN, LifecycleJournalStream.CANONICAL.code(),
				epoch, 3L, 9L, Instant.now())).isTrue();
		assertThat(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.CANONICAL.code()))
				.isEqualTo(new JournalCursor(epoch, 9L));
	}

	@Test
	void concurrentRoutersPreserveSourceSequenceWhenSurrogateIdsAreReversed() throws Exception {
		UUID destinationId = insertDestination(104L, WebhookType.BLOCKCHAIN, "router-order");
		UUID firstEvent = UUID.randomUUID();
		UUID secondEvent = UUID.randomUUID();
		insertSourceEvent(-100L, firstEvent, 1L);
		insertSourceEvent(-200L, secondEvent, 2L);
		DurableUniversalWebhookStore firstRouter = new DurableUniversalWebhookStore(jdbc);
		DurableUniversalWebhookStore secondRouter = new DurableUniversalWebhookStore(jdbc);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<Integer> first = executor.submit(() -> {
				start.await();
				return transactions.execute(status -> firstRouter.routePending(10, Instant.now()));
			});
			Future<Integer> second = executor.submit(() -> {
				start.await();
				return transactions.execute(status -> secondRouter.routePending(10, Instant.now()));
			});
			start.countDown();
			first.get();
			second.get();
		}
		transactions.execute(status -> firstRouter.routePending(10, Instant.now()));

		ClaimedDelivery claimed = firstRouter.claimAvailable(
				"worker", Instant.now().plusSeconds(1), Duration.ofMinutes(1), 10).getFirst();
		assertThat(claimed.eventId()).isEqualTo(firstEvent);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM universal_webhook_delivery WHERE destination_id = ?",
				Long.class, destinationId)).isEqualTo(2L);
	}

	@Test
	void routerProcessesThousandEventPrefixInFourBatches() {
		insertDestination(105L, WebhookType.BLOCKCHAIN, "router-throughput");
		jdbc.update("""
				INSERT INTO universal_webhook_source_event
				(event_id, source, source_sequence, event_type, payload, occurred_at, created_at)
				SELECT md5(sequence::text)::uuid, ?, sequence, ?,
				       '{"type":"NEW_BLOCK"}', now(), now()
				FROM generate_series(1, 1000) AS sequence
				""", WebhookType.BLOCKCHAIN.getCode(), WebhookEventType.NEW_BLOCK.getCode());
		DurableUniversalWebhookStore store = new DurableUniversalWebhookStore(jdbc);

		int routed = 0;
		for (int batch = 0; batch < 4; batch++) {
			routed += store.routePending(256, Instant.now());
		}

		assertThat(routed).isEqualTo(1000);
		assertThat(store.routePending(256, Instant.now())).isZero();
		assertThat(count("universal_webhook_delivery")).isEqualTo(1000);
	}

	@Test
	void explorerHeightBoundarySkipsFullReindexAndAllowsNextSyncedBlock() {
		UUID destinationId = insertDestination(106L, WebhookType.EXPLORER, "explorer-height");
		jdbc.update(
				"UPDATE webhook_event SET universal_explorer_after_height = ? WHERE webhook_id = ?",
				1_000_000L, destinationId);
		DurableUniversalWebhookStore store = new DurableUniversalWebhookStore(jdbc);
		store.append(UUID.randomUUID(), WebhookType.EXPLORER, WebhookEventType.NEW_BLOCK,
				null, "{\"type\":\"NEW_BLOCK\"}", null, null, null, Instant.now(),
				null, null, null, 0L);
		store.append(UUID.randomUUID(), WebhookType.EXPLORER, WebhookEventType.NEW_BLOCK,
				null, "{\"type\":\"NEW_BLOCK\"}", null, null, null, Instant.now(),
				null, null, null, 1_000_000L);
		UUID nextBlock = UUID.randomUUID();
		store.append(nextBlock, WebhookType.EXPLORER, WebhookEventType.NEW_BLOCK,
				null, "{\"type\":\"NEW_BLOCK\"}", null, null, null, Instant.now(),
				null, null, null, 1_000_001L);

		while (store.routePending(256, Instant.now()) > 0) {
		}

		assertThat(jdbc.queryForList("""
				SELECT event.event_id
				FROM universal_webhook_delivery delivery
				JOIN universal_webhook_source_event event ON event.id = delivery.source_event_id
				WHERE delivery.destination_id = ?
				""", UUID.class, destinationId)).containsExactly(nextBlock);
	}

	private long appendBlock(DurableUniversalWebhookStore store, UUID eventId, Instant occurredAt) {
		return store.append(eventId, WebhookType.BLOCKCHAIN, WebhookEventType.NEW_BLOCK,
				null, "{\"type\":\"NEW_BLOCK\"}", null, null, null, occurredAt);
	}

	private UUID insertDestination(long apiKeyId, WebhookType type, String suffix) {
		return insertDestination(apiKeyId, type, suffix, 0L, null, 0L);
	}

	private UUID insertDestination(
			long apiKeyId, WebhookType type, String suffix,
			long activeAfterEventId, UUID canonicalEpoch, long canonicalSequence) {
		byte[] secret = new byte[32];
		jdbc.update("""
				INSERT INTO api_key
				(id, created_at, enabled, key_prefix, label, secret_key, webhook_secret_key, version)
				VALUES (?, ?, TRUE, ?, ?, ?, ?, 0)
				""", apiKeyId, Timestamp.from(Instant.now()), "key-" + suffix, "key-" + suffix, secret, secret);
		UUID destinationId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO webhook
				(id, created_at, enabled, label, type, url, version, created_by_api_key_id)
				VALUES (?, ?, TRUE, ?, ?, ?, 0, ?)
				""", destinationId, Timestamp.from(Instant.now()), "hook-" + suffix,
				type.getCode(), "https://example.invalid/" + suffix, apiKeyId);
		jdbc.update("""
				INSERT INTO webhook_event
				(created_at, type, webhook_id, universal_active_after_source_sequence,
				 universal_canonical_epoch, universal_canonical_after_sequence)
				VALUES (?, ?, ?, ?, ?, ?)
				""", Timestamp.from(Instant.now()), WebhookEventType.NEW_BLOCK.getCode(), destinationId,
				activeAfterEventId, canonicalEpoch, canonicalSequence);
		return destinationId;
	}

	private void insertAddressRule(UUID destinationId, Address address, long activeAfterEventId) {
		jdbc.update("""
				INSERT INTO webhook_event
				(created_at, type, webhook_id, address_filter, universal_active_after_source_sequence)
				VALUES (?, ?, ?, ?, ?)
				""", Timestamp.from(Instant.now()), WebhookEventType.ADDRESS_ACTIVITY.getCode(),
				destinationId, address.toArray(), activeAfterEventId);
	}

	private void insertSourceEvent(long id, UUID eventId, long sourceSequence) {
		jdbc.update("""
				INSERT INTO universal_webhook_source_event
				(id, event_id, source, source_sequence, event_type, payload, occurred_at, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", id, eventId, WebhookType.BLOCKCHAIN.getCode(), sourceSequence,
				WebhookEventType.NEW_BLOCK.getCode(), "{\"type\":\"NEW_BLOCK\"}",
				Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
	}
}
