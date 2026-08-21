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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.randomx.RandomXCache;
import global.goldenera.randomx.RandomXDataset;
import global.goldenera.randomx.RandomXFlag;
import global.goldenera.randomx.RandomXVM;

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
	void fullMiningStillAllocatesDatasetAndVerificationRemainsCacheOnly() {
		RecordingFactory factory = new RecordingFactory();
		RandomXManager manager = manager(properties(true, RandomXMiningMemoryMode.FULL), productionContext(), factory);
		manager.ensureInitializedForHeight(1L);

		RandomXVmLease miningLease = manager.createMiningVM();
		RandomXVmLease verificationLease = manager.getLightVMForVerification(1L, ignored -> Optional.empty());

		assertThat(factory.datasets).hasSize(1);
		assertThat(factory.vmRequests).hasSize(2);
		assertThat(factory.vmRequests.get(0).flags()).contains(RandomXFlag.FULL_MEM);
		assertThat(factory.vmRequests.get(0).dataset()).isSameAs(factory.datasets.getFirst());
		assertThat(factory.vmRequests.get(1).flags()).doesNotContain(RandomXFlag.FULL_MEM, RandomXFlag.LARGE_PAGES);
		assertThat(factory.vmRequests.get(1).dataset()).isNull();

		miningLease.close();
		verificationLease.close();
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
			datasets.add(dataset);
			return dataset;
		}

		@Override
		public RandomXVM createVM(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
			RandomXVM vm = mock(RandomXVM.class);
			when(vm.calculateHash(any())).thenReturn(new byte[32]);
			vms.add(vm);
			vmRequests.add(new VmRequest(Set.copyOf(flags), cache, dataset));
			return vm;
		}
	}

	private record VmRequest(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
	}
}
