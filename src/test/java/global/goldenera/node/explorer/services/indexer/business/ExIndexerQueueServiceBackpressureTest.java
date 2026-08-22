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
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerQueueServiceBackpressureTest {

	@Test
	@Timeout(5)
	void neverExceedsCapacityAndResumesProducerWhenConsumerMakesRoom() throws Exception {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		when(readiness.isReady()).thenReturn(true);
		ExIndexerQueueService queue = new ExIndexerQueueService(
				properties, readiness, new SimpleMeterRegistry());
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		queue.pushConnectBatch(Collections.nCopies(10_000, event));

		CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> queue.pushConnect(event));
		Thread.sleep(50);
		assertThat(producer.isDone()).isFalse();
		assertThat(queue.size()).isEqualTo(10_000);

		queue.take();

		producer.get(1, TimeUnit.SECONDS);
		assertThat(queue.size()).isEqualTo(10_000);
	}
}
