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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainBlockHeaderMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.entities.WebhookEvent;
import global.goldenera.node.shared.enums.WebhookEventType;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.events.WebhookUpdateEvent;
import global.goldenera.node.shared.services.core.WebhookCoreService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Callback;

class WebhookDispatchServiceTest {

	@Test
	void slowWebhookHasAtMostOneRequestInFlight() {
		UUID webhookId = UUID.randomUUID();
		Webhook webhook = webhook(webhookId, WebhookType.BLOCKCHAIN, WebhookEventType.NEW_BLOCK);
		WebhookCoreService coreService = mock(WebhookCoreService.class);
		when(coreService.getAllEnabledWebhooksWithEvents()).thenReturn(List.of(webhook));
		AESGCMComponent encryption = mock(AESGCMComponent.class);
		when(encryption.decrypt(any(Bytes.class))).thenAnswer(invocation -> invocation.getArgument(0));
		OkHttpClient client = mock(OkHttpClient.class);
		Call call = mock(Call.class);
		when(client.newCall(any())).thenReturn(call);
		AtomicReference<Callback> callback = new AtomicReference<>();
		doAnswer(invocation -> {
			callback.set(invocation.getArgument(0));
			return null;
		}).when(call).enqueue(any());
		WebhookDispatchService dispatch = new WebhookDispatchService(
				client, new ObjectMapper(), mock(TaskScheduler.class), coreService,
				new SimpleMeterRegistry(), encryption, mock(BlockchainTxMapper.class),
				mock(BlockchainBlockHeaderMapper.class));
		dispatch.init();
		dispatch.loadIndexOnStartup();
		dispatch.processNewBlockEvent(mock(Block.class), List.of(), WebhookType.BLOCKCHAIN);
		dispatch.processNewBlockEvent(mock(Block.class), List.of(), WebhookType.BLOCKCHAIN);

		dispatch.dispatchPendingBatches();
		dispatch.dispatchPendingBatches();
		verify(client).newCall(any());

		callback.get().onFailure(call, new IOException("slow endpoint"));
		dispatch.processNewBlockEvent(mock(Block.class), List.of(), WebhookType.BLOCKCHAIN);
		dispatch.dispatchPendingBatches();
		verify(client, times(2)).newCall(any());
	}

	@Test
	void blockAndReorgSubscriptionsAreIndexedAndDispatchedIndependently() {
		UUID blockWebhookId = UUID.randomUUID();
		UUID reorgWebhookId = UUID.randomUUID();
		UUID explorerWebhookId = UUID.randomUUID();
		Webhook blockWebhook = webhook(blockWebhookId, WebhookType.BLOCKCHAIN, WebhookEventType.NEW_BLOCK);
		Webhook reorgWebhook = webhook(reorgWebhookId, WebhookType.BLOCKCHAIN, WebhookEventType.REORG);
		Webhook explorerWebhook = webhook(explorerWebhookId, WebhookType.EXPLORER, WebhookEventType.NEW_BLOCK);
		WebhookCoreService coreService = mock(WebhookCoreService.class);
		when(coreService.getAllEnabledWebhooksWithEvents())
				.thenReturn(List.of(blockWebhook, reorgWebhook, explorerWebhook));
		AESGCMComponent encryption = mock(AESGCMComponent.class);
		when(encryption.decrypt(any(Bytes.class))).thenAnswer(invocation -> invocation.getArgument(0));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		WebhookDispatchService dispatch = new WebhookDispatchService(
				mock(OkHttpClient.class), new ObjectMapper(), mock(TaskScheduler.class), coreService,
				registry, encryption, mock(BlockchainTxMapper.class), mock(BlockchainBlockHeaderMapper.class));
		dispatch.init();
		dispatch.loadIndexOnStartup();

		dispatch.processNewBlockEvent(mock(Block.class), List.of(), WebhookType.BLOCKCHAIN);

		assertThat(queueSize(registry)).isEqualTo(1.0);

		dispatch.processReorgEvent(1L, Hash.ZERO, 2L, Hash.ZERO, WebhookType.BLOCKCHAIN);

		assertThat(queueSize(registry)).isEqualTo(2.0);

		dispatch.processNewBlockEvent(mock(Block.class), List.of(), WebhookType.EXPLORER);

		assertThat(queueSize(registry)).isEqualTo(3.0);
		verify(encryption, times(3)).decrypt(blockWebhook.getCreatedByApiKey().getWebhookSecretKey());
	}

