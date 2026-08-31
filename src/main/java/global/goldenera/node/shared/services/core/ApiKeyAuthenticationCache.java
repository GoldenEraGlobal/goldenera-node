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

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;

import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.properties.ApiKeyAuthenticationCacheProperties;
import global.goldenera.node.shared.repositories.ApiKeyCoreRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.NonNull;

/**
 * Bounded authentication snapshot cache coherent across every process sharing the
 * PostgreSQL database. Every lookup observes the transactionally maintained auth
 * epoch before consulting the heavier cached snapshot.
 */
@Component
public class ApiKeyAuthenticationCache {

	private static final String LOOKUP_METRIC = "security.api_key.auth_cache.lookups";

	private final ApiKeyCoreRepository repository;
	private final ApiKeyAuthenticationCacheProperties properties;
	private final Cache<String, CacheEntry> cache;
	private final Object transactionResourceKey = new Object();
	private final Counter hitCounter;
	private final Counter negativeHitCounter;
	private final Counter missCounter;
	private final Counter disabledCounter;
	private final Counter transactionBypassCounter;
	private final Counter commitInvalidationCounter;

	@Autowired
	public ApiKeyAuthenticationCache(ApiKeyCoreRepository repository,
			ApiKeyAuthenticationCacheProperties properties,
			MeterRegistry registry) {
		this(repository, properties, registry, Ticker.systemTicker());
	}

	ApiKeyAuthenticationCache(ApiKeyCoreRepository repository,
			ApiKeyAuthenticationCacheProperties properties,
			MeterRegistry registry,
			Ticker ticker) {
		this.repository = repository;
		this.properties = properties;
		this.cache = Caffeine.newBuilder()
				.maximumSize(properties.getMaximumSize())
				.expireAfter(new EntryExpiry(properties.getTtl(), properties.getNegativeTtl()))
				.ticker(ticker)
				.build();
		this.hitCounter = lookupCounter(registry, "hit");
		this.negativeHitCounter = lookupCounter(registry, "negative_hit");
		this.missCounter = lookupCounter(registry, "miss");
		this.disabledCounter = lookupCounter(registry, "disabled");
		this.transactionBypassCounter = lookupCounter(registry, "transaction_bypass");
		this.commitInvalidationCounter = Counter.builder("security.api_key.auth_cache.invalidations")
				.description("Committed local API-key mutations that invalidated the authentication cache")
				.register(registry);
		Gauge.builder("security.api_key.auth_cache.size", cache, Cache::estimatedSize)
				.description("Estimated number of API-key authentication cache entries")
				.register(registry);
	}

	/**
	 * Reads the shared auth epoch before using a cached snapshot. The epoch read is
	 * the authentication attempt's coherence point: a mutation committed before it
	 * is visible immediately, including changes made by another process or direct
	 * SQL. No database operation is performed while holding a process lock.
	 */
	public <T> T withAuthenticationKey(@NonNull String keyPrefix,
			@NonNull Function<Optional<ApiKey>, T> action) {
		if (TransactionSynchronizationManager.hasResource(transactionResourceKey)) {
			transactionBypassCounter.increment();
			return action.apply(loadSnapshot(keyPrefix).map(AuthenticationSnapshot::toApiKey));
		}
		if (!properties.isEnabled()) {
			disabledCounter.increment();
			return action.apply(loadSnapshot(keyPrefix).map(AuthenticationSnapshot::toApiKey));
		}
		long observedEpoch = repository.findAuthenticationEpoch();
		CacheEntry entry = lookup(keyPrefix, observedEpoch);
		return action.apply(entry.toApiKey());
	}

