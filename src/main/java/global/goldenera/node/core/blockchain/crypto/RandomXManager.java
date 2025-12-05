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
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
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
 * Manages RandomX memory and VM lifecycle with thread-safe access.
 * Handles Huge Pages allocation, Fallbacks, and Epoch switching without
 * crashes.
 */
@Service
@Slf4j
@FieldDefaults(level = PRIVATE)
public class RandomXManager {

	final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
	final AtomicInteger activeVMs = new AtomicInteger(0);
	final MiningProperties miningProperties;
	final ChainQuery chainQuery;

	Set<RandomXFlag> flags;

	// Volatile for double-checked locking, but main access is guarded by locks
	volatile RandomXDataset miningDataset;
	volatile RandomXCache verificationCache;
	volatile byte[] currentSeed;

	volatile boolean isShutdown = false;
	@Getter
	private boolean isShuttingDown = false;

	final AtomicBoolean initializationInProgress = new AtomicBoolean(false);

	// Cache for temporary VMs during sync (key = seed bytes as hex string)
	final Map<Bytes, CachedRandomXVM> epochVMCache = new ConcurrentHashMap<>();
	private static final int MAX_CACHED_EPOCHS = 3;

	private static class CachedRandomXVM {
		final RandomXCache cache;
		final Set<RandomXFlag> flags;
		final Bytes seed;
		volatile long lastUsed;

		CachedRandomXVM(RandomXCache cache, Set<RandomXFlag> flags, Bytes seed) {
			this.cache = cache;
			this.flags = flags;
			this.seed = seed;
			this.lastUsed = System.currentTimeMillis();
		}

