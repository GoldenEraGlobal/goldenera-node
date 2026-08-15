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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.Constants;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
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

/**
 * Manages RandomX epoch resources and hands callers idempotent VM leases.
 * Native cache/dataset memory is retired on an epoch switch and is released
 * only after the last VM backed by that exact resource has closed.
 */
@Slf4j
@FieldDefaults(level = PRIVATE)
public class RandomXManager {

	private static final int MAX_CACHED_EPOCHS = 3;

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
	final Set<RandomXFlag> recommendedFlags;
	final Map<Bytes, EpochResources> epochResources = new LinkedHashMap<>(4, 0.75f, true);

	volatile EpochResources currentResources;
	volatile byte[] currentSeed;
	volatile boolean isShutdown;

	@Getter
	volatile boolean isShuttingDown;

	public RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext) {
		this(miningProperties, chainQuery, runtimeContext, new NativeRandomXResourceFactory(), null, null,
				Constants::getSettings);
	}

	RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext, RandomXResourceFactory resourceFactory,
			LongFunction<byte[]> testSeedResolver) {
		this(miningProperties, chainQuery, runtimeContext, resourceFactory, testSeedResolver,
				Set.of(RandomXFlag.DEFAULT), Constants::getSettings);
	}

	RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext, RandomXResourceFactory resourceFactory,
			Supplier<NetworkSettings> networkSettingsSupplier) {
		this(miningProperties, chainQuery, runtimeContext, resourceFactory, null,
				Set.of(RandomXFlag.DEFAULT), networkSettingsSupplier);
	}

	private RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext, RandomXResourceFactory resourceFactory,
			LongFunction<byte[]> testSeedResolver, Set<RandomXFlag> recommendedFlags,
			Supplier<NetworkSettings> networkSettingsSupplier) {
		this.miningProperties = Objects.requireNonNull(miningProperties, "miningProperties");
		this.chainQuery = Objects.requireNonNull(chainQuery, "chainQuery");
		this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext");
		this.resourceFactory = Objects.requireNonNull(resourceFactory, "resourceFactory");
		this.testSeedResolver = testSeedResolver;
		this.networkSettingsSupplier = Objects.requireNonNull(networkSettingsSupplier, "networkSettingsSupplier");
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

		if (supportsHugePagesAttempt()) {
			Set<RandomXFlag> hugeFlags = miningFlags(true);
			try {
				log.debug("Attempting to initialize FULL RandomX resources with large pages");
				return allocate(seed, hugeFlags, true);
			} catch (RandomXInitializationException e) {
				log.warn("RandomX large-page initialization failed; retrying with standard memory: {}",
						e.getMessage());
			}
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

	private boolean supportsHugePagesAttempt() {
		String osName = System.getProperty("os.name", "").toLowerCase();
		return !osName.contains("mac") && !osName.contains("darwin");
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
