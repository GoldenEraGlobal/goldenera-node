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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookCreateInDtoV1;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.services.core.WebhookCoreService;

class WebhookServiceTest {

	@Test
	void bridgeDestinationCannotBeCreatedThroughSharedWebhookApi() {
		WebhookService service = new WebhookService(
				mock(WebhookCoreService.class), new GeneralProperties());
		WebhookCreateInDtoV1 payload = new WebhookCreateInDtoV1();
		payload.setType(WebhookType.BRIDGE);

		assertThatThrownBy(() -> service.createWebhook(mock(ApiKey.class), payload))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("managed by the bridge API");
	}

	@Test
	void explorerWebhookRequiresExplorerRuntime() {
		GeneralProperties generalProperties = new GeneralProperties();
		generalProperties.setExplorerEnable(false);
		WebhookService service = new WebhookService(
				mock(WebhookCoreService.class), generalProperties);
		WebhookCreateInDtoV1 payload = new WebhookCreateInDtoV1();
		payload.setType(WebhookType.EXPLORER);

		assertThatThrownBy(() -> service.createWebhook(mock(ApiKey.class), payload))
				.isInstanceOf(GEValidationException.class)
				.hasMessage("Explorer webhooks require ge.general.explorer-enable=true");
	}

	@Test
	void legacyApiKeyWithoutSigningSecretCannotCreateWebhook() {
		GeneralProperties generalProperties = new GeneralProperties();
		generalProperties.setExplorerEnable(true);
		WebhookCoreService coreService = mock(WebhookCoreService.class);
		WebhookService service = new WebhookService(coreService, generalProperties);
		ApiKey apiKey = mock(ApiKey.class);
		when(apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)).thenReturn(true);
		when(apiKey.getId()).thenReturn(1L);
		when(apiKey.getMaxWebhooks()).thenReturn(10L);
		WebhookCreateInDtoV1 payload = new WebhookCreateInDtoV1();
		payload.setType(WebhookType.EXPLORER);
		payload.setLabel("legacy_hook");
		payload.setUrl("https://example.com/webhook");

		assertThatThrownBy(() -> service.createWebhook(apiKey, payload))
				.isInstanceOf(GEFailedException.class)
				.hasMessageContaining("legacy API key has no webhook signing secret");
	}

	@Test
	void newWebhookUsesApiKeySecretWithoutPersistingItsOwnSecret() {
		GeneralProperties generalProperties = new GeneralProperties();
		generalProperties.setExplorerEnable(true);
		WebhookCoreService coreService = mock(WebhookCoreService.class);
		when(coreService.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
		WebhookService service = new WebhookService(coreService, generalProperties);
		ApiKey apiKey = mock(ApiKey.class);
		when(apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)).thenReturn(true);
		when(apiKey.getId()).thenReturn(1L);
		when(apiKey.getMaxWebhooks()).thenReturn(10L);
		when(apiKey.getWebhookSecretKey()).thenReturn(Bytes.wrap(new byte[] { 1 }));
		WebhookCreateInDtoV1 payload = new WebhookCreateInDtoV1();
		payload.setType(WebhookType.EXPLORER);
		payload.setLabel("bridge_hook");
		payload.setUrl("https://example.com/webhook");

		WebhookService.CreatedWebhook created = service.createWebhook(apiKey, payload);

		assertThat(created.getWebhook().getSecretKey()).isNull();
		assertThat(created.getWebhook().getCreatedByApiKey()).isSameAs(apiKey);
	}
}
