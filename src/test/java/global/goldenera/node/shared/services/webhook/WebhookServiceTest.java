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

import org.junit.jupiter.api.Test;

import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookCreateInDtoV1;
import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.services.core.WebhookCoreService;

class WebhookServiceTest {

	@Test
	void explorerWebhookRequiresExplorerRuntime() {
		GeneralProperties generalProperties = new GeneralProperties();
		generalProperties.setExplorerEnable(false);
		WebhookService service = new WebhookService(
				mock(AESGCMComponent.class), mock(WebhookCoreService.class), generalProperties);
		WebhookCreateInDtoV1 payload = new WebhookCreateInDtoV1();
		payload.setType(WebhookType.EXPLORER);

		assertThatThrownBy(() -> service.createWebhook(mock(ApiKey.class), payload))
				.isInstanceOf(GEValidationException.class)
				.hasMessage("Explorer webhooks require ge.general.explorer-enable=true");
	}
}
