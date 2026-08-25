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
package global.goldenera.node.core.blockchain.crypto;

import static lombok.AccessLevel.PRIVATE;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.Constants;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationContext;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.properties.RandomXVerificationProperties;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.randomx.RandomXCache;
import global.goldenera.randomx.RandomXDataset;
import global.goldenera.randomx.RandomXFlag;
import global.goldenera.randomx.RandomXUtils;
import global.goldenera.randomx.RandomXVM;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Manages RandomX epoch resources and hands callers idempotent VM leases.
 * Native cache/dataset memory is retired on an epoch switch and is released
 * only after the last VM backed by that exact resource has closed.
 */
@Slf4j
@FieldDefaults(level = PRIVATE)
public class RandomXManager {

	private static final int MAX_CACHED_EPOCHS = 3;
	private static final int MAX_CIRCUIT_BROKEN_SEEDS = 32;
	private static final Bytes SYNC_PARITY_DOMAIN = Bytes.wrap(
			"goldenera-sync-randomx-parity-v1".getBytes(StandardCharsets.US_ASCII));

	final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
	final Object epochCacheLock = new Object();
	final AtomicInteger activeVMs = new AtomicInteger();
	final AtomicBoolean initializationInProgress = new AtomicBoolean();
	final MiningProperties miningProperties;
	final ChainQuery chainQuery;
	final SandboxRuntimeContext runtimeContext;
	final RandomXResourceFactory resourceFactory;
	final LongFunction<byte[]> testSeedResolver;
	final Supplier<NetworkSettings> networkSettingsSupplier;
	final Supplier<RandomXLargePageSupport.Availability> largePageAvailabilitySupplier;
	final Set<RandomXFlag> recommendedFlags;
	final Map<Bytes, EpochResources> epochResources = new LinkedHashMap<>(4, 0.75f, true);
	final Object syncDatasetLock = new Object();
	final Map<Bytes, Long> expectedSyncHashes = new LinkedHashMap<>(4, 0.75f, true);
	final Map<Bytes, Long> circuitBrokenSyncSeeds = new LinkedHashMap<>(4, 0.75f, true);
	final AtomicInteger syncBulkActiveGauge = new AtomicInteger();
	final AtomicLong syncLifecycleGeneration = new AtomicLong();
	final AtomicBoolean syncAccelerationStopping = new AtomicBoolean();

	volatile RandomXVerificationProperties syncVerificationProperties = new RandomXVerificationProperties();
	volatile RandomXSyncMemoryPolicy syncMemoryPolicy = new RandomXSyncMemoryPolicy(
			syncVerificationProperties, () -> new RandomXSyncMemoryPolicy.MemorySnapshot(0, 0, 0, false));
	volatile MeterRegistry syncRegistry = new SimpleMeterRegistry();
	volatile boolean explorerEnabled;
	volatile boolean syncBulkActive;
	volatile long syncGap;
	volatile long syncCooldownUntilNanos;
	volatile EpochResources syncDatasetResources;
	volatile Bytes syncDatasetSeed;

	volatile EpochResources currentResources;
	volatile byte[] currentSeed;
	volatile boolean isShutdown;

	@Getter
	volatile boolean isShuttingDown;

