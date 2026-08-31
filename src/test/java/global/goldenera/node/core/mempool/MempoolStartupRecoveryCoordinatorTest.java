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
package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolStartupRecoveryService.RecoveryResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStartupRecoveryCoordinatorTest {

	@Test
	void recoveryIsAtomicallyVisibleAndOpensMaintenanceGateOnlyAfterCompletion() throws Exception {
		MempoolRecoveryGate gate = new MempoolRecoveryGate();
		MempoolStore store = store();
		MempoolStartupRecoveryService recovery = mock(MempoolStartupRecoveryService.class);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			entered.countDown();
			release.await();
			return new RecoveryResult(0, 0, 0);
		}).when(recovery).recover();
		MempoolStartupRecoveryCoordinator coordinator = new MempoolStartupRecoveryCoordinator(
				gate, store, recovery, mock(MempoolCanonicalJournalProjector.class));

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<RecoveryResult> recoveryFuture = executor.submit(coordinator::recover);
			assertThat(entered.await(2L, TimeUnit.SECONDS)).isTrue();
			Future<Long> concurrentRead = executor.submit(store::getCount);
			Thread.sleep(50L);
			assertThat(concurrentRead).isNotDone();
			assertThat(gate.isRecovered()).isFalse();

			release.countDown();
			assertThat(recoveryFuture.get()).isEqualTo(new RecoveryResult(0, 0, 0));
			assertThat(concurrentRead.get()).isZero();
			assertThat(gate.isRecovered()).isTrue();
		}
	}

	@Test
	void failedRecoveryKeepsMaintenanceGateClosed() {
		MempoolRecoveryGate gate = new MempoolRecoveryGate();
		MempoolStartupRecoveryService recovery = mock(MempoolStartupRecoveryService.class);
		doThrow(new IllegalStateException("corrupt record")).when(recovery).recover();
		MempoolStartupRecoveryCoordinator coordinator = new MempoolStartupRecoveryCoordinator(
				gate, store(), recovery, mock(MempoolCanonicalJournalProjector.class));

		assertThatThrownBy(coordinator::recover)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("corrupt");
		assertThat(gate.isRecovered()).isFalse();
	}

	@Test
	void scheduledRevalidationCannotRunBeforeRecoveryGateOpens() {
		MempoolRecoveryGate gate = new MempoolRecoveryGate();
		MempoolStore store = mock(MempoolStore.class);
		when(store.getCount()).thenReturn(0L);
		MempoolManager manager = new MempoolManager(
				new SimpleMeterRegistry(),
				store,
				mock(MempoolValidator.class),
				properties(100),
				mock(ChainHeadStateCache.class),
				Runnable::run,
				mock(ThreadPoolTaskScheduler.class),
				gate);

		manager.revalidateMempool();
		verify(store, never()).getCount();

		gate.completeRecovery();
		manager.revalidateMempool();
		verify(store).getCount();
	}

	private MempoolStore store() {
		return new MempoolStore(
				new SimpleMeterRegistry(),
				properties(100),
				mock(ChainHeadStateCache.class),
				mock(ApplicationEventPublisher.class));
	}
}
