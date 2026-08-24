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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationContext;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationSession;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.properties.RandomXVerificationProperties;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.sync.SyncVerificationAccelerationPolicy;
import global.goldenera.randomx.RandomXCache;
import global.goldenera.randomx.RandomXDataset;
import global.goldenera.randomx.RandomXFlag;
import global.goldenera.randomx.RandomXVM;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class RandomXManagerTest {

	@Test
	void productionDefaultsToFullAndRejectsLightEvenWhenMiningIsDisabled() {
		MiningProperties full = properties(false, RandomXMiningMemoryMode.FULL);
		RandomXManager fullManager = manager(full, productionContext(), new RecordingFactory());

		assertThat(fullManager.getMiningMemoryMode()).isEqualTo(RandomXMiningMemoryMode.FULL);

		MiningProperties light = properties(false, RandomXMiningMemoryMode.LIGHT);
		assertThatThrownBy(() -> manager(light, productionContext(), new RecordingFactory()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("LIGHT")
				.hasMessageContaining("sandbox");
	}

	@Test
	void lightMiningUsesCacheOnlyFlagsAndIdempotentLeaseAccounting() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.LIGHT), sandboxContext(), factory);

		manager.ensureInitializedForHeight(7L);
		RandomXVmLease lease = manager.createMiningVM();

		assertThat(factory.datasets).isEmpty();
		assertThat(factory.cacheFlags).singleElement().satisfies(flags -> {
			assertThat(flags).doesNotContain(RandomXFlag.FULL_MEM, RandomXFlag.LARGE_PAGES);
		});
		assertThat(factory.vmRequests).singleElement().satisfies(request -> {
			assertThat(request.flags()).doesNotContain(RandomXFlag.FULL_MEM, RandomXFlag.LARGE_PAGES);
			assertThat(request.dataset()).isNull();
		});
		assertThat(manager.getActiveVmLeaseCount()).isEqualTo(1);

		lease.close();
		lease.close();

		assertThat(manager.getActiveVmLeaseCount()).isZero();
		verify(factory.vms.getFirst(), times(1)).close();
		manager.close();
		verify(factory.caches.getFirst(), times(1)).close();
	}

	@Test
	void epochSwitchRetiresButDoesNotFreeResourcesBackingAnActiveVm() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.LIGHT), sandboxContext(), factory);
		manager.ensureInitializedForHeight(1L);
		RandomXVmLease oldEpochLease = manager.createMiningVM();
		RandomXCache oldCache = factory.caches.getFirst();

		manager.ensureInitializedForHeight(2L);

		assertThat(factory.caches).hasSize(2);
		verify(oldCache, never()).close();
		oldEpochLease.close();
		verify(oldCache, times(1)).close();
		assertThat(manager.getActiveVmLeaseCount()).isZero();

		manager.close();
		verify(factory.caches.get(1), times(1)).close();
	}

	@Test
	void shutdownDefersNativeReleaseUntilLastLeaseCloses() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.LIGHT), sandboxContext(), factory);
		manager.ensureInitializedForHeight(1L);
		RandomXVmLease lease = manager.createMiningVM();
		RandomXCache cache = factory.caches.getFirst();

		manager.close();

		verify(cache, never()).close();
		assertThatThrownBy(manager::createMiningVM)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("shutting down");
		assertThatThrownBy(() -> manager.ensureInitializedForHeight(2L))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("shutting down");
		lease.close();
		verify(cache, times(1)).close();
		assertThat(manager.getActiveVmLeaseCount()).isZero();
	}

	@Test
	void vmCloseFailureStillReleasesTheEpochLease() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.LIGHT), sandboxContext(), factory);
		manager.ensureInitializedForHeight(1L);
		RandomXVmLease lease = manager.createMiningVM();
		RandomXVM vm = factory.vms.getFirst();
		when(vm.calculateHash(any())).thenReturn(new byte[32]);
		doThrow(new IllegalStateException("native close failed")).when(vm).close();

		assertThatThrownBy(lease::close)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("native close failed");
		assertThat(manager.getActiveVmLeaseCount()).isZero();

		manager.close();
		verify(factory.caches.getFirst(), times(1)).close();
	}

	@Test
	void historicalEpochEvictionDefersCacheReleaseWhileLeaseIsActive() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.LIGHT), sandboxContext(), factory);
		manager.ensureInitializedForHeight(0L);
		RandomXVmLease oldestHistoricalLease = manager.getLightVMForVerification(1L, ignored -> Optional.empty());
		RandomXCache oldestHistoricalCache = factory.caches.get(1);

		manager.getLightVMForVerification(2L, ignored -> Optional.empty()).close();
		manager.getLightVMForVerification(3L, ignored -> Optional.empty()).close();
		manager.getLightVMForVerification(4L, ignored -> Optional.empty()).close();

		verify(oldestHistoricalCache, never()).close();
		oldestHistoricalLease.close();
		verify(oldestHistoricalCache, times(1)).close();
		assertThat(manager.getActiveVmLeaseCount()).isZero();
		manager.close();
	}

	@Test
	void nativeInitializationFailureIsExplicitAndLeavesNoCurrentEpoch() {
		RandomXResourceFactory factory = mock(RandomXResourceFactory.class);
		when(factory.createCache(any())).thenThrow(new UnsatisfiedLinkError("native unavailable"));
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.LIGHT), sandboxContext(), factory);

		assertThatThrownBy(() -> manager.ensureInitializedForHeight(1L))
				.isInstanceOf(RandomXInitializationException.class)
				.hasMessageContaining("native RandomX LIGHT")
				.hasRootCauseInstanceOf(UnsatisfiedLinkError.class);
		assertThatThrownBy(manager::createMiningVM)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not initialized");
	}

	@Test
	void verificationReusesAnAlreadyAllocatedFullDataset() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);

		RandomXVmLease miningLease = manager.createMiningVM();
		ProofOfWorkVerificationContext context = manager.verificationContext(
				1L, ignored -> Optional.empty());
		RandomXVerificationVmLease verificationLease = manager.acquireVerificationVM(context);

		assertThat(factory.datasets).hasSize(1);
		assertThat(factory.vmRequests).hasSize(2);
		assertThat(factory.vmRequests.get(0).flags()).contains(RandomXFlag.FULL_MEM);
		assertThat(factory.vmRequests.get(0).dataset()).isSameAs(factory.datasets.getFirst());
		assertThat(factory.vmRequests.get(1).flags()).contains(RandomXFlag.FULL_MEM);
		assertThat(factory.vmRequests.get(1).dataset()).isSameAs(factory.datasets.getFirst());
		assertThat(verificationLease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);

		miningLease.close();
		verificationLease.close();
		manager.close();
	}

	@Test
	void verificationFallsBackToLightWhenNoDatasetExists() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		manager.ensureInitializedForHeight(1L);

		ProofOfWorkVerificationContext context = manager.verificationContext(
				1L, ignored -> Optional.empty());
		RandomXVerificationVmLease verificationLease = manager.acquireVerificationVM(context);

		assertThat(factory.datasets).isEmpty();
		assertThat(factory.vmRequests).singleElement().satisfies(request -> {
			assertThat(request.flags()).doesNotContain(RandomXFlag.FULL_MEM, RandomXFlag.LARGE_PAGES);
			assertThat(request.dataset()).isNull();
		});
		assertThat(verificationLease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);

		verificationLease.close();
		manager.close();
	}

	@Test
	void autoBulkSyncBuildsOneParityCheckedDatasetAndRetiresAfterLastLeaseAtTail() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(2L, ignored -> Optional.empty());

		RandomXVerificationVmLease first = manager.acquireVerificationVM(context);
		RandomXVerificationVmLease second = manager.acquireVerificationVM(context);

		assertThat(first.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
		assertThat(second.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
		assertThat(factory.datasets).hasSize(1);
		RandomXDataset dataset = factory.datasets.getFirst();
		manager.syncCaughtUp();
		verify(dataset, never()).close();
		first.close();
		verify(dataset, never()).close();
		second.close();
		verify(dataset, times(1)).close();
		manager.close();
	}

	@Test
	void failedSyncRetiresNativeDatasetAfterLeaseDrainAndKeepsOnlyCooldownMetadata() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(2L, ignored -> Optional.empty());
		RandomXVerificationVmLease lease = manager.acquireVerificationVM(context);
		RandomXDataset dataset = factory.datasets.getFirst();

		manager.syncFailed();
		verify(dataset, never()).close();
		lease.close();
		verify(dataset, times(1)).close();

		manager.syncBulkCatchUpStarted(2L, 10_000L);
		ProofOfWorkVerificationContext reconnect = manager.verificationContext(3L, ignored -> Optional.empty());
		try (RandomXVerificationVmLease fallback = manager.acquireVerificationVM(reconnect)) {
			assertThat(fallback.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}
		assertThat(factory.datasets).hasSize(1);
		manager.close();
	}

	@Test
	void smallGapAndImmediatePostCatchupReconnectStayLightWithoutRebuild() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 100L);
		ProofOfWorkVerificationContext smallGap = manager.verificationContext(2L, ignored -> Optional.empty());

		try (RandomXVerificationVmLease lease = manager.acquireVerificationVM(smallGap)) {
			assertThat(lease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}
		assertThat(factory.datasets).isEmpty();

		manager.syncCaughtUp();
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext bulk = manager.verificationContext(2L, ignored -> Optional.empty());
		manager.acquireVerificationVM(bulk).close();
		assertThat(factory.datasets).hasSize(1);
		manager.syncCaughtUp();
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext cooldown = manager.verificationContext(3L, ignored -> Optional.empty());
		try (RandomXVerificationVmLease lease = manager.acquireVerificationVM(cooldown)) {
			assertThat(lease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}
		assertThat(factory.datasets).hasSize(1);
		manager.close();
	}

	@Test
	void lateMainnetEpochDefersDatasetUntilNextFullEpochCanAmortizeIt() {
		RecordingFactory factory = new RecordingFactory();
		ChainQuery chainQuery = mock(ChainQuery.class);
		NetworkSettings settings = mock(NetworkSettings.class);
		when(settings.randomXEpochLength()).thenReturn(8192L);
		when(settings.randomXGenesisKey()).thenReturn("controlled-genesis-key");
		RandomXManager manager = new RandomXManager(
				properties(false, RandomXMiningMemoryMode.FULL), chainQuery, productionContext(), factory,
				() -> settings);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(0L);
		manager.syncBulkCatchUpStarted(0L, 20_000L);

		ProofOfWorkVerificationContext lateEpoch = manager.verificationContext(
				3000L, ignored -> Optional.empty());
		try (RandomXVerificationVmLease lease = manager.acquireVerificationVM(lateEpoch)) {
			assertThat(lease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}
		assertThat(factory.datasets).isEmpty();

		byte[] nextSeed = new byte[] { 9, 8, 7 };
		ProofOfWorkVerificationContext nextEpoch = manager.verificationContext(
				8192L, ignored -> Optional.of(nextSeed));
		try (RandomXVerificationVmLease lease = manager.acquireVerificationVM(nextEpoch)) {
			assertThat(lease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
		}
		assertThat(factory.datasets).hasSize(1);
		manager.close();
	}

	@Test
	void parityFailureCircuitsSeedAndFallsBackToLightWithoutRebuilding() {
		RecordingFactory factory = new RecordingFactory();
		factory.parityMismatch = true;
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(2L, ignored -> Optional.empty());

		try (RandomXVerificationVmLease first = manager.acquireVerificationVM(context);
				RandomXVerificationVmLease second = manager.acquireVerificationVM(context)) {
			assertThat(first.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			assertThat(second.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}

		assertThat(factory.datasets).hasSize(1);
		verify(factory.datasets.getFirst(), times(1)).close();
		manager.close();
	}

	@Test
	void datasetInitializationFailureCircuitsSeedAndFallsBackToLight() {
		RecordingFactory factory = new RecordingFactory();
		factory.failDatasetInitialization = true;
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(2L, ignored -> Optional.empty());

		try (RandomXVerificationVmLease first = manager.acquireVerificationVM(context);
				RandomXVerificationVmLease second = manager.acquireVerificationVM(context)) {
			assertThat(first.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			assertThat(second.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}

		assertThat(factory.datasets).hasSize(1);
		verify(factory.datasets.getFirst(), times(1)).close();
		manager.close();
	}

	@Test
	void seedAdvanceRetiresOldSyncDatasetBeforePublishingReplacement() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext firstContext = manager.verificationContext(2L, ignored -> Optional.empty());
		manager.acquireVerificationVM(firstContext).close();
		RandomXDataset firstDataset = factory.datasets.getFirst();

		ProofOfWorkVerificationContext secondContext = manager.verificationContext(3L, ignored -> Optional.empty());
		try (RandomXVerificationVmLease lease = manager.acquireVerificationVM(secondContext)) {
			assertThat(lease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
		}

		assertThat(factory.datasets).hasSize(2);
		verify(firstDataset, times(1)).close();
		manager.syncFailed();
		verify(factory.datasets.get(1), times(1)).close();
		manager.close();
	}

	@Test
	void concurrentSameSeedAcquisitionSingleFlightsDatasetBuild() throws Exception {
		RecordingFactory factory = new RecordingFactory();
		factory.blockDatasetInitialization = true;
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(2L, ignored -> Optional.empty());

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<RandomXVerificationVmLease> first = executor.submit(() -> manager.acquireVerificationVM(context));
			assertThat(factory.datasetInitStarted.await(1, TimeUnit.SECONDS)).isTrue();
			Future<RandomXVerificationVmLease> second = executor.submit(() -> manager.acquireVerificationVM(context));
			Thread.sleep(50);
			assertThat(factory.datasets).hasSize(1);
			factory.releaseDatasetInit.countDown();
			try (RandomXVerificationVmLease firstLease = first.get(1, TimeUnit.SECONDS);
					RandomXVerificationVmLease secondLease = second.get(1, TimeUnit.SECONDS)) {
				assertThat(firstLease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
				assertThat(secondLease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
			}
		}
		assertThat(factory.datasets).hasSize(1);
		manager.close();
	}

	@Test
	void shutdownDuringDatasetBuildClosesCandidateWithoutPublishingIt() throws Exception {
		RecordingFactory factory = new RecordingFactory();
		factory.blockDatasetInitialization = true;
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(2L, ignored -> Optional.empty());

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<RandomXVerificationVmLease> acquisition =
					executor.submit(() -> manager.acquireVerificationVM(context));
			assertThat(factory.datasetInitStarted.await(1, TimeUnit.SECONDS)).isTrue();
			Future<?> shutdown = executor.submit(manager::close);
			Thread.sleep(50);
			factory.releaseDatasetInit.countDown();
			try {
				RandomXVerificationVmLease fallback = acquisition.get(1, TimeUnit.SECONDS);
				fallback.close();
			} catch (ExecutionException ignored) {
				// Shutdown may win before the mandatory LIGHT fallback acquires its cache.
			}
			shutdown.get(1, TimeUnit.SECONDS);
		}

		assertThat(factory.datasets).hasSize(1);
		verify(factory.datasets.getFirst(), times(1)).close();
	}

	@Test
	void acceleratorPublishesLifecycleAndBuildMetrics() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		manager.configureSyncVerificationAcceleration(
				highMemoryProperties(), false, registry, this::highMemory);
		manager.ensureInitializedForHeight(1L);
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(2L, ignored -> Optional.empty());
		manager.acquireVerificationVM(context).close();

		assertThat(registry.get("blockchain.randomx.sync_dataset.builds")
				.tag("outcome", "success").tag("reason", "none").counter().count()).isEqualTo(1);
		assertThat(registry.get("blockchain.randomx.sync_dataset.active").gauge().value()).isEqualTo(1);
		assertThat(registry.get("blockchain.randomx.sync_dataset.bulk_active").gauge().value()).isEqualTo(1);

		manager.syncCaughtUp();
		assertThat(registry.get("blockchain.randomx.sync_dataset.active").gauge().value()).isZero();
		assertThat(registry.get("blockchain.randomx.sync_dataset.bulk_active").gauge().value()).isZero();
		assertThat(registry.get("blockchain.randomx.sync_dataset.bulk_transitions")
				.tag("state", "entered").tag("reason", "catch_up_gap").counter().count()).isEqualTo(1);
		assertThat(registry.get("blockchain.randomx.sync_dataset.bulk_transitions")
				.tag("state", "exited").tag("reason", "caught_up").counter().count()).isEqualTo(1);
		assertThat(registry.scrape()).contains(
				"blockchain_randomx_sync_dataset_bulk_transitions_total{reason=\"catch_up_gap\",state=\"entered\"} 1.0",
				"blockchain_randomx_sync_dataset_bulk_transitions_total{reason=\"caught_up\",state=\"exited\"} 1.0");
		manager.close();
	}

	@Test
	void matchingMiningDatasetIsReusedInBulkAndNeverRetiredBySync() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		RandomXDataset miningDataset = factory.datasets.getFirst();
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(1L, ignored -> Optional.empty());

		try (RandomXVerificationVmLease lease = manager.acquireVerificationVM(context)) {
			assertThat(lease.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
		}
		assertThat(factory.datasets).containsExactly(miningDataset);
		manager.syncFailed();
		verify(miningDataset, never()).close();
		try (RandomXVerificationVmLease nearHead = manager.acquireVerificationVM(context)) {
			assertThat(nearHead.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
		}
		verify(miningDataset, never()).close();
		manager.close();
		verify(miningDataset, times(1)).close();
	}

	@Test
	void concurrentEpochSwitchCannotRetireCurrentDatasetBeforeVerificationLeaseIsAcquired() throws Exception {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		RandomXDataset firstDataset = factory.datasets.getFirst();
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(1L, ignored -> Optional.empty());
		factory.blockVerificationVmCreation = true;

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<RandomXVerificationVmLease> acquisition =
					executor.submit(() -> manager.acquireVerificationVM(context));
			assertThat(factory.verificationVmCreationStarted.await(1, TimeUnit.SECONDS)).isTrue();
			Future<?> epochSwitch = executor.submit(() -> manager.ensureInitializedForHeight(2L));
			Thread.sleep(50);
			assertThat(epochSwitch.isDone()).isFalse();

			factory.releaseVerificationVmCreation.countDown();
			RandomXVerificationVmLease lease = acquisition.get(1, TimeUnit.SECONDS);
			epochSwitch.get(1, TimeUnit.SECONDS);
			verify(firstDataset, never()).close();
			lease.close();
			verify(firstDataset, times(1)).close();
		}
		manager.close();
	}

	@Test
	void borrowedMiningDatasetRemainsUsableThroughEverySyncTerminalCallback() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		RandomXDataset miningDataset = factory.datasets.getFirst();
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(1L, ignored -> Optional.empty());
		RandomXVerificationVmLease borrowed = manager.acquireVerificationVM(context);

		manager.syncCaughtUp();
		assertThat(borrowed.vm().calculateHash(new byte[] { 1 })).hasSize(32);
		verify(miningDataset, never()).close();
		manager.syncFailed();
		assertThat(borrowed.vm().calculateHash(new byte[] { 2 })).hasSize(32);
		verify(miningDataset, never()).close();
		manager.syncStopped();
		assertThat(borrowed.vm().calculateHash(new byte[] { 3 })).hasSize(32);
		verify(miningDataset, never()).close();

		borrowed.close();
		verify(miningDataset, never()).close();
		manager.close();
		verify(miningDataset, times(1)).close();
	}

	@Test
	void concurrentShutdownDefersMiningDatasetUntilMiningAndVerificationLeasesCloseInEitherOrder()
			throws Exception {
		assertConcurrentShutdownLeaseOrder(true);
		assertConcurrentShutdownLeaseOrder(false);
	}

	@Test
	void oldSeedBorrowRacingFailedAndStoppedIsReleasedOnlyByCurrentEpochRetirement() throws Exception {
		for (boolean stopped : List.of(false, true)) {
			RecordingFactory factory = new RecordingFactory();
			RandomXManager manager = manager(
					properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
			configureAcceleration(manager, highMemoryProperties(), highMemory());
			manager.ensureInitializedForHeight(1L);
			RandomXDataset oldDataset = factory.datasets.getFirst();
			manager.syncBulkCatchUpStarted(1L, 10_000L);
			ProofOfWorkVerificationContext context = manager.verificationContext(1L, ignored -> Optional.empty());
			RandomXVerificationVmLease borrowed = manager.acquireVerificationVM(context);
			CountDownLatch start = new CountDownLatch(1);

			try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
				Future<?> epochSwitch = executor.submit(() -> {
					await(start);
					manager.ensureInitializedForHeight(2L);
				});
				Future<?> terminal = executor.submit(() -> {
					await(start);
					if (stopped) {
						manager.syncStopped();
					} else {
						manager.syncFailed();
					}
				});
				start.countDown();
				epochSwitch.get(1, TimeUnit.SECONDS);
				terminal.get(1, TimeUnit.SECONDS);
			}

			verify(oldDataset, never()).close();
			assertThat(borrowed.vm().calculateHash(new byte[] { 4 })).hasSize(32);
			borrowed.close();
			verify(oldDataset, times(1)).close();
			manager.close();
		}
	}

	@Test
	void syncBorrowWaitsForNonInterruptibleMiningInitializationAfterPauseTimeoutSeamWithoutDeadlock()
			throws Exception {
		RecordingFactory factory = new RecordingFactory();
		RandomXVerificationProperties verificationProperties = highMemoryProperties();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		manager.configureSyncVerificationAcceleration(
				verificationProperties, false, registry, this::highMemory);
		manager.ensureInitializedForHeight(1L);
		factory.blockDatasetInitialization = true;
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(
				manager, verificationProperties, registry);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> miningInitialization = executor.submit(() -> manager.prepareMiningResourcesForHeight(2L));
			assertThat(factory.datasetInitStarted.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(manager.isInitializationInProgress()).isTrue();
			provider.bulkCatchUpStarted(1L, 10_000L);
			ProofOfWorkVerificationContext context = provider.verificationContext(2L, ignored -> Optional.empty());
			CountDownLatch acquisitionStarted = new CountDownLatch(1);
			Future<ProofOfWorkVerificationSession> syncBorrow = executor.submit(() -> {
				acquisitionStarted.countDown();
				return provider.openVerificationSession(context);
			});
			assertThat(acquisitionStarted.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(syncBorrow.isDone()).isFalse();

			factory.releaseDatasetInit.countDown();
			miningInitialization.get(1, TimeUnit.SECONDS);
			try (ProofOfWorkVerificationSession session = syncBorrow.get(1, TimeUnit.SECONDS)) {
				assertThat(session.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
				assertThat(session.hash(new byte[] { 5 })).hasSize(32);
			}
		}
		provider.syncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP);
		manager.close();
	}

	@Test
	void insufficientHugePagesSkipTheNativeLargePageAttempt() {
		RecordingFactory factory = new RecordingFactory();
		ChainQuery chainQuery = mock(ChainQuery.class);
		RandomXManager manager = new RandomXManager(
				properties(true, RandomXMiningMemoryMode.FULL), chainQuery, productionContext(), factory,
				height -> new byte[] { (byte) height, 42 },
				() -> new RandomXLargePageSupport.Availability(false, "test pool is too small"));

		manager.ensureInitializedForHeight(1L);

		assertThat(factory.cacheFlags).singleElement().satisfies(flags -> {
			assertThat(flags).contains(RandomXFlag.FULL_MEM);
			assertThat(flags).doesNotContain(RandomXFlag.LARGE_PAGES);
		});
		assertThat(factory.datasets).hasSize(1);
		manager.close();
	}

	@Test
	void explicitFullMiningUpgradesDisabledAutonomousCacheAtTheSameEpoch() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		manager.ensureInitializedForHeight(1L);

		assertThat(factory.datasets).isEmpty();

		manager.prepareMiningResourcesForHeight(1L);
		RandomXVmLease lease = manager.createMiningVM();

		assertThat(factory.datasets).hasSize(1);
		assertThat(factory.vmRequests).singleElement().satisfies(request -> {
			assertThat(request.flags()).contains(RandomXFlag.FULL_MEM);
			assertThat(request.dataset()).isSameAs(factory.datasets.getFirst());
		});
		lease.close();
		manager.close();
	}

	@Test
	void explicitLightMiningWithAutonomousDisabledRemainsCacheOnly() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(false, RandomXMiningMemoryMode.LIGHT), sandboxContext(), factory);

		manager.prepareMiningResourcesForHeight(1L);
		RandomXVmLease lease = manager.createMiningVM();

		assertThat(factory.datasets).isEmpty();
		assertThat(factory.vmRequests).singleElement().satisfies(request -> {
			assertThat(request.flags()).doesNotContain(RandomXFlag.FULL_MEM, RandomXFlag.LARGE_PAGES);
			assertThat(request.dataset()).isNull();
		});
		lease.close();
		manager.close();
	}

	@Test
	void realSeedDerivationUsesGenesisKeyUntilFirstEpochBoundary() {
		RecordingFactory factory = new RecordingFactory();
		ChainQuery chainQuery = mock(ChainQuery.class);
		NetworkSettings settings = mock(NetworkSettings.class);
		when(settings.randomXEpochLength()).thenReturn(10L);
		when(settings.randomXGenesisKey()).thenReturn("controlled-genesis-key");
		RandomXManager manager = new RandomXManager(
				properties(true, RandomXMiningMemoryMode.LIGHT), chainQuery, sandboxContext(), factory,
				() -> settings);

		manager.ensureInitializedForHeight(0L);
		manager.ensureInitializedForHeight(9L);

		ArgumentCaptor<byte[]> seed = ArgumentCaptor.forClass(byte[].class);
		verify(factory.caches.getFirst()).init(seed.capture());
		assertThat(seed.getValue()).containsExactly(
				"controlled-genesis-key".getBytes(StandardCharsets.UTF_8));
		assertThat(factory.caches).hasSize(1);
		verify(chainQuery, never()).getBlockHashByHeight(anyLong());
		manager.close();
	}

	@Test
	void realSeedDerivationUsesPreviousEpochStartBlockAtExactBoundary() {
		RecordingFactory factory = new RecordingFactory();
		ChainQuery chainQuery = mock(ChainQuery.class);
		Hash genesisBlockHash = Hash.fromHexString(
				"0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
		when(chainQuery.getBlockHashByHeight(0L)).thenReturn(Optional.of(genesisBlockHash));
		NetworkSettings settings = mock(NetworkSettings.class);
		when(settings.randomXEpochLength()).thenReturn(10L);
		when(settings.randomXGenesisKey()).thenReturn("controlled-genesis-key");
		RandomXManager manager = new RandomXManager(
				properties(true, RandomXMiningMemoryMode.LIGHT), chainQuery, sandboxContext(), factory,
				() -> settings);

		manager.ensureInitializedForHeight(10L);

		ArgumentCaptor<byte[]> seed = ArgumentCaptor.forClass(byte[].class);
		verify(factory.caches.getFirst()).init(seed.capture());
		assertThat(seed.getValue()).containsExactly(genesisBlockHash.toArray());
		verify(chainQuery).getBlockHashByHeight(0L);
		manager.close();
	}

	private RandomXManager manager(MiningProperties properties, SandboxRuntimeContext runtimeContext,
			RandomXResourceFactory factory) {
		ChainQuery chainQuery = mock(ChainQuery.class);
		return new RandomXManager(properties, chainQuery, runtimeContext, factory,
				height -> new byte[] { (byte) height, 42 });
	}

	private MiningProperties properties(boolean enabled, RandomXMiningMemoryMode mode) {
		MiningProperties properties = new MiningProperties();
		properties.setEnable(enabled);
		properties.setHashingThreads(-1);
		properties.setMemoryMode(mode);
		return properties;
	}

	private void configureAcceleration(
			RandomXManager manager,
			RandomXVerificationProperties properties,
			RandomXSyncMemoryPolicy.MemorySnapshot memory) {
		manager.configureSyncVerificationAcceleration(
				properties, false, new SimpleMeterRegistry(), () -> memory);
	}

	private RandomXVerificationProperties highMemoryProperties() {
		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		properties.setRebuildCooldown(Duration.ofMinutes(10));
		return properties;
	}

	private void assertConcurrentShutdownLeaseOrder(boolean miningFirst) throws Exception {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		configureAcceleration(manager, highMemoryProperties(), highMemory());
		manager.ensureInitializedForHeight(1L);
		RandomXDataset dataset = factory.datasets.getFirst();
		RandomXVmLease mining = manager.createMiningVM();
		manager.syncBulkCatchUpStarted(1L, 10_000L);
		ProofOfWorkVerificationContext context = manager.verificationContext(1L, ignored -> Optional.empty());
		RandomXVerificationVmLease verification = manager.acquireVerificationVM(context);

		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			executor.submit(manager::close).get(1, TimeUnit.SECONDS);
		}
		verify(dataset, never()).close();
		if (miningFirst) {
			mining.close();
			verify(dataset, never()).close();
			verification.close();
		} else {
			verification.close();
			verify(dataset, never()).close();
			mining.close();
		}
		verify(dataset, times(1)).close();
		manager.close();
		verify(dataset, times(1)).close();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(1, TimeUnit.SECONDS)) {
				throw new IllegalStateException("test start latch timed out");
			}
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("test start latch interrupted", failure);
		}
	}

	private RandomXSyncMemoryPolicy.MemorySnapshot highMemory() {
		return new RandomXSyncMemoryPolicy.MemorySnapshot(32768, 24000, 4096, true);
	}

	private SandboxRuntimeContext productionContext() {
		return new SandboxRuntimeContext(ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty());
	}

	private SandboxRuntimeContext sandboxContext() {
		SandboxRuntimeContext runtimeContext = mock(SandboxRuntimeContext.class);
		when(runtimeContext.isSandbox()).thenReturn(true);
		return runtimeContext;
	}

	private static final class RecordingFactory implements RandomXResourceFactory {
		private final List<Set<RandomXFlag>> cacheFlags = new ArrayList<>();
		private final List<RandomXCache> caches = new ArrayList<>();
		private final List<RandomXDataset> datasets = new ArrayList<>();
		private final List<RandomXVM> vms = new ArrayList<>();
		private final List<VmRequest> vmRequests = new ArrayList<>();
		private boolean parityMismatch;
		private boolean failDatasetInitialization;
		private boolean blockDatasetInitialization;
		private boolean blockVerificationVmCreation;
		private final CountDownLatch datasetInitStarted = new CountDownLatch(1);
		private final CountDownLatch releaseDatasetInit = new CountDownLatch(1);
		private final CountDownLatch verificationVmCreationStarted = new CountDownLatch(1);
		private final CountDownLatch releaseVerificationVmCreation = new CountDownLatch(1);

		@Override
		public RandomXCache createCache(Set<RandomXFlag> flags) {
			RandomXCache cache = mock(RandomXCache.class);
			cacheFlags.add(Set.copyOf(flags));
			caches.add(cache);
			return cache;
		}

		@Override
		public RandomXDataset createDataset(Set<RandomXFlag> flags) {
			RandomXDataset dataset = mock(RandomXDataset.class);
			if (failDatasetInitialization) {
				doThrow(new IllegalStateException("dataset init failed")).when(dataset).init(any());
			} else if (blockDatasetInitialization) {
				doAnswer(ignored -> {
					datasetInitStarted.countDown();
					if (!releaseDatasetInit.await(1, TimeUnit.SECONDS)) {
						throw new IllegalStateException("dataset init release timed out");
					}
					return null;
				}).when(dataset).init(any());
			}
			datasets.add(dataset);
			return dataset;
		}

		@Override
		public RandomXVM createVM(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
			if (blockVerificationVmCreation) {
				verificationVmCreationStarted.countDown();
				try {
					if (!releaseVerificationVmCreation.await(1, TimeUnit.SECONDS)) {
						throw new IllegalStateException("verification VM creation release timed out");
					}
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("verification VM creation interrupted", failure);
				}
			}
			RandomXVM vm = mock(RandomXVM.class);
			byte[] hash = new byte[32];
			if (parityMismatch && dataset != null) {
				hash[0] = 1;
			}
			when(vm.calculateHash(any())).thenReturn(hash);
			vms.add(vm);
			vmRequests.add(new VmRequest(Set.copyOf(flags), cache, dataset));
			return vm;
		}
	}

	private record VmRequest(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
	}
}