	public RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext) {
		this(miningProperties, chainQuery, runtimeContext, new NativeRandomXResourceFactory(), null, null,
				Constants::getSettings, new RandomXLargePageSupport()::currentAvailability);
	}

	RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext, RandomXResourceFactory resourceFactory,
			LongFunction<byte[]> testSeedResolver) {
		this(miningProperties, chainQuery, runtimeContext, resourceFactory, testSeedResolver,
				Set.of(RandomXFlag.DEFAULT), Constants::getSettings,
				new RandomXLargePageSupport()::currentAvailability);
	}

	RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext, RandomXResourceFactory resourceFactory,
			LongFunction<byte[]> testSeedResolver,
			Supplier<RandomXLargePageSupport.Availability> largePageAvailabilitySupplier) {
		this(miningProperties, chainQuery, runtimeContext, resourceFactory, testSeedResolver,
				Set.of(RandomXFlag.DEFAULT), Constants::getSettings, largePageAvailabilitySupplier);
	}

	RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext, RandomXResourceFactory resourceFactory,
			Supplier<NetworkSettings> networkSettingsSupplier) {
		this(miningProperties, chainQuery, runtimeContext, resourceFactory, null,
				Set.of(RandomXFlag.DEFAULT), networkSettingsSupplier,
				new RandomXLargePageSupport()::currentAvailability);
	}

	private RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext, RandomXResourceFactory resourceFactory,
			LongFunction<byte[]> testSeedResolver, Set<RandomXFlag> recommendedFlags,
			Supplier<NetworkSettings> networkSettingsSupplier,
			Supplier<RandomXLargePageSupport.Availability> largePageAvailabilitySupplier) {
		this.miningProperties = Objects.requireNonNull(miningProperties, "miningProperties");
		this.chainQuery = Objects.requireNonNull(chainQuery, "chainQuery");
		this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext");
		this.resourceFactory = Objects.requireNonNull(resourceFactory, "resourceFactory");
		this.testSeedResolver = testSeedResolver;
		this.networkSettingsSupplier = Objects.requireNonNull(networkSettingsSupplier, "networkSettingsSupplier");
		this.largePageAvailabilitySupplier = Objects.requireNonNull(largePageAvailabilitySupplier,
				"largePageAvailabilitySupplier");
		validateActivation();
		this.recommendedFlags = immutableFlags(recommendedFlags == null ? loadRecommendedFlags() : recommendedFlags);
		log.info("RandomX configured for {} mining memory", miningProperties.getMemoryMode());
	}

	private void validateActivation() {
		RandomXMiningMemoryMode memoryMode = Objects.requireNonNull(miningProperties.getMemoryMode(),
				"ge.core.mining.memory-mode");
		if (memoryMode == RandomXMiningMemoryMode.LIGHT && !runtimeContext.isSandbox()) {
			throw new IllegalStateException(
					"RandomX LIGHT mining is restricted to an activated sandbox execution domain");
		}
	}

	@PostConstruct
	public void init() {
		log.info("Initializing RandomX with the active epoch seed...");
		long height = chainQuery.getLatestBlockHeight().orElse(0L);
		ensureInitializedForHeight(height);
	}

	public boolean isInitializationInProgress() {
		return initializationInProgress.get();
	}

	public int getActiveVmLeaseCount() {
		return activeVMs.get();
	}

	public void configureSyncVerificationAcceleration(
			RandomXVerificationProperties properties,
			boolean explorerEnabled,
			MeterRegistry registry) {
		configureSyncVerificationAcceleration(
				properties, explorerEnabled, registry, RandomXSyncMemoryPolicy.MemorySnapshot::system);
	}

	void configureSyncVerificationAcceleration(
			RandomXVerificationProperties properties,
			boolean explorerEnabled,
			MeterRegistry registry,
			RandomXSyncMemoryPolicy.MemoryProbe memoryProbe) {
		Objects.requireNonNull(properties, "RandomX verification properties").validate();
		this.syncVerificationProperties = properties;
		this.explorerEnabled = explorerEnabled;
		this.syncRegistry = Objects.requireNonNull(registry, "meterRegistry");
		this.syncMemoryPolicy = new RandomXSyncMemoryPolicy(properties, Objects.requireNonNull(memoryProbe));
		registry.gauge("blockchain.randomx.sync_dataset.active", this,
				manager -> manager.isSyncDatasetMapped() ? 1 : 0);
		registry.gauge("blockchain.randomx.mining_dataset.active", this,
				manager -> manager.isDatasetAllocated() ? 1 : 0);
		registry.gauge("blockchain.randomx.vm.leases", activeVMs, AtomicInteger::get);
		registry.gauge("blockchain.randomx.historical_epochs", this, RandomXManager::historicalEpochCount);
		registry.gauge("blockchain.randomx.sync_dataset.bulk_active", syncBulkActiveGauge, AtomicInteger::get);
	}

	private int historicalEpochCount() {
		synchronized (epochCacheLock) {
			return epochResources.size();
		}
	}

	public void syncBulkCatchUpStarted(long localHeight, long targetHeight) {
		if (syncAccelerationStopping.get()) {
			return;
		}
		long generation = syncLifecycleGeneration.incrementAndGet();
		updateSyncGap(localHeight, targetHeight, generation);
	}

	public void syncProgress(long localHeight, long targetHeight) {
		updateSyncGap(localHeight, targetHeight, syncLifecycleGeneration.get());
	}

	public void syncCaughtUp() {
		exitBulkMode(hasActiveSyncAcceleration(), "caught_up");
	}

	public void syncStopped() {
		syncLifecycleGeneration.incrementAndGet();
		exitBulkMode(true, "stopped");
	}

	public void syncFailed() {
		exitBulkMode(hasActiveSyncAcceleration(), "failed");
	}

	private void updateSyncGap(long localHeight, long targetHeight, long generation) {
		if (generation != syncLifecycleGeneration.get()) {
			return;
		}
		long gap = targetHeight > localHeight ? targetHeight - localHeight : 0L;
		syncGap = gap;
		long now = System.nanoTime();
		if (syncBulkActive) {
			if (gap <= syncVerificationProperties.getTailExitGap()) {
				exitBulkMode(true, "near_head");
			}
			return;
		}
		if (gap >= syncVerificationProperties.getBulkEnterGap() && now >= syncCooldownUntilNanos) {
			syncBulkActive = true;
			syncBulkActiveGauge.set(1);
			syncRegistry.counter("blockchain.randomx.sync_dataset.bulk_transitions", "state", "entered",
					"reason", "catch_up_gap")
					.increment();
			log.info("RandomX sync accelerator entered BULK mode for gap {}", gap);
		} else {
			syncRegistry.counter("blockchain.randomx.sync_dataset.skipped", "reason",
					now < syncCooldownUntilNanos ? "cooldown" : "small_gap").increment();
		}
	}

	private void exitBulkMode(boolean startCooldown, String reason) {
		syncBulkActive = false;
		syncBulkActiveGauge.set(0);
		syncGap = 0L;
		if (startCooldown) {
			long cooldown = syncVerificationProperties.getRebuildCooldown().toNanos();
			syncCooldownUntilNanos = saturatingAdd(System.nanoTime(), cooldown);
		}
		retireSyncDataset(reason);
		syncRegistry.counter("blockchain.randomx.sync_dataset.bulk_transitions", "state", "exited",
				"reason", reason).increment();
	}

	private long saturatingAdd(long value, long increment) {
		return increment > 0 && value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
	}


	public RandomXMiningMemoryMode getMiningMemoryMode() {
		return miningProperties.getMemoryMode();
	}

	public boolean isDatasetAllocated() {
		lifecycleLock.readLock().lock();
		try {
			return currentResources != null && currentResources.dataset() != null;
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	/** Ensures mining resources correspond to the canonical RandomX epoch. */
	public void ensureInitializedForHeight(long height) {
		ensureResourcesForHeight(height, false);
	}

	/** Ensures this epoch has the resources required for an explicit mining attempt. */
	public void prepareMiningResourcesForHeight(long height) {
		ensureResourcesForHeight(height, true);
	}

	private void ensureResourcesForHeight(long height, boolean miningRequested) {
		assertRunning();

		byte[] requiredSeed = calculateSeedForHeight(height);
		lifecycleLock.readLock().lock();
		try {
			if (isCurrentSeed(requiredSeed) && (!miningRequested || currentResourcesSupportMining())) {
				return;
			}
		} finally {
			lifecycleLock.readLock().unlock();
		}

		lifecycleLock.writeLock().lock();
		try {
			assertRunning();
			if (isCurrentSeed(requiredSeed) && (!miningRequested || currentResourcesSupportMining())) {
				return;
			}
			log.info("RandomX epoch switch detected at height {}. Initializing replacement resources...", height);
			replaceCurrentResources(requiredSeed, miningRequested);
		} finally {
			lifecycleLock.writeLock().unlock();
		}
	}

	/** Opens a dataset-backed FULL miner or a cache-only LIGHT miner. */
	public RandomXVmLease createMiningVM() {
		lifecycleLock.readLock().lock();
		try {
			assertRunning();
			EpochResources resources = requireCurrentResources();
			if (miningProperties.getMemoryMode() == RandomXMiningMemoryMode.FULL
					&& resources.dataset() == null) {
				throw new IllegalStateException("RandomX FULL mining dataset is not loaded");
			}
			return resources.acquire(resourceFactory, activeVMs);
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	/** Opens a cache-only verifier while preserving the caller's seed resolver. */
	public RandomXVmLease getLightVMForVerification(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		assertRunning();
		byte[] requiredSeed = calculateSeedForHeight(height, seedBlockProvider);

		lifecycleLock.readLock().lock();
		try {
			assertRunning();
			if (isCurrentSeed(requiredSeed)) {
				return requireCurrentResources().acquireLight(resourceFactory, activeVMs);
			}
		} finally {
			lifecycleLock.readLock().unlock();
		}

		return acquireHistoricalEpoch(requiredSeed, height);
	}

	public ProofOfWorkVerificationContext verificationContext(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		ProofOfWorkVerificationContext context = new ProofOfWorkVerificationContext(Bytes.wrap(
				calculateSeedForHeight(height, seedBlockProvider)));
		observeExpectedSyncHashes(context, height);
		return context;
	}

	/** Uses a safe bulk-sync dataset when admitted and otherwise falls back to LIGHT. */
	public RandomXVerificationVmLease acquireVerificationVM(ProofOfWorkVerificationContext context) {
		assertRunning();
		Bytes seedKey = Objects.requireNonNull(context, "context").resourceKey();
		byte[] requiredSeed = seedKey.toArray();

		long expectedHashes;
		synchronized (syncDatasetLock) {
			expectedHashes = expectedSyncHashes.getOrDefault(seedKey, 0L);
		}
		boolean matchingMiningDataset = currentHasMatchingDataset(requiredSeed);
		if (syncBulkActive) {
			RandomXVerificationVmLease existingSyncDataset = acquireExistingSyncDataset(seedKey);
			if (existingSyncDataset != null) {
				return existingSyncDataset;
			}
		}
		RandomXSyncMemoryPolicy.Decision decision = syncBulkActive
				? syncMemoryPolicy.decide(expectedHashes, explorerEnabled,
						Boolean.TRUE.equals(miningProperties.getEnable()), matchingMiningDataset)
				: new RandomXSyncMemoryPolicy.Decision(RandomXSyncMemoryPolicy.Outcome.LIGHT, "not in BULK mode");
		if (decision.outcome() == RandomXSyncMemoryPolicy.Outcome.REUSE_EXISTING && matchingMiningDataset) {
			RandomXVerificationVmLease miningLease = acquireMatchingCurrentVerification(requiredSeed, true);
			if (miningLease != null) {
				retireSyncDataset("matching_mining_reuse");
				syncRegistry.counter("blockchain.randomx.sync_dataset.reused", "owner", "mining").increment();
				return miningLease;
			}
		}
		if (decision.outcome() == RandomXSyncMemoryPolicy.Outcome.BUILD_DATASET) {
			RandomXVerificationVmLease miningLease = acquireMatchingCurrentVerification(requiredSeed, true);
			if (miningLease != null) {
				retireSyncDataset("matching_mining_reuse");
				syncRegistry.counter("blockchain.randomx.sync_dataset.reused", "owner", "mining").increment();
				return miningLease;
			}
			RandomXVerificationVmLease accelerated = acquireSyncDatasetVerification(seedKey, requiredSeed);
			if (accelerated != null) {
				return accelerated;
			}
		}
		syncRegistry.counter("blockchain.randomx.sync_dataset.fallback", "reason",
				metricReason(decision.reason())).increment();
		return acquireLightVerification(seedKey, requiredSeed);
	}

	private boolean currentHasMatchingDataset(byte[] requiredSeed) {
		lifecycleLock.readLock().lock();
		try {
			return !isShutdown && isCurrentSeed(requiredSeed) && requireCurrentResources().dataset() != null;
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	private RandomXVerificationVmLease acquireMatchingCurrentVerification(
			byte[] requiredSeed, boolean requireDataset) {
		lifecycleLock.readLock().lock();
		try {
			assertRunning();
			if (!isCurrentSeed(requiredSeed)) {
				return null;
			}
			EpochResources resources = requireCurrentResources();
			if (requireDataset && resources.dataset() == null) {
				return null;
			}
			return resources.acquireVerification(resourceFactory, activeVMs);
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	private RandomXVerificationVmLease acquireExistingSyncDataset(Bytes seedKey) {
		synchronized (syncDatasetLock) {
			if (syncDatasetResources == null || !seedKey.equals(syncDatasetSeed)
					|| syncDatasetResources.isRetired()) {
				return null;
			}
			return syncDatasetResources.acquireVerification(resourceFactory, activeVMs);
		}
	}

	private void observeExpectedSyncHashes(ProofOfWorkVerificationContext context, long height) {
		long epochLength = Objects.requireNonNull(networkSettingsSupplier.get(), "active NetworkSettings")
				.randomXEpochLength();
		if (epochLength <= 0) {
			return;
		}
		long remainingInEpoch = epochLength - Math.floorMod(height, epochLength);
		long expected = Math.min(Math.max(0L, syncGap), remainingInEpoch);
		synchronized (syncDatasetLock) {
			expectedSyncHashes.merge(context.resourceKey(), expected, Math::max);
			while (expectedSyncHashes.size() > MAX_CIRCUIT_BROKEN_SEEDS) {
				expectedSyncHashes.remove(expectedSyncHashes.keySet().iterator().next());
			}
		}
	}

	private RandomXVerificationVmLease acquireLightVerification(Bytes seedKey, byte[] requiredSeed) {
		lifecycleLock.readLock().lock();
		try {
			assertRunning();
			if (isCurrentSeed(requiredSeed)) {
				return new RandomXVerificationVmLease(
						requireCurrentResources().acquireLight(resourceFactory, activeVMs),
						ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			}
		} finally {
			lifecycleLock.readLock().unlock();
		}

		synchronized (epochCacheLock) {
			assertRunning();
			EpochResources resources = epochResources.get(seedKey);
			if (resources == null) {
				log.info("Initializing cache-only RandomX resources for verification epoch");
				resources = allocateCacheOnly(requiredSeed);
				epochResources.put(Bytes.wrap(requiredSeed.clone()), resources);
			}
			RandomXVerificationVmLease lease = new RandomXVerificationVmLease(
					resources.acquireLight(resourceFactory, activeVMs),
					ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			evictHistoricalEpochs();
			return lease;
		}
	}

	private RandomXVerificationVmLease acquireSyncDatasetVerification(Bytes seedKey, byte[] requiredSeed) {
		synchronized (syncDatasetLock) {
			assertRunning();
			if (!syncBulkActive || syncAccelerationStopping.get() || isSyncSeedCircuitBroken(seedKey)) {
				return null;
			}
			if (syncDatasetResources != null && seedKey.equals(syncDatasetSeed)
					&& !syncDatasetResources.isRetired()) {
				return syncDatasetResources.acquireVerification(resourceFactory, activeVMs);
			}
			if (!retirePreviousSyncDatasetForReplacement()) {
				syncRegistry.counter("blockchain.randomx.sync_dataset.fallback", "reason", "active_previous_seed")
						.increment();
				return null;
			}

			long started = System.nanoTime();
			EpochResources candidate = null;
			try {
				candidate = allocateSyncDataset(requiredSeed);
				verifySyncDatasetParity(candidate, seedKey);
				if (!syncBulkActive || syncAccelerationStopping.get() || isShutdown) {
					candidate.retire();
					return null;
				}
				syncDatasetResources = candidate;
				syncDatasetSeed = Bytes.wrap(requiredSeed.clone());
				syncRegistry.counter("blockchain.randomx.sync_dataset.builds", "outcome", "success",
						"reason", "none").increment();
				syncRegistry.timer("blockchain.randomx.sync_dataset.build.duration")
						.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
				log.info("RandomX sync accelerator published FULL dataset for seed {} after {}ms",
						seedKey.slice(0, Math.min(8, seedKey.size())),
						TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
				return candidate.acquireVerification(resourceFactory, activeVMs);
			} catch (RuntimeException | LinkageError failure) {
				if (candidate != null) {
					candidate.retire();
				}
				markSyncSeedCircuitBroken(seedKey);
				syncRegistry.counter("blockchain.randomx.sync_dataset.builds", "outcome", "failure",
						"reason", metricReason(failure.getClass().getSimpleName())).increment();
				log.warn("RandomX sync FULL dataset unavailable for seed {}; using LIGHT: {}",
						seedKey.slice(0, Math.min(8, seedKey.size())), failure.getMessage());
				return null;
			}
		}
	}

	private EpochResources allocateSyncDataset(byte[] requiredSeed) {
		RandomXLargePageSupport.Availability largePages = Objects.requireNonNull(
				largePageAvailabilitySupplier.get(), "RandomX large-page availability");
		if (largePages.available()) {
			try {
				return allocate(requiredSeed, miningFlags(true), true);
			} catch (RandomXInitializationException failure) {
				log.warn("RandomX sync large-page dataset failed; retrying standard memory: {}",
						failure.getMessage());
			}
		}
		return allocate(requiredSeed, miningFlags(false), true);
	}

	private void verifySyncDatasetParity(EpochResources candidate, Bytes seedKey) {
		byte[] input = Bytes.concatenate(SYNC_PARITY_DOMAIN, seedKey).toArray();
		long started = System.nanoTime();
		byte[] light;
		byte[] full;
		try (RandomXVmLease lightLease = candidate.acquireLight(resourceFactory, activeVMs);
				RandomXVmLease fullLease = candidate.acquire(resourceFactory, activeVMs)) {
			light = lightLease.calculateHash(input);
			full = fullLease.calculateHash(input);
		}
		syncRegistry.timer("blockchain.randomx.sync_dataset.parity.duration")
				.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
		if (!Arrays.equals(light, full)) {
			throw new RandomXInitializationException("FULL/LIGHT RandomX parity probe mismatch", null);
		}
	}

	private boolean retirePreviousSyncDatasetForReplacement() {
		EpochResources resources = syncDatasetResources;
		if (resources == null) {
			return true;
		}
		resources.retire();
		if (!resources.isClosed()) {
			return false;
		}
		syncDatasetResources = null;
		syncDatasetSeed = null;
		return true;
	}

	private void retireSyncDataset(String reason) {
		synchronized (syncDatasetLock) {
			EpochResources resources = syncDatasetResources;
			if (resources == null) {
				return;
			}
			resources.retire();
			syncRegistry.counter("blockchain.randomx.sync_dataset.retired", "reason", reason).increment();
			if (resources.isClosed()) {
				syncDatasetResources = null;
				syncDatasetSeed = null;
			}
		}
	}

	private boolean isSyncDatasetMapped() {
		synchronized (syncDatasetLock) {
			return syncDatasetResources != null && !syncDatasetResources.isClosed();
		}
	}

	private boolean hasActiveSyncAcceleration() {
		return syncBulkActive || isSyncDatasetMapped();
	}

	private void markSyncSeedCircuitBroken(Bytes seedKey) {
		long expiresAt = saturatingAdd(
				System.nanoTime(), syncVerificationProperties.getRebuildCooldown().toNanos());
		circuitBrokenSyncSeeds.put(Bytes.wrap(seedKey.toArray()), expiresAt);
		while (circuitBrokenSyncSeeds.size() > MAX_CIRCUIT_BROKEN_SEEDS) {
			circuitBrokenSyncSeeds.remove(circuitBrokenSyncSeeds.keySet().iterator().next());
		}
		syncRegistry.counter("blockchain.randomx.sync_dataset.circuit_breaks").increment();
	}

	private boolean isSyncSeedCircuitBroken(Bytes seedKey) {
		Long expiresAt = circuitBrokenSyncSeeds.get(seedKey);
		if (expiresAt == null) {
			return false;
		}
		if (System.nanoTime() < expiresAt) {
			return true;
		}
		circuitBrokenSyncSeeds.remove(seedKey);
		return false;
	}

	private String metricReason(String value) {
		String normalized = value == null ? "unknown"
				: value.toLowerCase().replaceAll("[0-9]+", "n").replaceAll("[^a-z0-9]+", "_");
		return normalized.isBlank() ? "unknown" : normalized;
	}

	public RandomXVmLease getLightVMForVerification(long height) {
		return getLightVMForVerification(height,
				h -> chainQuery.getBlockHashByHeight(h).map(Hash::toArray));
	}

	private RandomXVmLease acquireHistoricalEpoch(byte[] seed, long height) {
		Bytes seedKey = Bytes.wrap(seed.clone());
		synchronized (epochCacheLock) {
			assertRunning();
			EpochResources resources = epochResources.get(seedKey);
			if (resources == null) {
				log.info("Initializing cache-only RandomX resources for historical epoch at height {}", height);
				resources = allocateCacheOnly(seed);
				epochResources.put(seedKey, resources);
			}
			RandomXVmLease lease = resources.acquireLight(resourceFactory, activeVMs);
			evictHistoricalEpochs();
			return lease;
		}
	}

	private void evictHistoricalEpochs() {
		while (epochResources.size() > MAX_CACHED_EPOCHS) {
			Iterator<Map.Entry<Bytes, EpochResources>> iterator = epochResources.entrySet().iterator();
			Map.Entry<Bytes, EpochResources> eldest = iterator.next();
			iterator.remove();
			log.debug("Retiring least-recently-used RandomX historical epoch cache");
			eldest.getValue().retire();
		}
	}

	private void replaceCurrentResources(byte[] requiredSeed, boolean miningRequested) {
		initializationInProgress.set(true);
		try {
			long startedAt = System.currentTimeMillis();
			EpochResources replacement = allocateCurrent(requiredSeed, miningRequested);
			EpochResources previous = currentResources;
			currentResources = replacement;
			currentSeed = requiredSeed.clone();
			if (previous != null) {
				previous.retire();
			}
			log.info("RandomX {} memory update finished in {} ms", miningProperties.getMemoryMode(),
					System.currentTimeMillis() - startedAt);
		} finally {
			initializationInProgress.set(false);
		}
	}

	private EpochResources allocateCurrent(byte[] seed, boolean miningRequested) {
		boolean miningEnabled = Boolean.TRUE.equals(miningProperties.getEnable());
		boolean fullMining = (miningEnabled || miningRequested)
				&& miningProperties.getMemoryMode() == RandomXMiningMemoryMode.FULL;
		if (!fullMining) {
			return allocateCacheOnly(seed);
		}

		RandomXLargePageSupport.Availability largePages = Objects.requireNonNull(
				largePageAvailabilitySupplier.get(), "RandomX large-page availability");
		if (largePages.available()) {
			Set<RandomXFlag> hugeFlags = miningFlags(true);
			try {
				log.debug("Attempting to initialize FULL RandomX resources with large pages");
				return allocate(seed, hugeFlags, true);
			} catch (RandomXInitializationException e) {
				log.warn("RandomX large-page initialization failed; retrying with standard memory: {}",
						e.getMessage());
			}
		} else {
			log.info("Skipping RandomX large-page allocation: {}", largePages.reason());
		}
		return allocate(seed, miningFlags(false), true);
	}

	private EpochResources allocateCacheOnly(byte[] seed) {
		return allocate(seed, lightFlags(), false);
	}

	private EpochResources allocate(byte[] seed, Set<RandomXFlag> resourceFlags, boolean withDataset) {
		RandomXCache cache = null;
		RandomXDataset dataset = null;
		try {
			cache = resourceFactory.createCache(resourceFlags);
			cache.init(seed);
			if (withDataset) {
				dataset = resourceFactory.createDataset(resourceFlags);
				dataset.init(cache);
			}
			return new EpochResources(seed, resourceFlags, cache, dataset);
		} catch (RuntimeException | LinkageError failure) {
			closePartial(dataset, cache);
			throw new RandomXInitializationException(
					"Failed to initialize native RandomX " + (withDataset ? "FULL" : "LIGHT") + " resources",
					failure);
		}
	}

	private void closePartial(RandomXDataset dataset, RandomXCache cache) {
		if (dataset != null) {
			try {
				dataset.close();
			} catch (RuntimeException | LinkageError e) {
				log.warn("Failed to release partially initialized RandomX dataset", e);
			}
		}
		if (cache != null) {
			try {
				cache.close();
			} catch (RuntimeException | LinkageError e) {
				log.warn("Failed to release partially initialized RandomX cache", e);
			}
		}
	}

	private Set<RandomXFlag> lightFlags() {
		EnumSet<RandomXFlag> light = mutableRecommendedFlags();
		light.remove(RandomXFlag.FULL_MEM);
		light.remove(RandomXFlag.LARGE_PAGES);
		ensureNonEmpty(light);
		return Set.copyOf(light);
	}

	private Set<RandomXFlag> miningFlags(boolean largePages) {
		EnumSet<RandomXFlag> full = mutableRecommendedFlags();
		full.add(RandomXFlag.FULL_MEM);
		if (largePages) {
			full.add(RandomXFlag.LARGE_PAGES);
		} else {
			full.remove(RandomXFlag.LARGE_PAGES);
		}
		return Set.copyOf(full);
	}

	private EnumSet<RandomXFlag> mutableRecommendedFlags() {
		return recommendedFlags.isEmpty()
				? EnumSet.noneOf(RandomXFlag.class)
				: EnumSet.copyOf(recommendedFlags);
	}

	private void ensureNonEmpty(EnumSet<RandomXFlag> resourceFlags) {
		if (resourceFlags.isEmpty()) {
			resourceFlags.add(RandomXFlag.DEFAULT);
		}
	}

	private static Set<RandomXFlag> immutableFlags(Set<RandomXFlag> resourceFlags) {
		Objects.requireNonNull(resourceFlags, "RandomX recommended flags");
		return Set.copyOf(resourceFlags);
	}

	private static Set<RandomXFlag> loadRecommendedFlags() {
		try {
			return RandomXUtils.getRecommendedFlags();
		} catch (RuntimeException | LinkageError failure) {
			throw new RandomXInitializationException("Failed to load native RandomX capabilities", failure);
		}
	}

	private EpochResources requireCurrentResources() {
		EpochResources resources = currentResources;
		if (resources == null || currentSeed == null) {
			throw new IllegalStateException("RandomX is not initialized for the current epoch");
		}
		return resources;
	}

	private boolean isCurrentSeed(byte[] requiredSeed) {
		return currentResources != null && Arrays.equals(currentSeed, requiredSeed);
	}

	private boolean currentResourcesSupportMining() {
		return miningProperties.getMemoryMode() != RandomXMiningMemoryMode.FULL
				|| requireCurrentResources().dataset() != null;
	}

	private void assertRunning() {
		if (isShutdown) {
			throw new IllegalStateException("RandomX service is shutting down");
		}
	}

	private byte[] calculateSeedForHeight(long height) {
		if (testSeedResolver != null) {
			byte[] seed = Objects.requireNonNull(testSeedResolver.apply(height), "test RandomX seed");
			return seed.clone();
		}
		return calculateSeedForHeight(height,
				h -> chainQuery.getBlockHashByHeight(h).map(Hash::toArray));
	}

	private byte[] calculateSeedForHeight(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		if (testSeedResolver != null) {
			byte[] seed = Objects.requireNonNull(testSeedResolver.apply(height), "test RandomX seed");
			return seed.clone();
		}
		NetworkSettings networkSettings = Objects.requireNonNull(networkSettingsSupplier.get(),
				"active NetworkSettings");
		long epochLength = networkSettings.randomXEpochLength();
		if (epochLength <= 0) {
			throw new IllegalStateException("RandomX epoch length must be positive");
		}
		long epoch = height / epochLength;
		if (epoch == 0) {
			return networkSettings.randomXGenesisKey().getBytes(StandardCharsets.UTF_8);
		}

		long seedBlockHeight = (epoch - 1) * epochLength;
		Optional<byte[]> seed = seedBlockProvider.apply(seedBlockHeight);
		if (seed.isPresent()) {
			return seed.get().clone();
		}
		return chainQuery.getBlockHashByHeight(seedBlockHeight)
				.map(Hash::toArray)
				.orElseThrow(() -> new IllegalStateException(
						"Cannot calculate RandomX seed: seed block at height " + seedBlockHeight + " not found."));
	}

	@PreDestroy
	public void close() {
		log.info("Shutting down RandomX service...");
		syncAccelerationStopping.set(true);
		syncBulkActive = false;
		syncBulkActiveGauge.set(0);
		retireSyncDataset("shutdown");
		EpochResources current;
		lifecycleLock.writeLock().lock();
		try {
			if (isShutdown) {
				return;
			}
			isShuttingDown = true;
			isShutdown = true;
			current = currentResources;
			currentResources = null;
			currentSeed = null;
		} finally {
			lifecycleLock.writeLock().unlock();
		}

		if (current != null) {
			current.retire();
		}
		synchronized (epochCacheLock) {
			epochResources.values().forEach(EpochResources::retire);
			epochResources.clear();
		}
		if (activeVMs.get() == 0) {
			log.info("RandomX native resources released successfully");
		} else {
			log.info("RandomX shutdown retired resources; {} active VM lease(s) will release them on close",
					activeVMs.get());
		}
	}

	private static final class EpochResources {
		private final byte[] seed;
		private final Set<RandomXFlag> flags;
		private final RandomXCache cache;
		private final RandomXDataset dataset;
		private int leases;
		private boolean retired;
		private boolean closed;

		private EpochResources(byte[] seed, Set<RandomXFlag> flags, RandomXCache cache,
				RandomXDataset dataset) {
			this.seed = seed.clone();
			this.flags = Set.copyOf(flags);
			this.cache = Objects.requireNonNull(cache, "cache");
			this.dataset = dataset;
		}

		private RandomXDataset dataset() {
			return dataset;
		}

		private synchronized RandomXVmLease acquire(RandomXResourceFactory factory,
				AtomicInteger totalLeases) {
			return acquire(factory, totalLeases, flags, dataset);
		}

		private synchronized RandomXVmLease acquireLight(RandomXResourceFactory factory,
				AtomicInteger totalLeases) {
			EnumSet<RandomXFlag> cacheOnlyFlags = EnumSet.copyOf(flags);
			cacheOnlyFlags.remove(RandomXFlag.FULL_MEM);
			cacheOnlyFlags.remove(RandomXFlag.LARGE_PAGES);
			if (cacheOnlyFlags.isEmpty()) {
				cacheOnlyFlags.add(RandomXFlag.DEFAULT);
			}
			return acquire(factory, totalLeases, Set.copyOf(cacheOnlyFlags), null);
		}

		private synchronized RandomXVerificationVmLease acquireVerification(
				RandomXResourceFactory factory, AtomicInteger totalLeases) {
			if (dataset != null) {
				return new RandomXVerificationVmLease(
						acquire(factory, totalLeases, flags, dataset),
						ProofOfWorkVerificationMode.RANDOMX_FULL);
			}
			return new RandomXVerificationVmLease(
					acquireLight(factory, totalLeases),
					ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}

		private RandomXVmLease acquire(RandomXResourceFactory factory, AtomicInteger totalLeases,
				Set<RandomXFlag> vmFlags, RandomXDataset vmDataset) {
			if (retired || closed) {
				throw new IllegalStateException("RandomX epoch resources have been retired");
			}
			RandomXVM vm = Objects.requireNonNull(factory.createVM(vmFlags, cache, vmDataset),
					"RandomX VM factory result");
			leases++;
			totalLeases.incrementAndGet();
			return new RandomXVmLease(vm, () -> release(totalLeases));
		}

		private synchronized void release(AtomicInteger totalLeases) {
			if (leases == 0) {
				log.error("Ignoring duplicate RandomX epoch lease release for seed {}", Bytes.wrap(seed));
				return;
			}
			leases--;
			totalLeases.decrementAndGet();
			closeIfRetiredAndUnused();
		}

		private synchronized void retire() {
			retired = true;
			closeIfRetiredAndUnused();
		}

		private synchronized boolean isRetired() {
			return retired;
		}

		private synchronized boolean isClosed() {
			return closed;
		}

		private void closeIfRetiredAndUnused() {
			if (!retired || leases != 0 || closed) {
				return;
			}
			closed = true;
			if (dataset != null) {
				try {
					dataset.close();
				} catch (RuntimeException | LinkageError e) {
					log.warn("Failed to release retired RandomX dataset", e);
				}
			}
			try {
				cache.close();
			} catch (RuntimeException | LinkageError e) {
				log.warn("Failed to release retired RandomX cache", e);
			}
		}
	}
}
