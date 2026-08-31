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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.enums.BridgeDeliveryState;
import global.goldenera.node.bridge.enums.BridgeSubscriptionStatus;
import global.goldenera.node.bridge.services.BridgeDeliveryService.DeliveryFilter;
import global.goldenera.node.bridge.services.BridgeDeliveryService;
import global.goldenera.node.bridge.services.BridgeSubscriptionService;
import global.goldenera.node.bridge.webhook.BridgeLifecycleProjectionCursorStore.Cursor;
import global.goldenera.node.bridge.webhook.BridgeLifecycleProjectionCursorStore;
import global.goldenera.node.bridge.webhook.BridgeDeliveryStore;
import global.goldenera.node.bridge.webhook.BridgeDeliveryWakeup;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.hypersistence.utils.spring.repository.BaseJpaRepositoryImpl;
import jakarta.persistence.EntityManagerFactory;
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
	private static DataSourceTransactionManager transactionManager;
	private static BridgeSubscriptionRepositoryImpl repository;
    private static AnnotationConfigApplicationContext queryContext;

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
		transactionManager = new DataSourceTransactionManager(dataSource);
		transactions = new TransactionTemplate(transactionManager);
		repository = new BridgeSubscriptionRepositoryImpl(jdbc);
        queryContext = new AnnotationConfigApplicationContext(QueryConfiguration.class);
	}

    @AfterAll
    static void closeContext() {
        if (queryContext != null) {
            queryContext.close();
        }
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
    void batchDisableIsScopedAndPreservesDestinationsWithRemainingSubscriptions() {
        UUID destination = insertDestination(710L, "https://consumer.example/own");
        UUID otherDestination = insertDestination(711L, "https://consumer.example/other");
        UUID first = insertSubscription(destination, 1);
        UUID second = insertSubscription(destination, 2);
        UUID other = insertSubscription(otherDestination, 1);
        assertThat(transactions.<Long>execute(status -> repository.disableSubscriptions(
                710L, Network.MAINNET, List.of(first, other, UUID.randomUUID(), first)))).isEqualTo(1L);
        assertThat(enabled(first)).isFalse();
        assertThat(enabled(second)).isTrue();
        assertThat(enabled(other)).isTrue();
        assertThat(jdbc.queryForObject("SELECT enabled FROM webhook WHERE id = ?", Boolean.class, destination)).isTrue();
        assertThat(transactions.<Long>execute(status -> repository.disableSubscriptions(
                710L, Network.MAINNET, List.of()))).isZero();
        assertThat(enabled(second)).isTrue();
        assertThat(transactions.<Long>execute(status -> repository.disableSubscriptions(
                710L, Network.MAINNET, null))).isEqualTo(1L);
        assertThat(enabled(second)).isFalse();
        assertThat(enabled(other)).isTrue();
        assertThat(jdbc.queryForObject("SELECT enabled FROM webhook WHERE id = ?", Boolean.class, destination)).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM webhook WHERE id = ?", Boolean.class, otherDestination)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bridge_subscription", Long.class)).isEqualTo(3L);
    }

    @Test
    void pagesSubscriptionsByOwnerAddressAndActivityWithStablePagination() {
        UUID destination = insertDestination(720L, "https://consumer.example/own");
        UUID otherDestination = insertDestination(721L, "https://consumer.example/other");
        UUID first = insertSubscription(destination, 1);
        UUID inactive = insertSubscription(destination, 2);
        UUID other = insertSubscription(otherDestination, 1);
        jdbc.update("UPDATE bridge_subscription SET enabled = FALSE WHERE id = ?", inactive);
        BridgeSubscriptionService service = queryContext.getBean(BridgeSubscriptionService.class);
        ApiKey key = apiKey(720L);
        assertThat(service.getPage(0, 10, null, null, null, key).getContent())
                .extracting(value -> value.getId()).containsExactly(first);
        assertThat(service.getPage(0, 10, null, null, BridgeSubscriptionStatus.INACTIVE, key).getContent())
                .extracting(value -> value.getId()).containsExactly(inactive);
        assertThat(service.getPage(0, 10, null, null, BridgeSubscriptionStatus.ALL, key).getTotalElements()).isEqualTo(2);
        assertThat(service.getPage(0, 10, null, Address.fromHexString(String.format("0x%040x", 2)),
                BridgeSubscriptionStatus.ALL, key).getContent()).extracting(value -> value.getId()).containsExactly(inactive);
        assertThat(service.getAdminPage(0, 10, null, null, null, null).getContent())
                .extracting(value -> value.getId()).containsExactlyInAnyOrder(first, other);
        assertThat(service.getAdminPage(0, 10, null, 720L, null, BridgeSubscriptionStatus.INACTIVE).getContent())
                .extracting(value -> value.getId()).containsExactly(inactive);
        var page = service.getAdminPage(0, 1, null, null, null, BridgeSubscriptionStatus.ALL);
        var next = service.getAdminPage(1, 1, null, null, null, BridgeSubscriptionStatus.ALL);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent().getFirst().getId()).isNotEqualTo(next.getContent().getFirst().getId());
        assertThat(page.getContent().getFirst().getDestination().getUrl()).startsWith("https://consumer.example/");
        assertThat(service.getAdminPage(0, 10, null, 999L, null, BridgeSubscriptionStatus.ALL)).isEmpty();
    }

	@Test
	void auditFiltersDeliveriesAndCannotExposeAnotherApiKeysPayload() {
        UUID destination = insertDestination(730L, "https://consumer.example/own");
        UUID otherDestination = insertDestination(731L, "https://consumer.example/other");
        UUID first = insertDelivery(destination, BridgeDeliveryState.DELIVERED, "own delivered");
        UUID retry = insertDelivery(destination, BridgeDeliveryState.RETRY, "own retry");
        UUID other = insertDelivery(otherDestination, BridgeDeliveryState.DEAD, "private other payload");
        BridgeDeliveryService service = queryContext.getBean(BridgeDeliveryService.class);
        DeliveryFilter all = new DeliveryFilter(null, null, null, null, null, null);
        ApiKey key = apiKey(730L);
        assertThat(service.getPage(0, 10, null, all, key).getContent())
                .extracting(value -> value.getDeliveryId()).containsExactlyInAnyOrder(first, retry);
        assertThat(service.getPage(0, 10, null,
                new DeliveryFilter(otherDestination, null, null, null, null, null), key)).isEmpty();
        assertThat(service.getPage(0, 10, null,
                new DeliveryFilter(null, other, null, null, null, null), key)).isEmpty();
        var filtered = service.getPage(0, 10, null,
                new DeliveryFilter(destination, retry, null, BridgeDeliveryState.RETRY,
                        Instant.now().minusSeconds(60), Instant.now().plusSeconds(60)), key);
        assertThat(filtered.getContent()).extracting(value -> value.getDeliveryId()).containsExactly(retry);
        assertThat(filtered.getContent().getFirst().getBody()).isEqualTo("own retry");
        UUID eventId = filtered.getContent().getFirst().getEventId();
        assertThat(service.getPage(0, 10, null,
                new DeliveryFilter(null, null, eventId, null, null, null), key).getTotalElements()).isOne();
        assertThat(service.getPage(0, 10, null,
                new DeliveryFilter(null, null, null, null, Instant.now().plusSeconds(60), null), key)).isEmpty();
        assertThat(service.getAdminPage(0, 1, null, all, null).getTotalElements()).isEqualTo(3);
        assertThat(service.getAdminPage(0, 10, null, all, 731L).getContent())
                .extracting(value -> value.getDeliveryId()).containsExactly(other);
        assertThatThrownBy(() -> service.getPage(0, 10, null,
                new DeliveryFilter(null, null, null, null, Instant.now(), Instant.EPOCH), key))
                .isInstanceOf(GEValidationException.class);
        assertThatThrownBy(() -> service.getPage(0, 101, null, all, key)).isInstanceOf(GEValidationException.class);
        assertThatThrownBy(() -> service.getPage(0, 10, null, all, mock(ApiKey.class)))
                .isInstanceOf(GEValidationException.class);
    }

    private UUID insertDelivery(UUID destination, BridgeDeliveryState state, String body) {
        var store = new BridgeDeliveryRepositoryImpl(jdbc);
        var delivery = transactions.execute(status -> {
            var reservation = store.reserve(UUID.randomUUID(), destination, Instant.now()).orElseThrow();
            store.setBodyOnce(reservation.id(), body, Instant.now());
            return reservation;
        });
        jdbc.update("UPDATE bridge_delivery SET state = ? WHERE id = ?", state.getCode(), delivery.id());
        return delivery.deliveryId();
    }

    private ApiKey apiKey(long id) {
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(id);
        when(key.hasPermission(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS)).thenReturn(true);
        return key;
    }

    private boolean enabled(UUID id) {
        return jdbc.queryForObject("SELECT enabled FROM bridge_subscription WHERE id = ?", Boolean.class, id);
    }

    private UUID insertSubscription(UUID destination, int address) {
        UUID epoch = UUID.randomUUID();
        return transactions.execute(status -> repository.upsertEnabled(UUID.randomUUID(), destination, Network.MAINNET,
                Address.fromHexString(String.format("0x%040x", address)), epoch, 0L, epoch, 0L, 0L, Instant.now()));
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

	@Test
	void jdbcDeliveryStorePreservesOrderingLeasesAndRetryEligibility() {
		UUID destination = insertDestination(740L, "https://consumer.example/delivery");
		UUID firstId = insertDelivery(destination, BridgeDeliveryState.READY, "first");
		UUID secondId = insertDelivery(destination, BridgeDeliveryState.READY, "second");
		BridgeDeliveryStore store = new BridgeDeliveryStore(jdbc);
		Instant now = Instant.now();

		var firstClaim = transactions.execute(status ->
				store.claimAvailable("worker-a", now, Duration.ofMinutes(2), 8));

		assertThat(firstClaim).singleElement().satisfies(delivery -> {
			assertThat(delivery.getDeliveryId()).isEqualTo(firstId);
			assertThat(delivery.getAttempt()).isEqualTo(1);
			assertThat(delivery.getBody()).isEqualTo("first");
			assertThat(delivery.getUrl()).isEqualTo("https://consumer.example/delivery");
		});
		List<BridgeDeliveryStore.ClaimedDelivery> blockedSecondClaim = transactions.execute(status ->
				store.claimAvailable("worker-b", now, Duration.ofMinutes(2), 8));
		assertThat(blockedSecondClaim).isEmpty();
		Instant retryAt = now.plusSeconds(5);
		Boolean markedForRetry = transactions.execute(status -> store.markRetry(
				firstId, "worker-a", 1, 503, "retry", retryAt, now));
		assertThat(markedForRetry).isTrue();
		List<BridgeDeliveryStore.ClaimedDelivery> prematureRetryClaim = transactions.execute(status ->
				store.claimAvailable("worker-b", now.plusSeconds(4), Duration.ofMinutes(2), 8));
		assertThat(prematureRetryClaim).isEmpty();

		var retryClaim = transactions.execute(status ->
				store.claimAvailable("worker-b", retryAt, Duration.ofMinutes(2), 8));
		assertThat(retryClaim).singleElement().satisfies(delivery -> {
			assertThat(delivery.getDeliveryId()).isEqualTo(firstId);
			assertThat(delivery.getAttempt()).isEqualTo(2);
		});
		Boolean wrongOwnerDelivered = transactions.execute(status -> store.markDelivered(
				firstId, "wrong-worker", 2, 204, retryAt));
		assertThat(wrongOwnerDelivered).isFalse();
		Boolean delivered = transactions.execute(status -> store.markDelivered(
				firstId, "worker-b", 2, 204, retryAt));
		assertThat(delivered).isTrue();

		List<BridgeDeliveryStore.ClaimedDelivery> secondClaim = transactions.execute(status ->
				store.claimAvailable("worker-c", retryAt, Duration.ofMinutes(2), 8));
		assertThat(secondClaim)
				.singleElement()
				.extracting(delivery -> delivery.getDeliveryId())
				.isEqualTo(secondId);
		Boolean deadLettered = transactions.execute(status -> store.markDead(
				secondId, "worker-c", 1, 400, "x".repeat(3_000), retryAt));
		assertThat(deadLettered).isTrue();
		assertThat(jdbc.queryForObject(
				"SELECT length(last_error) FROM bridge_delivery WHERE delivery_id = ?",
				Integer.class,
				secondId)).isEqualTo(2_048);
	}

	@Test
	void bridgeLeaseGenerationFencesSameOwnerReclaimAndRestartKeepsFifo() {
		UUID destination = insertDestination(744L, "https://consumer.example/fencing");
		UUID firstId = insertDelivery(destination, BridgeDeliveryState.READY, "first");
		UUID secondId = insertDelivery(destination, BridgeDeliveryState.READY, "second");
		BridgeDeliveryStore store = new BridgeDeliveryStore(jdbc);
		Instant now = Instant.now();
		Instant reclaimedAt = now.plus(Duration.ofMinutes(2)).plusMillis(1);
		BridgeDeliveryStore.ClaimedDelivery stale = transactions.execute(status ->
				store.claimAvailable("stable-worker", now, Duration.ofMinutes(2), 8)).getFirst();
		BridgeDeliveryStore.ClaimedDelivery reclaimed = transactions.execute(status ->
				store.claimAvailable("stable-worker", reclaimedAt, Duration.ofMinutes(2), 8)).getFirst();

		assertThat(stale.getDeliveryId()).isEqualTo(firstId);
		assertThat(reclaimed.getDeliveryId()).isEqualTo(firstId);
		assertThat(reclaimed.getAttempt()).isEqualTo(stale.getAttempt() + 1);
		Boolean staleCompletionAccepted = transactions.execute(status -> store.markDelivered(
				firstId, "stable-worker", stale.getAttempt(), 204, reclaimedAt.plusSeconds(1)));
		assertThat(staleCompletionAccepted).isFalse();
		Integer releasedLeases = transactions.execute(status -> store.releaseLeases(
				"stable-worker", reclaimedAt.plusSeconds(2)));
		assertThat(releasedLeases).isEqualTo(1);

		BridgeDeliveryStore.ClaimedDelivery restarted = transactions.execute(status ->
				store.claimAvailable(
						"replacement-worker", reclaimedAt.plusSeconds(3), Duration.ofMinutes(2), 8)).getFirst();
		assertThat(restarted.getDeliveryId()).isEqualTo(firstId);
		assertThat(restarted.getAttempt()).isEqualTo(reclaimed.getAttempt() + 1);
		Boolean restartedCompletionAccepted = transactions.execute(status -> store.markDelivered(
				firstId, "replacement-worker", restarted.getAttempt(), 204, reclaimedAt.plusSeconds(4)));
		assertThat(restartedCompletionAccepted).isTrue();
		BridgeDeliveryStore.ClaimedDelivery next = transactions.execute(status ->
				store.claimAvailable(
						"replacement-worker", reclaimedAt.plusSeconds(5), Duration.ofMinutes(2), 8)).getFirst();
		assertThat(next.getDeliveryId()).isEqualTo(secondId);
	}

	@Test
	void completedBridgeOutboxRowSignalsDeliveryOnlyAfterCommit() {
		UUID destination = insertDestination(741L, "https://consumer.example/wakeup");
		BridgeDeliveryWakeup wakeup = mock(BridgeDeliveryWakeup.class);
		BridgeDeliveryRepositoryImpl outbox = new BridgeDeliveryRepositoryImpl(jdbc, wakeup);

		transactions.executeWithoutResult(status -> {
			var reservation = outbox.reserve(UUID.randomUUID(), destination, Instant.now()).orElseThrow();
			assertThat(outbox.setBodyOnce(reservation.id(), "payload", Instant.now())).isTrue();
			verifyNoInteractions(wakeup);
		});

		verify(wakeup).wake();
	}

	@Test
	void rolledBackBridgeOutboxRowDoesNotSignalDelivery() {
		UUID destination = insertDestination(742L, "https://consumer.example/rollback");
		BridgeDeliveryWakeup wakeup = mock(BridgeDeliveryWakeup.class);
		BridgeDeliveryRepositoryImpl outbox = new BridgeDeliveryRepositoryImpl(jdbc, wakeup);

		transactions.executeWithoutResult(status -> {
			var reservation = outbox.reserve(UUID.randomUUID(), destination, Instant.now()).orElseThrow();
			assertThat(outbox.setBodyOnce(reservation.id(), "payload", Instant.now())).isTrue();
			status.setRollbackOnly();
		});

		verifyNoInteractions(wakeup);
	}

	@Test
	void requiresNewOutboxCommitSignalsEvenWhenSuspendedOuterTransactionRollsBack() {
		UUID destination = insertDestination(743L, "https://consumer.example/requires-new");
		BridgeDeliveryWakeup wakeup = mock(BridgeDeliveryWakeup.class);
		BridgeDeliveryRepositoryImpl outbox = new BridgeDeliveryRepositoryImpl(jdbc, wakeup);
		TransactionTemplate inner = new TransactionTemplate(transactionManager);
		inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		UUID eventId = UUID.randomUUID();

		transactions.executeWithoutResult(outerStatus -> {
			inner.executeWithoutResult(innerStatus -> {
				var reservation = outbox.reserve(eventId, destination, Instant.now()).orElseThrow();
				assertThat(outbox.setBodyOnce(reservation.id(), "payload", Instant.now())).isTrue();
				verifyNoInteractions(wakeup);
			});
			verify(wakeup).wake();
			outerStatus.setRollbackOnly();
		});

		verify(wakeup).wake();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM bridge_delivery WHERE event_id = ?", Long.class, eventId)).isOne();
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
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BridgeSubscriptionRepository.class,
            repositoryBaseClass = BaseJpaRepositoryImpl.class)
    @Import({ BridgeSubscriptionService.class, BridgeDeliveryService.class })
    static class QueryConfiguration {
        @Bean
        DataSource dataSource() {
            return jdbc.getDataSource();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return jdbc;
        }

        @Bean
        GeneralProperties generalProperties() {
            GeneralProperties properties = new GeneralProperties();
            properties.setNetwork(Network.MAINNET);
            return properties;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan("global.goldenera.node.bridge.entities", "global.goldenera.node.shared.entities");
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

}