		void close() {
			if (cache != null) {
				try {
					cache.close();
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	public boolean isInitializationInProgress() {
		return initializationInProgress.get();
	}

	public RandomXManager(MiningProperties miningProperties, ChainQuery chainQuery) {
		this.miningProperties = miningProperties;
		this.chainQuery = chainQuery;
		this.flags = new HashSet<>(RandomXUtils.getRecommendedFlags());
		log.debug("RandomX initialized with recommended flags: {}", flags);
	}

	@PostConstruct
	public void init() {
		log.info("Initializing RandomX with Genesis seed...");
		// Initial load does not need locks as no other threads are running yet,
		// but for consistency, we treat it as an update.
		long height = chainQuery.getLatestBlockHeight().orElse(0L);
		ensureInitializedForHeight(height);
	}

	/**
	 * Ensures the RandomX memory is initialized for the specific block height.
	 * If the epoch changed, it triggers a memory swap.
	 */
	public void ensureInitializedForHeight(long height) {
		if (isShutdown)
			return;

		byte[] requiredSeed = calculateSeedForHeight(height);

		// 1. Fast Path (Read Lock)
		lifecycleLock.readLock().lock();
		try {
			if (Arrays.equals(currentSeed, requiredSeed) && verificationCache != null) {
				return;
			}
		} finally {
			lifecycleLock.readLock().unlock();
		}

		// 2. Slow Path (Write Lock) - Switch Epoch
		lifecycleLock.writeLock().lock();
		try {
			if (isShutdown)
				return;
			// Double check inside write lock
			if (Arrays.equals(currentSeed, requiredSeed) && verificationCache != null) {
				return;
			}

			log.info("RandomX epoch switch detected at height {}. Reinitializing memory...", height);
			updateSeed(requiredSeed, false);
		} finally {
			lifecycleLock.writeLock().unlock();
		}
	}

	/**
	 * Creates a VM for Mining. Requires Dataset (Full Memory).
	 * The returned VM MUST be closed by the caller.
	 */
	public RandomXVM createMiningVM() {
		lifecycleLock.readLock().lock();
		try {
			if (isShutdown) {
				throw new IllegalStateException("Cannot create VM: service is shutting down");
			}
			if (verificationCache == null || currentSeed == null) {
				throw new IllegalStateException("RandomX not initialized. Call ensureInitializedForHeight() first.");
			}
			if (miningDataset == null) {
				throw new IllegalStateException("Mining is DISABLED or Dataset not loaded.");
			}

			activeVMs.incrementAndGet();
			try {
				return new RandomXVM(flags, verificationCache, miningDataset) {
					@Override
					public void close() {
						super.close();
						activeVMs.decrementAndGet();
					}
				};
			} catch (Exception e) {
				activeVMs.decrementAndGet(); // Revert increment if creation fails
				throw e;
			}
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	/**
	 * Creates a VM for Verification with a custom seed provider.
	 * Useful during sync when the seed block might not be in the DB yet.
	 */
	public RandomXVM getLightVMForVerification(long height,
			java.util.function.Function<Long, java.util.Optional<byte[]>> seedBlockProvider) {
		if (isShutdown)
			throw new IllegalStateException("Service is shutting down");

		byte[] requiredSeed = calculateSeedForHeight(height, seedBlockProvider);

		// Scenario 1: Current Epoch (Reuse Global Cache)
		lifecycleLock.readLock().lock();
		try {
			if (Arrays.equals(currentSeed, requiredSeed) && verificationCache != null) {
				// Ensure we don't pass FULL_MEM for light verification
				Set<RandomXFlag> lightFlags = EnumSet.copyOf(flags);
				lightFlags.remove(RandomXFlag.FULL_MEM);

				activeVMs.incrementAndGet();
				try {
					return new RandomXVM(lightFlags, verificationCache, null) {
						@Override
						public void close() {
							super.close();
							activeVMs.decrementAndGet();
						}
					};
				} catch (Exception e) {
					activeVMs.decrementAndGet();
					throw e;
				}
			}
		} finally {
			lifecycleLock.readLock().unlock();
		}

		// Scenario 2: Different Epoch (Syncing/Old Block)
		// Use cached VM if available to avoid expensive re-initialization
		return getOrCreateCachedVM(requiredSeed, height);
	}

	public RandomXVM getLightVMForVerification(long height) {
		return getLightVMForVerification(height, h -> chainQuery.getBlockHashByHeight(h).map(Hash::toArray));
	}

	/**
	 * Get or create a cached RandomX VM for the given seed.
	 * This dramatically speeds up sync across epochs by reusing the cache.
	 */
	private RandomXVM getOrCreateCachedVM(byte[] seed, long height) {
		Bytes seedKey = Bytes.wrap(seed);

		CachedRandomXVM cached = epochVMCache.computeIfAbsent(seedKey, k -> {
			log.info("Creating cached RandomX VM for epoch at height {} (will be reused for all blocks in this epoch)",
					height);
			Set<RandomXFlag> lightFlags = EnumSet.copyOf(flags);
			lightFlags.remove(RandomXFlag.FULL_MEM);
			lightFlags.remove(RandomXFlag.LARGE_PAGES);

			RandomXCache tempCache = new RandomXCache(lightFlags);
			try {
				tempCache.init(seed);
				return new CachedRandomXVM(tempCache, lightFlags, seedKey);
			} catch (Exception e) {
				tempCache.close();
				throw e;
			}
		});

		cached.lastUsed = System.currentTimeMillis();

		// Evict old caches if we have too many
		if (epochVMCache.size() > MAX_CACHED_EPOCHS) {
			evictOldestCache();
		}

		// Create a VM using the cached cache (VM creation is cheap, cache init is
		// expensive)
		activeVMs.incrementAndGet();
		try {
			return new RandomXVM(cached.flags, cached.cache, null) {
				@Override
				public void close() {
					super.close();
					activeVMs.decrementAndGet();
				}
			};
		} catch (Exception e) {
			activeVMs.decrementAndGet();
			throw e;
		}
	}

	private void evictOldestCache() {
		Bytes oldestKey = null;
		long oldestTime = Long.MAX_VALUE;

		for (Map.Entry<Bytes, CachedRandomXVM> entry : epochVMCache.entrySet()) {
			if (entry.getValue().lastUsed < oldestTime) {
				oldestTime = entry.getValue().lastUsed;
				oldestKey = entry.getKey();
			}
		}

		if (oldestKey != null) {
			CachedRandomXVM removed = epochVMCache.remove(oldestKey);
			if (removed != null) {
				log.debug("Evicting old RandomX epoch cache");
				removed.close();
			}
		}
	}

	/**
	 * Core logic to allocate memory, handle Huge Pages, and swap references.
	 * This method MUST be called under a WriteLock (or during single-threaded
	 * init).
	 */
	private void updateSeed(byte[] newSeed, boolean isInitial) {
		initializationInProgress.set(true);
		try {
			log.info("Starting RandomX memory initialization (initial: {}). Mining Enabled: {}", isInitial,
					miningProperties.getEnable());
			long start = System.currentTimeMillis();

			// 1. RELEASE PHASE (Release-First strategy to save RAM)
			if (!isInitial) {
				// SAFETY: Wait for all active VMs to release their references
				int retries = 0;
				while (activeVMs.get() > 0) {
					try {
						log.debug(
								"Waiting for {} active RandomX VMs to finish before freeing old memory... (Attempt {}/1200)",
								activeVMs.get(), retries);
						Thread.sleep(50);
						retries++;
						if (retries > 1200) { // 60 seconds
							log.error("Timed out waiting for active VMs. Force closing (Crash risk!)");
							break;
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						log.warn("Interrupted during memory cleanup wait.");
						break;
					}
				}

				if (miningDataset != null) {
					try {
						miningDataset.close();
					} catch (Exception e) {
						log.error("Error closing miningDataset", e);
					}
					miningDataset = null;
				}
				if (verificationCache != null) {
					try {
						verificationCache.close();
					} catch (Exception e) {
						log.error("Error closing verificationCache", e);
					}
					verificationCache = null;
				}

				// Explicit GC to encourage reclaiming native memory wrappers
				System.gc();
			}

			RandomXCache newCache = null;
			RandomXDataset newDataset = null;
			Set<RandomXFlag> appliedFlags = EnumSet.copyOf(flags);

			String osName = System.getProperty("os.name").toLowerCase();
			boolean isMac = osName.contains("mac") || osName.contains("darwin");
			boolean tryHugePages = !isMac && miningProperties.getEnable();

			// 2. ALLOCATION PHASE
			// Attempt Huge Pages first (if not on Mac)
			if (tryHugePages) {
				try {
					log.debug("Attempting to initialize RandomX with LARGE PAGES...");
					Set<RandomXFlag> fastFlags = EnumSet.copyOf(flags);
					fastFlags.add(RandomXFlag.LARGE_PAGES);
					if (miningProperties.getEnable()) {
						fastFlags.add(RandomXFlag.FULL_MEM);
					}

					newCache = new RandomXCache(fastFlags);
					newCache.init(newSeed);

					if (miningProperties.getEnable()) {
						newDataset = new RandomXDataset(fastFlags);
						newDataset.init(newCache);
					}

					appliedFlags = fastFlags;
					log.info("SUCCESS: RandomX initialized using LARGE PAGES.");

				} catch (Exception e) {
					log.warn("Failed to initialize with LARGE PAGES. Falling back to standard memory... (Error: {})",
							e.getMessage());
					// Clean up partial allocations
					if (newDataset != null)
						try {
							newDataset.close();
						} catch (Exception ex) {
						}
					if (newCache != null)
						try {
							newCache.close();
						} catch (Exception ex) {
						}
					newCache = null;
					newDataset = null;
				}
			}

			// Fallback to Standard Memory
			if (newCache == null) {
				try {
					Set<RandomXFlag> slowFlags = EnumSet.copyOf(flags);
					slowFlags.remove(RandomXFlag.LARGE_PAGES);
					if (miningProperties.getEnable()) {
						slowFlags.add(RandomXFlag.FULL_MEM);
					}

					newCache = new RandomXCache(slowFlags);
					newCache.init(newSeed);

					if (miningProperties.getEnable()) {
						newDataset = new RandomXDataset(slowFlags);
						newDataset.init(newCache);
					}

					appliedFlags = slowFlags;
					log.info("RandomX initialized using STANDARD memory.");

				} catch (Exception fatalError) {
					log.error("CRITICAL: Failed to initialize RandomX!", fatalError);
					if (newCache != null)
						try {
							newCache.close();
						} catch (Exception ex) {
						}
					if (fatalError instanceof InterruptedException
							|| fatalError.getCause() instanceof InterruptedException) {
						Thread.currentThread().interrupt();
					}
					throw new RuntimeException("RandomX initialization failed completely", fatalError);
				}
			}

			// 3. ASSIGNMENT PHASE
			this.verificationCache = newCache;
			this.miningDataset = newDataset;
			this.currentSeed = newSeed;
			this.flags = appliedFlags;

			log.info("RandomX memory update finished in {} ms.", System.currentTimeMillis() - start);
		} finally {
			initializationInProgress.set(false);
		}
	}

	private byte[] calculateSeedForHeight(long height) {
		return calculateSeedForHeight(height, h -> chainQuery.getBlockHashByHeight(h).map(Hash::toArray));
	}

	private byte[] calculateSeedForHeight(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		long epoch = height / Constants.RANDOMX_EPOCH_LENGTH;

		if (epoch == 0) {
			return Constants.RANDOMX_GENESIS_KEY.getBytes(StandardCharsets.UTF_8);
		}

		// Standard approach: Use the hash of the block that started the PREVIOUS epoch.
		// This ensures the seed is unpredictable until that block is mined, preventing
		// long-range pre-calculation.
		long seedBlockHeight = (epoch - 1) * Constants.RANDOMX_EPOCH_LENGTH;

		// Try the provider first (e.g. batch context)
		Optional<byte[]> seed = seedBlockProvider.apply(seedBlockHeight);
		if (seed.isPresent()) {
			return seed.get();
		}

		// Fallback to DB
		return chainQuery.getBlockHashByHeight(seedBlockHeight)
				.map(Hash::toArray)
				.orElseThrow(() -> new IllegalStateException(
						"Cannot calculate RandomX seed: Seed block at height " + seedBlockHeight + " not found."));
	}

	@PreDestroy
	public void close() {
		log.info("Shutting down RandomX service...");
		lifecycleLock.writeLock().lock();
		try {
			isShuttingDown = true;
			isShutdown = true;

			// Wait for active VMs to finish to avoid native crash (Use-After-Free)
			int retry = 0;
			while (activeVMs.get() > 0 && retry < 50) {
				try {
					// We hold the write lock, so no new VMs can be created.
					// We wait for existing ones to close.
					Thread.sleep(100);
					retry++;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			if (activeVMs.get() > 0) {
				log.warn(
						"RandomX service shutting down with {} active VMs still running! Skipping native memory release to prevent SIGSEGV.",
						activeVMs.get());
			} else {
				if (miningDataset != null) {
					try {
						miningDataset.close();
					} catch (Exception e) {
						log.error("Error closing miningDataset", e);
					}
					miningDataset = null;
				}

				if (verificationCache != null) {
					try {
						verificationCache.close();
					} catch (Exception e) {
						log.error("Error closing verificationCache", e);
					}
					verificationCache = null;
				}
				currentSeed = null;
				log.info("RandomX native memory released successfully.");
			}
		} finally {
			lifecycleLock.writeLock().unlock();
		}
	}
}