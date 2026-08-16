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
package global.goldenera.node.shared.services.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent.RemoveReason;
import global.goldenera.node.core.config.CoreAsyncConfig;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.explorer.events.ExBlockConnectedEvent;
import global.goldenera.node.explorer.services.webhook.ExplorerWebhookListenerService;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WebhookListenerServiceTest {

	@Test
	void springSelectsTheObjectFactoryConstructor() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getBeanFactory().registerSingleton("webhookDispatchService", mock(WebhookDispatchService.class));
			context.getBeanFactory().registerSingleton(CoreAsyncConfig.WEBHOOK_EVENT_EXECUTOR,
					(Executor) Runnable::run);
			context.register(WebhookListenerService.class);
			context.refresh();

			assertThat(context.getBean(WebhookListenerService.class)).isNotNull();
		}
	}

	@Test
	@Timeout(5)
	void blockPublisherReturnsWhileWebhookMappingRunsOnDedicatedQueue() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ThreadPoolTaskExecutor executor = new CoreAsyncConfig().webhookEventExecutor(registry);
		WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
		CountDownLatch mappingStarted = new CountDownLatch(1);
		CountDownLatch allowCompletion = new CountDownLatch(1);
		CountDownLatch mappingCompleted = new CountDownLatch(1);
		doAnswer(invocation -> {
			try {
				mappingStarted.countDown();
				assertThat(allowCompletion.await(3, TimeUnit.SECONDS)).isTrue();
			} finally {
				mappingCompleted.countDown();
			}
			return null;
		}).when(dispatch).processNewBlockEvent(
				any(Block.class), anyList(), eq(WebhookType.BLOCKCHAIN));

		Block block = mock(Block.class);
		when(block.getHash()).thenReturn(Hash.ZERO);
		when(block.getTxs()).thenReturn(List.of());
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		when(event.getBlock()).thenReturn(block);
		when(event.getEvents()).thenReturn(List.of());
		WebhookListenerService listener = new WebhookListenerService(dispatch, executor);

		try {
			listener.handleBlockConnected(event);

			assertThat(mappingStarted.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(mappingCompleted.getCount()).isEqualTo(1);
			assertThat(registry.find("blockchain.webhook.event_queue.size").gauge()).isNotNull();
			allowCompletion.countDown();
			assertThat(mappingCompleted.await(1, TimeUnit.SECONDS)).isTrue();
		} finally {
			allowCompletion.countDown();
			executor.shutdown();
		}
	}

	@ParameterizedTest
	@EnumSource(value = RemoveReason.class, names = { "EVICTED_FULL", "INSUFFICIENT_FUNDS" })
	void capacityAndBalanceEvictionsProduceDroppedWebhookStatus(RemoveReason reason) {
		WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
		WebhookListenerService listener = new WebhookListenerService(dispatch, Runnable::run);
		Tx tx = mock(Tx.class);
		MempoolEntry entry = mock(MempoolEntry.class);
		when(entry.getTx()).thenReturn(tx);
		when(entry.getHash()).thenReturn(Hash.ZERO);
		MempoolTxRemoveEvent event = mock(MempoolTxRemoveEvent.class);
		when(event.getEntry()).thenReturn(entry);
		when(event.getReason()).thenReturn(reason);

		listener.handleMempoolTxRemove(event);

		verify(dispatch).processAddressActivityEvent(
				null, tx, WebhookTxStatus.DROPPED, null, WebhookType.BLOCKCHAIN);
	}

	@Test
	void notReadyExplorerDoesNotResolveExecutorOrEnqueueWebhookWork() {
		WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		ExecutorResolutionProbe executor = new ExecutorResolutionProbe();
		ExplorerWebhookListenerService listener = new ExplorerWebhookListenerService(
				dispatch, readiness, executor::resolve);
		listener.handleExBlockConnected(mock(ExBlockConnectedEvent.class));

		assertThat(executor.resolved).isFalse();
		verify(dispatch, never()).processNewBlockEvent(any(), anyList(), any());
	}

	@Test
	void queuedWebhookWorkRechecksReadinessBeforeProcessing() {
		WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		when(readiness.isReady()).thenReturn(true, false);
		AtomicReference<Runnable> queued = new AtomicReference<>();
		ExplorerWebhookListenerService listener = new ExplorerWebhookListenerService(
				dispatch, readiness, () -> queued::set);

		listener.handleExBlockConnected(mock(ExBlockConnectedEvent.class));
		assertThat(queued.get()).isNotNull();

		queued.get().run();

		verify(dispatch, never()).processNewBlockEvent(any(), anyList(), any());
	}

	@Test
	void readyExplorerEmitsConfirmedAddressActivityForEveryIndexedTransaction() {
		WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		when(readiness.isReady()).thenReturn(true);
		Tx first = mock(Tx.class);
		Tx second = mock(Tx.class);
		Block block = mock(Block.class);
		when(block.getHash()).thenReturn(Hash.ZERO);
		when(block.getTxs()).thenReturn(List.of(first, second));
		ExBlockConnectedEvent event = mock(ExBlockConnectedEvent.class);
		when(event.getBlock()).thenReturn(block);
		when(event.getEvents()).thenReturn(List.of());
		ExplorerWebhookListenerService listener = new ExplorerWebhookListenerService(
				dispatch, readiness, () -> Runnable::run);

		listener.handleExBlockConnected(event);

		verify(dispatch).processNewBlockEvent(block, List.of(), WebhookType.EXPLORER);
		verify(dispatch).processAddressActivityEvent(
				block, first, WebhookTxStatus.CONFIRMED, 0, WebhookType.EXPLORER);
		verify(dispatch).processAddressActivityEvent(
				block, second, WebhookTxStatus.CONFIRMED, 1, WebhookType.EXPLORER);
	}

	private static final class ExecutorResolutionProbe {

		private boolean resolved;

		private Executor resolve() {
			resolved = true;
			return Runnable::run;
		}
	}
}
