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
package global.goldenera.node.shared.services.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.properties.ApiKeyAuthenticationCacheProperties;
import global.goldenera.node.shared.repositories.ApiKeyCoreRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class ApiKeyAuthenticationCachePostgresConcurrencyTest {

	private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
	private static final String PREFIX = "sk_12345678901";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("goldenera")
			.withUsername("goldenera")
			.withPassword("goldenera");

	private static HikariDataSource dataSource;
	private static EntityManagerFactory entityManagerFactory;
	private static EntityManager entityManager;
	private static TransactionTemplate transactions;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void initializeJpa() throws Exception {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(MASTER_CHANGELOG, resources, database)) {
				liquibase.update();
			}
		}

		HikariConfig hikari = new HikariConfig();
		hikari.setJdbcUrl(POSTGRES.getJdbcUrl());
		hikari.setUsername(POSTGRES.getUsername());
		hikari.setPassword(POSTGRES.getPassword());
		hikari.setMaximumPoolSize(1);
		hikari.setMinimumIdle(1);
		hikari.setConnectionTimeout(5_000);
		dataSource = new HikariDataSource(hikari);
		jdbc = new JdbcTemplate(dataSource);

		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(dataSource);
		factory.setPackagesToScan(
				"global.goldenera.node.shared.entities",
				"global.goldenera.node.shared.converters");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of(
				"hibernate.hbm2ddl.auto", "validate",
				"hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
		factory.afterPropertiesSet();
		entityManagerFactory = factory.getObject();
		entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
		transactions = new TransactionTemplate(new JpaTransactionManager(entityManagerFactory));
	}

	@AfterAll
	static void closeJpa() {
		if (entityManagerFactory != null) {
			entityManagerFactory.close();
		}
		if (dataSource != null) {
			dataSource.close();
		}
	}

	@BeforeEach
	void resetApiKey() {
		jdbc.execute("TRUNCATE api_key CASCADE");
		jdbc.update("UPDATE api_key_auth_epoch SET epoch = 0 WHERE singleton = TRUE");
		jdbc.update("""
				INSERT INTO api_key(
				  id, version, label, description, key_prefix, secret_key, webhook_secret_key,
				  enabled, max_webhooks, expires_at, created_at)
				VALUES (1, 0, 'test', NULL, ?, decode('010203', 'hex'), decode('040506', 'hex'),
				  TRUE, 10, NULL, now())
				""", PREFIX);
		jdbc.update("INSERT INTO api_key_permission(api_key_id, permission) VALUES (1, 8)");
	}

	@Test
	void oneConnectionPoolCannotDeadlockAuthenticationAgainstCommittingMutation() throws Exception {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		AtomicReference<CountDownLatch> epochLookupStarted = new AtomicReference<>(new CountDownLatch(0));
		when(repository.findAuthenticationEpoch()).thenAnswer(ignored -> {
			epochLookupStarted.get().countDown();
			return transactions.execute(status -> ((Number) entityManager.createNativeQuery(
					"SELECT epoch FROM api_key_auth_epoch WHERE singleton = TRUE").getSingleResult()).longValue());
		});
		when(repository.findByKeyPrefix(anyString())).thenAnswer(invocation -> transactions.execute(status ->
				entityManager.createQuery("SELECT a FROM ApiKey a WHERE a.keyPrefix = :prefix", ApiKey.class)
						.setParameter("prefix", invocation.getArgument(0))
						.getResultStream()
						.findFirst()));
		ApiKeyAuthenticationCacheProperties properties = new ApiKeyAuthenticationCacheProperties();
		properties.setTtl(Duration.ofMinutes(1));
		ApiKeyAuthenticationCache cache = new ApiKeyAuthenticationCache(
				repository, properties, new SimpleMeterRegistry());
		assertThat(cache.withAuthenticationKey(PREFIX, Optional::orElseThrow).isEnabled()).isTrue();

		CountDownLatch writerHasOnlyConnection = new CountDownLatch(1);
		CountDownLatch allowMutationCallback = new CountDownLatch(1);
		CountDownLatch mutationCallbackReturned = new CountDownLatch(1);
		CountDownLatch blockedAuthEpochLookup = new CountDownLatch(1);
		epochLookupStarted.set(blockedAuthEpochLookup);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> writer = executor.submit(() -> transactions.executeWithoutResult(status -> {
				entityManager.createNativeQuery("UPDATE api_key SET enabled = FALSE WHERE id = 1").executeUpdate();
				writerHasOnlyConnection.countDown();
				await(allowMutationCallback);
				cache.executeMutation(() -> null);
				mutationCallbackReturned.countDown();
			}));
			assertThat(writerHasOnlyConnection.await(5, TimeUnit.SECONDS)).isTrue();

			Future<Boolean> authentication = executor.submit(() ->
					cache.withAuthenticationKey(PREFIX, value -> value.orElseThrow().isEnabled()));
			assertThat(blockedAuthEpochLookup.await(5, TimeUnit.SECONDS)).isTrue();
			allowMutationCallback.countDown();

			assertThat(mutationCallbackReturned.await(2, TimeUnit.SECONDS)).isTrue();
			writer.get(5, TimeUnit.SECONDS);
			assertThat(authentication.get(5, TimeUnit.SECONDS)).isFalse();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for test coordination");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for test coordination", exception);
		}
	}
}
