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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookEventDtoV1;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookEventType;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.services.core.WebhookCoreService;

class WebhookEventServiceTest {
	@Test
	void sharedSubscriptionApiRejectsBridgeDestinations() {
		WebhookCoreService core = mock(WebhookCoreService.class);
		ApiKey apiKey = mock(ApiKey.class);
		when(apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)).thenReturn(true);
		Webhook webhook = mock(Webhook.class);
		when(webhook.getCreatedByApiKey()).thenReturn(apiKey);
		when(webhook.getType()).thenReturn(WebhookType.BRIDGE);
		UUID webhookId = UUID.randomUUID();
		when(core.getById(webhookId)).thenReturn(webhook);
		WebhookEventService service = new WebhookEventService(
				core, mock(DurableUniversalWebhookStore.class), mock(LifecycleJournalQuery.class), mock(ChainQuery.class));
		List<WebhookEventDtoV1> events = List.of(
				new WebhookEventDtoV1(WebhookEventType.NEW_BLOCK, null, null));

		assertThatThrownBy(() -> service.subscribe(webhookId, apiKey, events))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("bridge API");
		assertThatThrownBy(() -> service.unsubscribe(webhookId, apiKey, events))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("bridge API");
	}
}
