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
package global.goldenera.node.explorer.services.indexer.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerMempoolCoreService;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveRebuildTrigger;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerLifecycleRecoveryTest {

	@Test
	void coordinatorStartsWhenRebuildBecomesReadyAndCanReplaceStoppedWorker() throws Exception {
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExIndexerQueueService queue = mock(ExIndexerQueueService.class);
		AtomicInteger takes = new AtomicInteger();
		CountDownLatch secondWorkerBlocker = new CountDownLatch(1);
		when(queue.take()).thenAnswer(invocation -> {
			if (takes.incrementAndGet() == 1) {
				throw new InterruptedException("simulated stopped worker");
			}
			secondWorkerBlocker.await();
			throw new InterruptedException("shutdown");
		});
		ExIndexerCoordinateService service = new ExIndexerCoordinateService(
				enabledProperties(), readiness, new SimpleMeterRegistry(), queue,
				mock(ExIndexerService.class), new ExplorerIndexingExecutionGate(),
				mock(ExplorerArchiveRebuildTrigger.class));

		service.start();
		assertThat(takes.get()).isZero();

		readiness.ready();
		await().atMost(Duration.ofSeconds(2)).until(() -> takes.get() >= 2);

		service.stop();
		secondWorkerBlocker.countDown();
	}

	@Test
	void mempoolFlusherIsScheduledWhileExplorerIsStillRebuilding() {
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		readiness.rebuilding("startup rebuild");
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		ExIndexerMempoolCoreService coreService = mock(ExIndexerMempoolCoreService.class);
		ExIndexerMempoolService service = new ExIndexerMempoolService(
				enabledProperties(), readiness, new SimpleMeterRegistry(),
				coreService, scheduler);

		service.init();
		service.init();

		verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
		readiness.ready();
		verify(coreService).truncate();
		service.destroy();
	}

	@Test
	void syncBatchUsesOneQueueSlotAndIsCommittedInBoundedChunksWithFullLagWeight() {
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		readiness.ready();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ExIndexerQueueService queue = new ExIndexerQueueService(enabledProperties(), readiness, registry);
		queue.initMetrics();
		ExIndexerService indexer = mock(ExIndexerService.class);
		ExIndexerCoordinateService service = new ExIndexerCoordinateService(
				enabledProperties(), readiness, registry, queue, indexer,
				new ExplorerIndexingExecutionGate(), mock(ExplorerArchiveRebuildTrigger.class));
		List<BlockConnectedEvent> events = IntStream.range(0, 130)
				.mapToObj(ignored -> mock(BlockConnectedEvent.class))
				.toList();

		assertThat(queue.pushConnectBatch(events)).isEqualTo(ExIndexerQueueService.BatchAdmission.ENQUEUED);
		assertThat(queue.size()).isEqualTo(1);
		service.start();
		try {
			await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
					assertThat(registry.get("explorer.catchup.lag.blocks").gauge().value()).isZero());

			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<BlockConnectedEvent>> batches = ArgumentCaptor.forClass(List.class);
			verify(indexer, times(3)).handleBlockConnectedBatch(batches.capture());
			assertThat(batches.getAllValues()).extracting(List::size).containsExactly(64, 64, 2);
		} finally {
			service.stop();
		}
	}

	private GeneralProperties enabledProperties() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		return properties;
	}
}
