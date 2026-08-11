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
package global.goldenera.node.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CoreAsyncConfigTest {

	@Test
	void orderedExecutorReportsBackpressureAndRunsRejectedTaskOnCaller() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ThreadPoolTaskExecutor executor = new CoreAsyncConfig().mempoolEventExecutor(registry);
		try {
			ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();
			AtomicBoolean ran = new AtomicBoolean();

			threadPool.getRejectedExecutionHandler().rejectedExecution(() -> ran.set(true), threadPool);

			assertThat(ran).isTrue();
			assertThat(registry.counter("blockchain.mempool.event_queue.backpressure_total").count())
					.isEqualTo(1);
			assertThat(threadPool.getQueue().remainingCapacity()).isEqualTo(10000);
		} finally {
			executor.shutdown();
		}
	}

	@Test
	void saturatedOrderedExecutorPreservesQueuedTasksAndRunsOverflowOnPublisherThread() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ThreadPoolTaskExecutor executor = new CoreAsyncConfig().mempoolEventExecutor(registry);
		CountDownLatch workerStarted = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		AtomicInteger completed = new AtomicInteger();
		AtomicReference<Thread> overflowThread = new AtomicReference<>();
		try {
			executor.execute(() -> {
				workerStarted.countDown();
				try {
					releaseWorker.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				completed.incrementAndGet();
			});
			assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
			for (int task = 0; task < 10_000; task++) {
				executor.execute(completed::incrementAndGet);
			}

			Thread publisher = Thread.currentThread();
			executor.execute(() -> {
				overflowThread.set(Thread.currentThread());
				completed.incrementAndGet();
			});

			assertThat(overflowThread.get()).isSameAs(publisher);
			assertThat(registry.counter("blockchain.mempool.event_queue.backpressure_total").count())
					.isEqualTo(1);
			assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(10_000);
		} finally {
			releaseWorker.countDown();
			executor.shutdown();
		}
		assertThat(completed).hasValue(10_002);
	}

	@Test
	void gracefulShutdownDrainsOrderedEventQueue() {
		ThreadPoolTaskExecutor executor = new CoreAsyncConfig()
				.mempoolEventExecutor(new SimpleMeterRegistry());
		AtomicInteger completed = new AtomicInteger();
		for (int task = 0; task < 500; task++) {
			executor.execute(completed::incrementAndGet);
		}

		executor.shutdown();

		assertThat(completed).hasValue(500);
		assertThat(executor.getThreadPoolExecutor().isTerminated()).isTrue();
	}
}
