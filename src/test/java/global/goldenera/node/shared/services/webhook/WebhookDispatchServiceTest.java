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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

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
import global.goldenera.node.shared.services.core.WebhookCoreService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.OkHttpClient;

class WebhookDispatchServiceTest {

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
		dispatch.loadIndexOnStartup();

		dispatch.processNewBlockEvent(mock(Block.class), List.of(), WebhookType.BLOCKCHAIN);

		assertThat(queueSize(registry, blockWebhookId)).isEqualTo(1.0);
		assertThat(queueSize(registry, reorgWebhookId)).isNull();
		assertThat(queueSize(registry, explorerWebhookId)).isNull();

		dispatch.processReorgEvent(1L, Hash.ZERO, 2L, Hash.ZERO, WebhookType.BLOCKCHAIN);

		assertThat(queueSize(registry, blockWebhookId)).isEqualTo(1.0);
		assertThat(queueSize(registry, reorgWebhookId)).isEqualTo(1.0);
		assertThat(queueSize(registry, explorerWebhookId)).isNull();

		dispatch.processNewBlockEvent(mock(Block.class), List.of(), WebhookType.EXPLORER);

		assertThat(queueSize(registry, explorerWebhookId)).isEqualTo(1.0);
	}

	private static Webhook webhook(UUID id, WebhookType webhookType, WebhookEventType eventType) {
		ApiKey apiKey = mock(ApiKey.class);
		when(apiKey.getId()).thenReturn((long) id.hashCode());
		Webhook webhook = mock(Webhook.class);
		when(webhook.getId()).thenReturn(id);
		when(webhook.getCreatedByApiKey()).thenReturn(apiKey);
		when(webhook.getSecretKey()).thenReturn(Bytes.wrap(new byte[32]));
		when(webhook.getDtoVersion()).thenReturn(1);
		when(webhook.getType()).thenReturn(webhookType);
		when(webhook.getUrl()).thenReturn("https://example.invalid/webhook");
		WebhookEvent event = mock(WebhookEvent.class);
		when(event.getType()).thenReturn(eventType);
		when(webhook.getEvents()).thenReturn(List.of(event));
		return webhook;
	}

	private static Double queueSize(SimpleMeterRegistry registry, UUID webhookId) {
		var gauge = registry.find("webhook.queue.size")
				.tag("webhookId", webhookId.toString())
				.gauge();
		return gauge == null ? null : gauge.value();
	}
}
