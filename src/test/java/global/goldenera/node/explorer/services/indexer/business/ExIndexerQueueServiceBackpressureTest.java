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
import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerQueueServiceBackpressureTest {

	@Test
	@Timeout(2)
	void fullSyncQueueSwitchesToRebuildWithoutBlockingOrPartialAdmission() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		readiness.ready();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ExIndexerQueueService queue = new ExIndexerQueueService(properties, readiness, registry);
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		for (int index = 0; index < ExIndexerQueueService.MAX_QUEUE_CAPACITY; index++) {
			assertThat(queue.pushConnectBatch(List.of(event)))
					.isEqualTo(ExIndexerQueueService.BatchAdmission.ENQUEUED);
		}

		long started = System.nanoTime();
		ExIndexerQueueService.BatchAdmission admission = queue.pushConnectBatch(List.of(event));

		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(100L);
		assertThat(admission).isEqualTo(ExIndexerQueueService.BatchAdmission.REBUILD_REQUIRED);
		assertThat(queue.size()).isZero();
		assertThat(readiness.isReady()).isFalse();
		assertThat(registry.find("explorer.queue.overflow_to_rebuild")
				.tag("source", "sync batch").counter().count()).isEqualTo(1.0);
	}

	@Test
	@Timeout(5)
	void fullLiveQueueSwitchesToRebuildWithoutBlockingConsensusPublisher() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		readiness.ready();
		ExIndexerQueueService queue = new ExIndexerQueueService(
				properties, readiness, new SimpleMeterRegistry());
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		for (int index = 0; index < ExIndexerQueueService.MAX_QUEUE_CAPACITY; index++) {
			queue.pushConnectBatch(List.of(event));
		}

		long started = System.nanoTime();
		ExIndexerQueueService.BatchAdmission admission = queue.pushConnect(event);

		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(100L);
		assertThat(admission).isEqualTo(ExIndexerQueueService.BatchAdmission.REBUILD_REQUIRED);
		assertThat(queue.size()).isZero();
		assertThat(readiness.isReady()).isFalse();
	}

	@Test
	void normalSyncBatchPreservesLiveEventOrder() throws Exception {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		readiness.ready();
		ExIndexerQueueService queue = new ExIndexerQueueService(
				properties, readiness, new SimpleMeterRegistry());
		BlockConnectedEvent first = mock(BlockConnectedEvent.class);
		BlockConnectedEvent second = mock(BlockConnectedEvent.class);
		BlockConnectedEvent third = mock(BlockConnectedEvent.class);

		assertThat(queue.pushConnectBatch(List.of(first, second, third)))
				.isEqualTo(ExIndexerQueueService.BatchAdmission.ENQUEUED);

		ExIndexerTask task = queue.take();
		assertThat(task).isInstanceOf(ExIndexerTask.ConnectBatchTask.class);
		assertThat(((ExIndexerTask.ConnectBatchTask) task).getEvents())
				.containsExactly(first, second, third);
	}
}
