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
package global.goldenera.node.admin.api.v1.apikey.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import global.goldenera.node.admin.api.v1.apikey.dtos.ApiKeyDtoV1;
import global.goldenera.node.admin.services.ApiKeyService;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;

class ApiKeyMapperTest {

	@Test
	void createResponseContainsBothOneTimeSecrets() {
		ApiKey apiKey = mock(ApiKey.class);
		when(apiKey.getPermissions()).thenReturn(Set.of(ApiKeyPermission.BRIDGE_MANAGE_SUBSCRIPTIONS));
		when(apiKey.getId()).thenReturn(1L);
		when(apiKey.getLabel()).thenReturn("bridge_key");
		when(apiKey.getCreatedAt()).thenReturn(Instant.EPOCH);
		ApiKeyService.CreatedApiKey created = new ApiKeyService.CreatedApiKey(
				apiKey, "sk_prefix_api-secret", "webhook-secret");

		ApiKeyDtoV1.CreatedApiKeyDtoV1 dto = new ApiKeyMapper().map(created);

		assertThat(dto.getSecretKey()).isEqualTo("sk_prefix_api-secret");
		assertThat(dto.getWebhookSecretKey()).isEqualTo("webhook-secret");
	}
}
