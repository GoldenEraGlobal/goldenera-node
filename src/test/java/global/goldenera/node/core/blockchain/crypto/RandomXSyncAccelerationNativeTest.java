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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationContext;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationSession;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.properties.RandomXSyncVerificationMode;
import global.goldenera.node.core.properties.RandomXVerificationProperties;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.sync.SyncVerificationAccelerationPolicy.EndReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Opt-in real-native performance proof for temporary AUTO sync acceleration. */
@Tag("resource-heavy")
class RandomXSyncAccelerationNativeTest {

	private static final int HASH_COUNT = 4_096;
	private static final int WORKERS = 8;
	private static final int COMPARISON_HASH_COUNT = 1_000;
	private static final int COMPARISON_WORKERS = 4;

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@EnabledIfSystemProperty(named = "goldenera.randomx.sync-full-tests", matches = "true",
			disabledReason = "Requires at least 12 GiB effective memory and native RandomX support")
	void autoBuildsUsesAndRetiresOneTemporaryFullDatasetForSameSeedBulkWork() throws Exception {
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(0L));
		MiningProperties mining = new MiningProperties();
		mining.setEnable(false);
		mining.setHashingThreads(1);
		mining.setMemoryMode(RandomXMiningMemoryMode.FULL);
		SandboxRuntimeContext runtime = new SandboxRuntimeContext(
				ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty());
		RandomXVerificationProperties verification = new RandomXVerificationProperties();
		verification.setParallelism(WORKERS);
		verification.setRebuildCooldown(Duration.ofMinutes(10));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RandomXManager manager = new RandomXManager(mining, chainQuery, runtime);
		manager.configureSyncVerificationAcceleration(verification, false, registry);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager, verification, registry);

		try {
			manager.init();
			provider.bulkCatchUpStarted(0, HASH_COUNT);
			ProofOfWorkVerificationContext context = provider.verificationContext(
					1L, ignored -> Optional.empty());
			long started = System.nanoTime();
			long checksum = hashSameSeedWorkload(
					provider, context, HASH_COUNT, WORKERS, ProofOfWorkVerificationMode.RANDOMX_FULL);
			long validationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

			assertThat(checksum).isNotZero();
			assertThat(registry.get("blockchain.randomx.sync_dataset.builds")
					.tags("outcome", "success").counter().count()).isEqualTo(1.0d);
			assertThat(registry.get("blockchain.randomx.sync_dataset.active").gauge().value()).isEqualTo(1.0d);
			assertThat(registry.get("blockchain.randomx.sync_dataset.bulk_active").gauge().value()).isEqualTo(1.0d);
			assertThat(registry.get("blockchain.randomx.verification.vm.created")
					.tags("mode", "randomx_full").counter().count()).isEqualTo(WORKERS);
			assertThat(registry.get("blockchain.randomx.verification.hashes")
					.tags("mode", "randomx_full").counter().count()).isEqualTo(HASH_COUNT);
			assertThat(registry.get("blockchain.randomx.verification.vm.reused")
					.tags("mode", "randomx_full").counter().count()).isEqualTo(HASH_COUNT - WORKERS);

			provider.syncEnded(EndReason.CAUGHT_UP);
			assertThat(registry.get("blockchain.randomx.sync_dataset.active").gauge().value()).isZero();
			assertThat(registry.get("blockchain.randomx.sync_dataset.bulk_active").gauge().value()).isZero();
			assertThat(manager.getActiveVmLeaseCount()).isZero();

			System.out.printf(
					"randomxSyncFull hashes=%d workers=%d validationMs=%d datasetInitMs=%.0f vmCreates=%.0f%n",
					HASH_COUNT,
					WORKERS,
					validationMillis,
					registry.get("blockchain.randomx.sync_dataset.build.duration").timer().totalTime(
							TimeUnit.MILLISECONDS),
					registry.get("blockchain.randomx.verification.vm.created")
							.tags("mode", "randomx_full").counter().count());
		} finally {
			manager.close();
			registry.close();
		}
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@EnabledIfSystemProperty(named = "goldenera.randomx.sync-full-tests", matches = "true",
			disabledReason = "Requires at least 12 GiB effective memory and native RandomX support")
	void comparesReusableLightAndFullSessionsOnFourWorkersWithoutSpeedThresholds() throws Exception {
		ModeResult light = runComparison(RandomXSyncVerificationMode.LIGHT,
				ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		ModeResult full = runComparison(RandomXSyncVerificationMode.AUTO,
				ProofOfWorkVerificationMode.RANDOMX_FULL);

		assertThat(full.checksum()).isEqualTo(light.checksum());
		assertThat(light.vmCreates()).isEqualTo(COMPARISON_WORKERS);
		assertThat(full.vmCreates()).isEqualTo(COMPARISON_WORKERS);
		assertThat(light.hashes()).isEqualTo(COMPARISON_HASH_COUNT);
		assertThat(full.hashes()).isEqualTo(COMPARISON_HASH_COUNT);
		assertThat(light.activeDatasetAfterCaughtUp()).isZero();
		assertThat(full.activeDatasetAfterCaughtUp()).isZero();

		System.out.printf(
				"randomxCompare mode=LIGHT hashes=%d workers=%d initMs=%d validationMs=%d hashesPerSecond=%.1f vmCreates=%d%n",
				COMPARISON_HASH_COUNT, COMPARISON_WORKERS, light.initMillis(), light.validationMillis(),
				light.hashesPerSecond(), light.vmCreates());
		System.out.printf(
				"randomxCompare mode=FULL hashes=%d workers=%d initMs=%d validationMs=%d hashesPerSecond=%.1f vmCreates=%d%n",
				COMPARISON_HASH_COUNT, COMPARISON_WORKERS, full.initMillis(), full.validationMillis(),
				full.hashesPerSecond(), full.vmCreates());
	}

	private ModeResult runComparison(
			RandomXSyncVerificationMode configuredMode,
			ProofOfWorkVerificationMode expectedMode) throws Exception {
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(0L));
		MiningProperties mining = new MiningProperties();
		mining.setEnable(false);
		mining.setHashingThreads(1);
		mining.setMemoryMode(RandomXMiningMemoryMode.FULL);
		SandboxRuntimeContext runtime = new SandboxRuntimeContext(
				ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty());
		RandomXVerificationProperties verification = new RandomXVerificationProperties();
		verification.setVerificationMode(configuredMode);
		verification.setParallelism(COMPARISON_WORKERS);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RandomXManager manager = new RandomXManager(mining, chainQuery, runtime);
		manager.configureSyncVerificationAcceleration(verification, false, registry);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager, verification, registry);

		try {
			manager.init();
			provider.bulkCatchUpStarted(0, 4_096);
			ProofOfWorkVerificationContext context = provider.verificationContext(
					1L, ignored -> Optional.empty());
			if (expectedMode == ProofOfWorkVerificationMode.RANDOMX_FULL) {
				try (ProofOfWorkVerificationSession session = provider.openVerificationSession(context)) {
					assertThat(session.mode()).isEqualTo(expectedMode);
				}
			}
			double vmCreatesBefore = counterValue(registry, "blockchain.randomx.verification.vm.created", expectedMode);
			double hashesBefore = counterValue(registry, "blockchain.randomx.verification.hashes", expectedMode);
			long started = System.nanoTime();
			long checksum = hashSameSeedWorkload(
					provider, context, COMPARISON_HASH_COUNT, COMPARISON_WORKERS, expectedMode);
			long validationNanos = System.nanoTime() - started;
			int vmCreates = (int) (counterValue(
					registry, "blockchain.randomx.verification.vm.created", expectedMode) - vmCreatesBefore);
			int hashes = (int) (counterValue(
					registry, "blockchain.randomx.verification.hashes", expectedMode) - hashesBefore);
			long initMillis = expectedMode == ProofOfWorkVerificationMode.RANDOMX_FULL
					? Math.round(registry.get("blockchain.randomx.sync_dataset.build.duration")
							.timer().totalTime(TimeUnit.MILLISECONDS))
					: 0L;
			provider.syncEnded(EndReason.CAUGHT_UP);
			double activeAfterCaughtUp = registry.get("blockchain.randomx.sync_dataset.active")
					.gauge().value();
			assertThat(manager.getActiveVmLeaseCount()).isZero();
			return new ModeResult(
					checksum,
					initMillis,
					TimeUnit.NANOSECONDS.toMillis(validationNanos),
					COMPARISON_HASH_COUNT * 1_000_000_000.0d / validationNanos,
					vmCreates,
					hashes,
					activeAfterCaughtUp);
		} finally {
			manager.close();
			registry.close();
		}
	}

	private double counterValue(
			SimpleMeterRegistry registry,
			String name,
			ProofOfWorkVerificationMode mode) {
		var counter = registry.find(name)
				.tags("mode", mode.name().toLowerCase(Locale.ROOT))
				.counter();
		return counter == null ? 0.0d : counter.count();
	}

	private long hashSameSeedWorkload(
			RandomXProofOfWorkProvider provider,
			ProofOfWorkVerificationContext context,
			int hashCount,
			int workers,
			ProofOfWorkVerificationMode expectedMode) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		try {
			List<Callable<Long>> tasks = new ArrayList<>(workers);
			for (int worker = 0; worker < workers; worker++) {
				int workerIndex = worker;
				tasks.add(() -> {
					long checksum = 0L;
					try (ProofOfWorkVerificationSession session = provider.openVerificationSession(context)) {
						assertThat(session.mode()).isEqualTo(expectedMode);
						for (int index = workerIndex; index < hashCount; index += workers) {
							byte[] input = ByteBuffer.allocate(Long.BYTES * 2)
									.putLong(0x676f6c64656e6572L)
									.putLong(index)
									.array();
							byte[] hash = session.hash(input);
							checksum ^= ByteBuffer.wrap(hash).getLong();
						}
					}
					return checksum;
				});
			}
			long checksum = 0L;
			for (Future<Long> future : executor.invokeAll(tasks)) {
				checksum ^= future.get();
			}
			return checksum;
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private record ModeResult(
			long checksum,
			long initMillis,
			long validationMillis,
			double hashesPerSecond,
			int vmCreates,
			int hashes,
			double activeDatasetAfterCaughtUp) {
	}
}
