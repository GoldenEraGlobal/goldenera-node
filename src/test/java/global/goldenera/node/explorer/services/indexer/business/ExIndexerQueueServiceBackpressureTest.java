/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
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