	@Test
	void webhookUpdateReloadsApiKeyOwnedSecret() {
		UUID webhookId = UUID.randomUUID();
		Webhook webhook = webhook(webhookId, WebhookType.EXPLORER, WebhookEventType.NEW_BLOCK);
		when(webhook.isEnabled()).thenReturn(true);
		when(webhook.getCreatedByApiKey().isEnabled()).thenReturn(true);
		WebhookCoreService coreService = mock(WebhookCoreService.class);
		when(coreService.findWebhookByIdWithEvents(webhookId)).thenReturn(Optional.of(webhook));
		AESGCMComponent encryption = mock(AESGCMComponent.class);
		when(encryption.decrypt(any(Bytes.class))).thenAnswer(invocation -> invocation.getArgument(0));
		WebhookDispatchService dispatch = new WebhookDispatchService(
				mock(OkHttpClient.class), new ObjectMapper(), mock(TaskScheduler.class), coreService,
				new SimpleMeterRegistry(), encryption, mock(BlockchainTxMapper.class),
				mock(BlockchainBlockHeaderMapper.class));

		dispatch.handleWebhookUpdate(new WebhookUpdateEvent(this,
				WebhookUpdateEvent.UpdateType.CREATE_WEBHOOK, webhookId, Optional.of(webhook)));

		verify(encryption).decrypt(webhook.getCreatedByApiKey().getWebhookSecretKey());
	}

	@Test
	void internalBridgeDestinationIsNotLoadedIntoSharedDispatcher() {
		UUID webhookId = UUID.randomUUID();
		Webhook webhook = webhook(webhookId, WebhookType.BRIDGE, WebhookEventType.NEW_BLOCK);
		when(webhook.isEnabled()).thenReturn(true);
		when(webhook.getCreatedByApiKey().isEnabled()).thenReturn(true);
		WebhookCoreService coreService = mock(WebhookCoreService.class);
		when(coreService.findWebhookByIdWithEvents(webhookId)).thenReturn(Optional.of(webhook));
		AESGCMComponent encryption = mock(AESGCMComponent.class);
		WebhookDispatchService dispatch = new WebhookDispatchService(
				mock(OkHttpClient.class), new ObjectMapper(), mock(TaskScheduler.class), coreService,
				new SimpleMeterRegistry(), encryption, mock(BlockchainTxMapper.class),
				mock(BlockchainBlockHeaderMapper.class));

		dispatch.handleWebhookUpdate(new WebhookUpdateEvent(this,
				WebhookUpdateEvent.UpdateType.CREATE_WEBHOOK, webhookId, Optional.of(webhook)));

		verifyNoInteractions(encryption);
	}

	@Test
	void legacyWebhookSecretTakesPrecedenceOverApiKeySecret() {
		UUID webhookId = UUID.randomUUID();
		Webhook webhook = webhook(webhookId, WebhookType.EXPLORER, WebhookEventType.NEW_BLOCK);
		Bytes legacySecret = Bytes.wrap(new byte[] { 1 });
		when(webhook.getSecretKey()).thenReturn(legacySecret);
		WebhookCoreService coreService = mock(WebhookCoreService.class);
		when(coreService.getAllEnabledWebhooksWithEvents()).thenReturn(List.of(webhook));
		AESGCMComponent encryption = mock(AESGCMComponent.class);
		when(encryption.decrypt(legacySecret)).thenReturn(Bytes.wrap(new byte[] { 3 }));
		WebhookDispatchService dispatch = new WebhookDispatchService(
				mock(OkHttpClient.class), new ObjectMapper(), mock(TaskScheduler.class), coreService,
				new SimpleMeterRegistry(), encryption, mock(BlockchainTxMapper.class),
				mock(BlockchainBlockHeaderMapper.class));

		dispatch.loadIndexOnStartup();

		verify(encryption).decrypt(legacySecret);
		verifyNoMoreInteractions(encryption);
	}

	private static Webhook webhook(UUID id, WebhookType webhookType, WebhookEventType eventType) {
		ApiKey apiKey = mock(ApiKey.class);
		when(apiKey.getId()).thenReturn((long) id.hashCode());
		when(apiKey.getWebhookSecretKey()).thenReturn(Bytes.wrap(new byte[32]));
		Webhook webhook = mock(Webhook.class);
		when(webhook.getId()).thenReturn(id);
		when(webhook.getCreatedByApiKey()).thenReturn(apiKey);
		when(webhook.getDtoVersion()).thenReturn(1);
		when(webhook.getType()).thenReturn(webhookType);
		when(webhook.getUrl()).thenReturn("https://example.invalid/webhook");
		WebhookEvent event = mock(WebhookEvent.class);
		when(event.getType()).thenReturn(eventType);
		when(webhook.getEvents()).thenReturn(List.of(event));
		return webhook;
	}

	private static Double queueSize(SimpleMeterRegistry registry) {
		var gauge = registry.find("webhook.queue.size").gauge();
		return gauge == null ? null : gauge.value();
	}
}
