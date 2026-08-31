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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.crypto.RandomXVmLease;
import global.goldenera.node.core.blockchain.crypto.RandomXVerificationVmLease;
import global.goldenera.node.core.properties.RandomXVerificationProperties;
import global.goldenera.node.core.sync.SyncVerificationAccelerationPolicy.EndReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RandomXProofOfWorkProviderTest {

	@Test
	void miningUsesExistingDatasetBackedRandomXManagerLifecycle() {
		RandomXManager manager = mock(RandomXManager.class);
		RandomXVmLease lease = mock(RandomXVmLease.class);
		byte[] input = { 1, 2, 3 };
		byte[] output = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
		when(manager.createMiningVM()).thenReturn(lease);
		when(lease.calculateHash(same(input))).thenReturn(output);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager);

		provider.prepareForMining(123L);
		try (ProofOfWorkHasher hasher = provider.openMiningHasher()) {
			assertThat(hasher.hash(input)).isSameAs(output);
		}

		verify(manager).prepareMiningResourcesForHeight(123L);
		verify(manager).createMiningVM();
		verify(lease).calculateHash(same(input));
		verify(lease).close();
	}

	@Test
	void verificationSessionReusesOneVmAndPublishesBoundedModeMetrics() {
		RandomXManager manager = mock(RandomXManager.class);
		RandomXVmLease lease = mock(RandomXVmLease.class);
		ProofOfWorkVerificationContext context = new ProofOfWorkVerificationContext(Bytes.of(1, 2));
		Function<Long, Optional<byte[]>> seedResolver = height -> Optional.of(new byte[] { height.byteValue() });
		when(manager.verificationContext(456L, seedResolver)).thenReturn(context);
		when(manager.acquireVerificationVM(context)).thenReturn(
				new RandomXVerificationVmLease(lease, ProofOfWorkVerificationMode.RANDOMX_LIGHT));
		when(lease.calculateHash(any())).thenReturn(new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES]);
		when(manager.isInitializationInProgress()).thenReturn(true);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(
				manager, new RandomXVerificationProperties(), registry, 4);

		ProofOfWorkVerificationContext resolved = provider.verificationContext(456L, seedResolver);
		try (ProofOfWorkVerificationSession session = provider.openVerificationSession(resolved)) {
			session.hash(new byte[] { 1 });
			session.hash(new byte[] { 2 });
			assertThat(provider.isInitializationInProgress()).isTrue();
		}

		verify(manager).verificationContext(456L, seedResolver);
		verify(manager).acquireVerificationVM(context);
		verify(lease, times(2)).calculateHash(any());
		verify(lease).close();
		assertThat(registry.get("blockchain.randomx.verification.vm.created")
				.tag("mode", "randomx_light").counter().count()).isEqualTo(1);
		assertThat(registry.get("blockchain.randomx.verification.vm.reused")
				.tag("mode", "randomx_light").counter().count()).isEqualTo(1);
		assertThat(registry.get("blockchain.randomx.verification.vm.closed")
				.tag("mode", "randomx_light").counter().count()).isEqualTo(1);
		assertThat(registry.get("blockchain.randomx.verification.hash.duration")
				.tag("mode", "randomx_light").timer().count()).isEqualTo(2);
	}

	@Test
	void verifierPermitActuallyBlocksASecondSessionUntilTheFirstCloses() throws Exception {
		RandomXManager manager = mock(RandomXManager.class);
		ProofOfWorkVerificationContext context = new ProofOfWorkVerificationContext(Bytes.of(7));
		when(manager.acquireVerificationVM(context)).thenAnswer(ignored -> new RandomXVerificationVmLease(
				mock(RandomXVmLease.class), ProofOfWorkVerificationMode.RANDOMX_LIGHT));
		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		properties.setParallelism(1);
		properties.setMaxParallelism(1);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(
				manager, properties, new SimpleMeterRegistry(), 8);

		try (ProofOfWorkVerificationSession first = provider.openVerificationSession(context);
				ExecutorService executor = Executors.newSingleThreadExecutor()) {
			CountDownLatch attempted = new CountDownLatch(1);
			Future<ProofOfWorkVerificationSession> second = executor.submit(() -> {
				attempted.countDown();
				return provider.openVerificationSession(context);
			});
			assertThat(attempted.await(1, TimeUnit.SECONDS)).isTrue();
			Thread.sleep(50);
			assertThat(second.isDone()).isFalse();

			first.close();
			second.get(1, TimeUnit.SECONDS).close();
		}
		assertThat(provider.verificationConcurrencyLimit(16)).isEqualTo(1);
	}

	@Test
	void miningInitializationFailureIsReportedAsFatalProofOfWorkFailure() {
		RandomXManager manager = mock(RandomXManager.class);
		doThrow(new IllegalStateException("native initialization failed"))
				.when(manager).prepareMiningResourcesForHeight(789L);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager);

		assertThatThrownBy(() -> provider.prepareForMining(789L))
				.isInstanceOf(ProofOfWorkMiningException.class)
				.hasMessageContaining("height 789")
				.hasRootCauseMessage("native initialization failed");
		verify(manager).prepareMiningResourcesForHeight(789L);
	}

	@Test
	void syncLifecycleSignalsDelegateWithoutOwningMiningResources() {
		RandomXManager manager = mock(RandomXManager.class);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager);

		provider.bulkCatchUpStarted(10L, 10_000L);
		provider.progress(1_000L, 10_000L);
		provider.syncEnded(EndReason.FAILED);
		provider.syncEnded(EndReason.CAUGHT_UP);
		provider.syncEnded(EndReason.STOPPED);

		verify(manager).syncBulkCatchUpStarted(10L, 10_000L);
		verify(manager).syncProgress(1_000L, 10_000L);
		verify(manager).syncFailed();
		verify(manager).syncCaughtUp();
		verify(manager).syncStopped();
	}
}
