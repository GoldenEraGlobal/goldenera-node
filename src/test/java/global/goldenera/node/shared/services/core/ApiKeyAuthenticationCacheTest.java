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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.properties.ApiKeyAuthenticationCacheProperties;
import global.goldenera.node.shared.repositories.ApiKeyCoreRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ApiKeyAuthenticationCacheTest {

	@AfterEach
	void cleanTransactionState() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void cachesImmutableSnapshotsAndReturnsFreshPrincipals() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey source = apiKey(true, null, ApiKeyPermission.READ_BLOCK_HEADER);
		when(repository.findByKeyPrefix(source.getKeyPrefix())).thenReturn(Optional.of(source));
		ApiKeyAuthenticationCache cache = cache(repository, properties(), new AtomicLong());

		ApiKey first = lookup(cache, source.getKeyPrefix()).orElseThrow();
		source.setEnabled(false);
		source.setPermissions(Set.of(ApiKeyPermission.SUBMIT_MEMPOOL_TX));
		source.setSecretKey(Bytes.of(99));
		first.setPermissions(Set.of(ApiKeyPermission.READ_TX));
		first.getSecretKey().toArray()[0] = 42;

		ApiKey second = lookup(cache, source.getKeyPrefix()).orElseThrow();

		assertThat(second).isNotSameAs(first);
		assertThat(second.isEnabled()).isTrue();
		assertThat(second.getPermissions()).containsExactly(ApiKeyPermission.READ_BLOCK_HEADER);
		assertThat(second.getSecretKey()).isEqualTo(Bytes.of(1, 2, 3));
		verify(repository).findByKeyPrefix(source.getKeyPrefix());
	}

	@Test
	void disabledCacheAlwaysLoadsFromRepository() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey key = apiKey(true, null, ApiKeyPermission.READ_TX);
		when(repository.findByKeyPrefix(key.getKeyPrefix())).thenReturn(Optional.of(key));
		ApiKeyAuthenticationCacheProperties properties = properties();
		properties.setEnabled(false);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ApiKeyAuthenticationCache cache = new ApiKeyAuthenticationCache(repository, properties, registry,
				new AtomicLong()::get);

		lookup(cache, key.getKeyPrefix());
		lookup(cache, key.getKeyPrefix());

		verify(repository, times(2)).findByKeyPrefix(key.getKeyPrefix());
		verify(repository, never()).findAuthenticationEpoch();
		assertThat(registry.get("security.api_key.auth_cache.lookups")
				.tag("result", "disabled").counter().count()).isEqualTo(2);
	}

	@Test
	void positiveAndNegativeEntriesExpireIndependently() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey key = apiKey(true, null, ApiKeyPermission.READ_TX);
		AtomicReference<ApiKey> stored = new AtomicReference<>(key);
		when(repository.findByKeyPrefix(key.getKeyPrefix()))
				.thenAnswer(ignored -> Optional.ofNullable(stored.get()));
		ApiKeyAuthenticationCacheProperties properties = properties();
		properties.setTtl(Duration.ofSeconds(5));
		properties.setNegativeTtl(Duration.ofMillis(500));
		AtomicLong ticker = new AtomicLong();
		ApiKeyAuthenticationCache cache = cache(repository, properties, ticker);

		assertThat(lookup(cache, key.getKeyPrefix())).isPresent();
		stored.set(null);
		ticker.addAndGet(Duration.ofSeconds(5).toNanos());
		assertThat(lookup(cache, key.getKeyPrefix())).isEmpty();
		stored.set(key);
		ticker.addAndGet(Duration.ofMillis(500).toNanos());
		assertThat(lookup(cache, key.getKeyPrefix())).isPresent();

		verify(repository, times(3)).findByKeyPrefix(key.getKeyPrefix());
	}

	@Test
	void committedOuterTransactionInvalidatesOnceAfterNestedMutations() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey enabled = apiKey(true, null, ApiKeyPermission.READ_TX);
		Instant updatedExpiry = Instant.parse("2027-01-01T00:00:00Z");
		ApiKey disabled = apiKey(false, updatedExpiry, ApiKeyPermission.SUBMIT_MEMPOOL_TX);
		disabled.setSecretKey(Bytes.of(9));
		AtomicReference<ApiKey> stored = new AtomicReference<>(enabled);
		when(repository.findByKeyPrefix(enabled.getKeyPrefix()))
				.thenAnswer(ignored -> Optional.ofNullable(stored.get()));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ApiKeyAuthenticationCache cache = new ApiKeyAuthenticationCache(repository, properties(), registry,
				new AtomicLong()::get);
		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isTrue();

		beginTransaction();
		cache.executeMutation(() -> {
			stored.set(disabled);
			return null;
		});
		cache.executeMutation(() -> null);
		assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
		completeTransaction(true);

		ApiKey reloaded = lookup(cache, enabled.getKeyPrefix()).orElseThrow();
		assertThat(reloaded.isEnabled()).isFalse();
		assertThat(reloaded.getPermissions()).containsExactly(ApiKeyPermission.SUBMIT_MEMPOOL_TX);
		assertThat(reloaded.getSecretKey()).isEqualTo(Bytes.of(9));
		assertThat(reloaded.getExpiresAt()).isEqualTo(updatedExpiry);
		verify(repository, times(2)).findByKeyPrefix(enabled.getKeyPrefix());
		assertThat(registry.get("security.api_key.auth_cache.invalidations").counter().count()).isEqualTo(1);
	}

	@Test
	void rollbackDoesNotEvictCommittedSnapshotOrCacheUncommittedState() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey enabled = apiKey(true, null, ApiKeyPermission.READ_TX);
		ApiKey disabled = apiKey(false, null, ApiKeyPermission.SUBMIT_MEMPOOL_TX);
		AtomicReference<ApiKey> stored = new AtomicReference<>(enabled);
		when(repository.findByKeyPrefix(enabled.getKeyPrefix()))
				.thenAnswer(ignored -> Optional.ofNullable(stored.get()));
		ApiKeyAuthenticationCache cache = cache(repository, properties(), new AtomicLong());
		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isTrue();

		beginTransaction();
		cache.executeMutation(() -> {
			stored.set(disabled);
			return null;
		});
		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isFalse();
		stored.set(enabled);
		completeTransaction(false);

		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isTrue();
		verify(repository, times(2)).findByKeyPrefix(enabled.getKeyPrefix());
	}

	@Test
	void committedCreateInvalidatesNegativeEntry() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey created = apiKey(true, null, ApiKeyPermission.READ_TX);
		AtomicReference<ApiKey> stored = new AtomicReference<>();
		when(repository.findByKeyPrefix(created.getKeyPrefix()))
				.thenAnswer(ignored -> Optional.ofNullable(stored.get()));
		ApiKeyAuthenticationCache cache = cache(repository, properties(), new AtomicLong());
		assertThat(lookup(cache, created.getKeyPrefix())).isEmpty();
		assertThat(lookup(cache, created.getKeyPrefix())).isEmpty();

		inTransaction(true, () -> cache.executeMutation(() -> {
			stored.set(created);
			return null;
		}));

		assertThat(lookup(cache, created.getKeyPrefix())).isPresent();
		verify(repository, times(2)).findByKeyPrefix(created.getKeyPrefix());
	}

	@Test
	void sharedEpochInvalidatesSnapshotWithoutLocalEvent() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey enabled = apiKey(true, null, ApiKeyPermission.READ_TX);
		ApiKey disabled = apiKey(false, null, ApiKeyPermission.SUBMIT_MEMPOOL_TX);
		AtomicReference<ApiKey> stored = new AtomicReference<>(enabled);
		AtomicLong epoch = new AtomicLong(10);
		when(repository.findAuthenticationEpoch()).thenAnswer(ignored -> epoch.get());
		when(repository.findByKeyPrefix(enabled.getKeyPrefix()))
				.thenAnswer(ignored -> Optional.ofNullable(stored.get()));
		ApiKeyAuthenticationCache cache = cache(repository, properties(), new AtomicLong());
		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isTrue();

		stored.set(disabled);
		epoch.incrementAndGet();

		ApiKey refreshed = lookup(cache, enabled.getKeyPrefix()).orElseThrow();
		assertThat(refreshed.isEnabled()).isFalse();
		assertThat(refreshed.getPermissions()).containsExactly(ApiKeyPermission.SUBMIT_MEMPOOL_TX);

		verify(repository, times(2)).findByKeyPrefix(enabled.getKeyPrefix());
		verify(repository, times(2)).findAuthenticationEpoch();
	}

	@Test
	void transactionBypassHasItsOwnMetricAndDoesNotPopulateCache() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey enabled = apiKey(true, null, ApiKeyPermission.READ_TX);
		ApiKey disabled = apiKey(false, null, ApiKeyPermission.READ_TX);
		AtomicReference<ApiKey> stored = new AtomicReference<>(enabled);
		when(repository.findByKeyPrefix(enabled.getKeyPrefix()))
				.thenAnswer(ignored -> Optional.ofNullable(stored.get()));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ApiKeyAuthenticationCache cache = new ApiKeyAuthenticationCache(repository, properties(), registry,
				new AtomicLong()::get);

		beginTransaction();
		cache.executeMutation(() -> {
			stored.set(disabled);
			return null;
		});
		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isFalse();
		stored.set(enabled);
		completeTransaction(false);

		assertThat(registry.get("security.api_key.auth_cache.lookups")
				.tag("result", "transaction_bypass").counter().count()).isEqualTo(1);
		assertThat(registry.get("security.api_key.auth_cache.lookups")
				.tag("result", "disabled").counter().count()).isZero();
		verify(repository, never()).findAuthenticationEpoch();
	}

	@Test
	void requiresNewMutationInvalidatesIndependentlyOfOuterRollback() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey enabled = apiKey(true, null, ApiKeyPermission.READ_TX);
		ApiKey disabled = apiKey(false, null, ApiKeyPermission.READ_TX);
		AtomicReference<ApiKey> stored = new AtomicReference<>(enabled);
		when(repository.findByKeyPrefix(enabled.getKeyPrefix()))
				.thenAnswer(ignored -> Optional.ofNullable(stored.get()));
		ApiKeyAuthenticationCache cache = cache(repository, properties(), new AtomicLong());
		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isTrue();

		beginTransaction();
		cache.executeMutation(() -> null);
		TransactionSynchronization outer = TransactionSynchronizationManager.getSynchronizations().getFirst();
		outer.suspend();
		TransactionSynchronizationManager.clearSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(false);

		inTransaction(true, () -> cache.executeMutation(() -> {
			stored.set(disabled);
			return null;
		}));

		beginTransaction();
		TransactionSynchronizationManager.registerSynchronization(outer);
		outer.resume();
		completeTransaction(false);

		assertThat(lookup(cache, enabled.getKeyPrefix()).orElseThrow().isEnabled()).isFalse();
		verify(repository, times(2)).findByKeyPrefix(enabled.getKeyPrefix());
	}

	@Test
	void failedNonTransactionalMutationDoesNotEvict() {
		ApiKeyCoreRepository repository = mock(ApiKeyCoreRepository.class);
		ApiKey key = apiKey(true, null, ApiKeyPermission.READ_TX);
		when(repository.findByKeyPrefix(key.getKeyPrefix())).thenReturn(Optional.of(key));
		ApiKeyAuthenticationCache cache = cache(repository, properties(), new AtomicLong());
		lookup(cache, key.getKeyPrefix());

		assertThatThrownBy(() -> cache.executeMutation(() -> {
			throw new IllegalStateException("failed");
		})).isInstanceOf(IllegalStateException.class);
		lookup(cache, key.getKeyPrefix());

		verify(repository).findByKeyPrefix(key.getKeyPrefix());
	}

	private static ApiKeyAuthenticationCache cache(ApiKeyCoreRepository repository,
			ApiKeyAuthenticationCacheProperties properties,
			AtomicLong ticker) {
		return new ApiKeyAuthenticationCache(repository, properties, new SimpleMeterRegistry(), ticker::get);
	}

	private static ApiKeyAuthenticationCacheProperties properties() {
		return new ApiKeyAuthenticationCacheProperties();
	}

	private static Optional<ApiKey> lookup(ApiKeyAuthenticationCache cache, String prefix) {
		return cache.withAuthenticationKey(prefix, value -> value);
	}

	private static ApiKey apiKey(boolean enabled, Instant expiresAt, ApiKeyPermission permission) {
		ApiKey apiKey = new ApiKey();
		apiKey.setId(1L);
		apiKey.setVersion(1L);
		apiKey.setLabel("test-key");
		apiKey.setDescription("test");
		apiKey.setKeyPrefix("sk_12345678901");
		apiKey.setSecretKey(Bytes.of(1, 2, 3));
		apiKey.setWebhookSecretKey(Bytes.of(4, 5, 6));
		apiKey.setEnabled(enabled);
		apiKey.setMaxWebhooks(10L);
		apiKey.setExpiresAt(expiresAt);
		apiKey.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
		apiKey.setPermissions(Set.of(permission));
		return apiKey;
	}

	private static void inTransaction(boolean commit, Supplier<?> action) {
		beginTransaction();
		try {
			action.get();
			completeTransaction(commit);
		} catch (RuntimeException | Error exception) {
			completeTransaction(false);
			throw exception;
		}
	}

	private static void beginTransaction() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
	}

	private static void completeTransaction(boolean commit) {
		try {
			if (commit) {
				TransactionSynchronizationManager.getSynchronizations()
						.forEach(TransactionSynchronization::afterCommit);
			}
			int status = commit ? TransactionSynchronization.STATUS_COMMITTED
					: TransactionSynchronization.STATUS_ROLLED_BACK;
			TransactionSynchronizationManager.getSynchronizations()
					.forEach(synchronization -> synchronization.afterCompletion(status));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}
	}

}