	/**
	 * Registers a local post-commit eviction as an eager optimization. Strict
	 * correctness comes from the database trigger epoch, so no process lock spans a
	 * transaction and {@code REQUIRES_NEW} calls cannot block unrelated lookups.
	 */
	public <T> T executeMutation(@NonNull Supplier<T> mutation) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			return executeNonTransactionalMutation(mutation);
		}

		if (TransactionSynchronizationManager.hasResource(transactionResourceKey)) {
			return mutation.get();
		}

		boolean synchronizationRegistered = false;
		try {
			TransactionSynchronizationManager.bindResource(transactionResourceKey, Boolean.TRUE);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void suspend() {
					TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
				}

				@Override
				public void resume() {
					TransactionSynchronizationManager.bindResource(transactionResourceKey, Boolean.TRUE);
				}

				@Override
				public void afterCommit() {
					invalidateAfterCommit();
				}

				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
				}
			});
			synchronizationRegistered = true;
			return mutation.get();
		} finally {
			if (!synchronizationRegistered) {
				TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
			}
		}
	}

	private CacheEntry lookup(String keyPrefix, long observedEpoch) {
		CacheEntry cached = cache.getIfPresent(keyPrefix);
		if (cached != null && cached.epoch() == observedEpoch) {
			if (cached.found()) {
				hitCounter.increment();
			} else {
				negativeHitCounter.increment();
			}
			return cached;
		}

		missCounter.increment();
		CacheEntry loaded = load(keyPrefix, observedEpoch);
		cache.put(keyPrefix, loaded);
		return loaded;
	}

	private CacheEntry load(String keyPrefix, long observedEpoch) {
		return loadSnapshot(keyPrefix)
				.map(snapshot -> CacheEntry.found(observedEpoch, snapshot))
				.orElseGet(() -> CacheEntry.missing(observedEpoch));
	}

	private Optional<AuthenticationSnapshot> loadSnapshot(String keyPrefix) {
		return repository.findByKeyPrefix(keyPrefix).map(AuthenticationSnapshot::from);
	}

	private <T> T executeNonTransactionalMutation(Supplier<T> mutation) {
		T result = mutation.get();
		invalidateAfterCommit();
		return result;
	}

	private void invalidateAfterCommit() {
		cache.invalidateAll();
		commitInvalidationCounter.increment();
	}

	private static Counter lookupCounter(MeterRegistry registry, String result) {
		return Counter.builder(LOOKUP_METRIC)
				.description("API-key authentication cache lookup outcomes")
				.tag("result", result)
				.register(registry);
	}

	private record CacheEntry(long epoch, AuthenticationSnapshot snapshot) {

		private static CacheEntry found(long epoch, AuthenticationSnapshot snapshot) {
			return new CacheEntry(epoch, snapshot);
		}

		private static CacheEntry missing(long epoch) {
			return new CacheEntry(epoch, null);
		}

		private boolean found() {
			return snapshot != null;
		}

		private Optional<ApiKey> toApiKey() {
			return snapshot == null ? Optional.empty() : Optional.of(snapshot.toApiKey());
		}
	}

	private record AuthenticationSnapshot(
			Set<ApiKeyPermission> permissions,
			Long id,
			Long version,
			String label,
			String description,
			String keyPrefix,
			byte[] secretKey,
			byte[] webhookSecretKey,
			boolean enabled,
			Long maxWebhooks,
			Instant expiresAt,
			Instant createdAt) {

		private static AuthenticationSnapshot from(ApiKey apiKey) {
			return new AuthenticationSnapshot(
					Set.copyOf(apiKey.getPermissions()),
					apiKey.getId(),
					apiKey.getVersion(),
					apiKey.getLabel(),
					apiKey.getDescription(),
					apiKey.getKeyPrefix(),
					copy(apiKey.getSecretKey()),
					copy(apiKey.getWebhookSecretKey()),
					apiKey.isEnabled(),
					apiKey.getMaxWebhooks(),
					apiKey.getExpiresAt(),
					apiKey.getCreatedAt());
		}

		private ApiKey toApiKey() {
			ApiKey apiKey = new ApiKey();
			apiKey.setPermissions(new HashSet<>(permissions));
			apiKey.setId(id);
			apiKey.setVersion(version);
			apiKey.setLabel(label);
			apiKey.setDescription(description);
			apiKey.setKeyPrefix(keyPrefix);
			apiKey.setSecretKey(wrap(secretKey));
			apiKey.setWebhookSecretKey(wrap(webhookSecretKey));
			apiKey.setEnabled(enabled);
			apiKey.setMaxWebhooks(maxWebhooks);
			apiKey.setExpiresAt(expiresAt);
			apiKey.setCreatedAt(createdAt);
			return apiKey;
		}

		private static byte[] copy(Bytes value) {
			return value == null ? null : value.toArray();
		}

		private static Bytes wrap(byte[] value) {
			return value == null ? null : Bytes.wrap(value.clone());
		}
	}

	private static final class EntryExpiry implements Expiry<String, CacheEntry> {

		private final long positiveTtlNanos;
		private final long negativeTtlNanos;

		private EntryExpiry(Duration positiveTtl, Duration negativeTtl) {
			positiveTtlNanos = positiveTtl.toNanos();
			negativeTtlNanos = negativeTtl.toNanos();
		}

		@Override
		public long expireAfterCreate(String key, CacheEntry value, long currentTime) {
			return value.found() ? positiveTtlNanos : negativeTtlNanos;
		}

		@Override
		public long expireAfterUpdate(String key, CacheEntry value, long currentTime, long currentDuration) {
			return expireAfterCreate(key, value, currentTime);
		}

		@Override
		public long expireAfterRead(String key, CacheEntry value, long currentTime, long currentDuration) {
			return currentDuration;
		}
	}
}
