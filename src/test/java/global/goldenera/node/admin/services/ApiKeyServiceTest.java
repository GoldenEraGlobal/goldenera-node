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
package global.goldenera.node.admin.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.services.core.ApiKeyCoreService;

class ApiKeyServiceTest {

	@Test
	void createReturnsWebhookSecretOnceAndPersistsOnlyEncryptedValue() {
		HmacComponent hmac = mock(HmacComponent.class);
		AESGCMComponent encryption = mock(AESGCMComponent.class);
		ApiKeyCoreService coreService = mock(ApiKeyCoreService.class);
		Bytes hashedApiSecret = Bytes.wrap(new byte[] { 1 });
		Bytes encryptedWebhookSecret = Bytes.wrap(new byte[] { 2 });
		when(hmac.hash(any(Bytes.class))).thenReturn(hashedApiSecret);
		when(encryption.encrypt(any(Bytes.class))).thenReturn(encryptedWebhookSecret);
		when(coreService.create(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));
		ApiKeyService service = new ApiKeyService(hmac, encryption, coreService);

		ApiKeyService.CreatedApiKey created = service.createApiKey(
				"bridge_key", null, Set.of(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS), null, 10L);

		ArgumentCaptor<Bytes> plaintextCaptor = ArgumentCaptor.forClass(Bytes.class);
		verify(encryption).encrypt(plaintextCaptor.capture());
		assertThat(new String(plaintextCaptor.getValue().toArray(), StandardCharsets.UTF_8))
				.isEqualTo(created.getWebhookSecretKey());
		assertThat(created.getWebhookSecretKey()).isNotBlank();
		assertThat(created.getSecretKey()).startsWith(created.getApiKey().getKeyPrefix() + "_");
		assertThat(created.getApiKey().getSecretKey()).isEqualTo(hashedApiSecret);
		assertThat(created.getApiKey().getWebhookSecretKey()).isEqualTo(encryptedWebhookSecret);
	}
}
