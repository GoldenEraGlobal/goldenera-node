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
package global.goldenera.node.core.blockchain.pow;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.crypto.RandomXVmLease;
import global.goldenera.node.core.blockchain.crypto.RandomXVerificationVmLease;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.properties.RandomXVerificationProperties;
import global.goldenera.node.core.sync.SyncVerificationAccelerationPolicy.EndReason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Production proof-of-work provider preserving the existing RandomX modes:
 * dataset-backed hashing for mining and cache-only hashing for verification.
 */
public class RandomXProofOfWorkProvider implements ProofOfWorkProvider {

	private final RandomXManager randomXManager;
	private final MeterRegistry registry;
	private final Semaphore verificationPermits;
	private final Semaphore fullDatasetVerificationPermits;
	private final int verificationParallelism;
	private final int fullDatasetVerificationParallelism;
	private final AtomicInteger activeVerificationWorkers = new AtomicInteger();

	public RandomXProofOfWorkProvider(RandomXManager randomXManager) {
		this(randomXManager, new RandomXVerificationProperties(), new SimpleMeterRegistry(),
				Runtime.getRuntime().availableProcessors());
	}

	public RandomXProofOfWorkProvider(RandomXManager randomXManager,
			RandomXVerificationProperties verificationProperties,
			MeterRegistry registry) {
		this(randomXManager, verificationProperties, registry, Runtime.getRuntime().availableProcessors());
	}

	RandomXProofOfWorkProvider(RandomXManager randomXManager,
			RandomXVerificationProperties verificationProperties,
			MeterRegistry registry,
			int availableProcessors) {
		this.randomXManager = Objects.requireNonNull(randomXManager, "randomXManager");
		this.registry = Objects.requireNonNull(registry, "registry");
		this.verificationParallelism = Objects.requireNonNull(
				verificationProperties, "verificationProperties").resolveParallelism(availableProcessors);
		this.fullDatasetVerificationParallelism =
				verificationProperties.resolveFullDatasetParallelism(availableProcessors);
		this.verificationPermits = new Semaphore(verificationParallelism, true);
		this.fullDatasetVerificationPermits = new Semaphore(fullDatasetVerificationParallelism, true);
		registry.gauge("blockchain.randomx.verification.active_workers", activeVerificationWorkers,
				AtomicInteger::get);
		registry.gauge("blockchain.randomx.verification.worker_limit", verificationParallelism);
		registry.gauge("blockchain.randomx.verification.full_dataset_worker_limit",
				fullDatasetVerificationParallelism);
	}

	@Override
	public void prepareForMining(long height) {
		try {
			randomXManager.prepareMiningResourcesForHeight(height);
		} catch (ProofOfWorkMiningException failure) {
			throw failure;
		} catch (RuntimeException | LinkageError failure) {
			throw new ProofOfWorkMiningException(
					"Failed to initialize RandomX mining resources for height " + height,
					failure);
		}
	}

	@Override
	public ProofOfWorkHasher openMiningHasher() {
		RandomXVmLease lease = randomXManager.createMiningVM();
		return new ProofOfWorkHasher(lease::calculateHash, lease::close);
	}

	@Override
	public ProofOfWorkVerificationContext verificationContext(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		return randomXManager.verificationContext(height, seedBlockProvider);
	}

	@Override
	public ProofOfWorkVerificationSession openVerificationSession(ProofOfWorkVerificationContext context) {
		acquireVerificationPermit();
		RandomXVerificationVmLease lease;
		try {
			lease = randomXManager.acquireVerificationVM(context);
		} catch (RuntimeException | Error failure) {
			verificationPermits.release();
			throw failure;
		}
		boolean fullDatasetPermit = false;
		try {
			if (lease.mode() == ProofOfWorkVerificationMode.RANDOMX_FULL) {
				acquirePermit(fullDatasetVerificationPermits);
				fullDatasetPermit = true;
			}
		} catch (RuntimeException | Error failure) {
			lease.close();
			verificationPermits.release();
			throw failure;
		}
		boolean releaseFullDatasetPermit = fullDatasetPermit;
		String mode = lease.mode().name().toLowerCase();
		activeVerificationWorkers.incrementAndGet();
		registry.counter("blockchain.randomx.verification.vm.created", "mode", mode).increment();
		AtomicLong hashes = new AtomicLong();
		return new ProofOfWorkVerificationSession(
				context,
				lease.mode(),
				input -> {
					long started = System.nanoTime();
					try {
						long index = hashes.getAndIncrement();
						if (index > 0) {
							registry.counter("blockchain.randomx.verification.vm.reused", "mode", mode)
									.increment();
						}
						registry.counter("blockchain.randomx.verification.hashes", "mode", mode).increment();
						return lease.vm().calculateHash(input);
					} finally {
						registry.timer("blockchain.randomx.verification.hash.duration", "mode", mode)
								.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
					}
				},
				() -> {
					try {
						lease.close();
					} finally {
						if (releaseFullDatasetPermit) {
							fullDatasetVerificationPermits.release();
						}
						registry.counter("blockchain.randomx.verification.vm.closed", "mode", mode).increment();
						activeVerificationWorkers.decrementAndGet();
						verificationPermits.release();
					}
				});
	}

	@Override
	public boolean isInitializationInProgress() {
		return randomXManager.isInitializationInProgress();
	}

	@Override
	public int verificationConcurrencyLimit(int availableProcessors) {
		return Math.max(1, Math.min(verificationParallelism, availableProcessors));
	}

	public boolean isDatasetAllocated() {
		return randomXManager.isDatasetAllocated();
	}

	public int getActiveVmLeaseCount() {
		return randomXManager.getActiveVmLeaseCount();
	}

	public RandomXMiningMemoryMode getMiningMemoryMode() {
		return randomXManager.getMiningMemoryMode();
	}

	public void bulkCatchUpStarted(long localHeight, long targetHeight) {
		randomXManager.syncBulkCatchUpStarted(localHeight, targetHeight);
	}

	public void progress(long localHeight, long targetHeight) {
		randomXManager.syncProgress(localHeight, targetHeight);
	}

	public void syncEnded(EndReason reason) {
		switch (reason) {
			case CAUGHT_UP, NEAR_HEAD -> randomXManager.syncCaughtUp();
			case FAILED -> randomXManager.syncFailed();
			case STOPPED -> randomXManager.syncStopped();
		}
	}

	private void acquireVerificationPermit() {
		acquirePermit(verificationPermits);
	}

	private void acquirePermit(Semaphore permits) {
		try {
			permits.acquire();
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for a RandomX verification lease", failure);
		}
	}

}
