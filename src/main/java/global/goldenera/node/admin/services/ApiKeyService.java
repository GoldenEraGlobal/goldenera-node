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

import static lombok.AccessLevel.PRIVATE;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.admin.utils.ApiKeyValidator;
import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.services.ApiKeyCoreService;
import global.goldenera.node.shared.utils.StringUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class ApiKeyService {

	static final int ACCESS_TOKEN_LENGTH = 64;

	HmacComponent hmacComponent;
	ApiKeyCoreService apiKeyCoreService;

	@Transactional(rollbackFor = Exception.class)
	public CreatedApiKey createApiKey(
			@NonNull String label,
			String description,
			@NonNull Set<ApiKeyPermission> permissions,
			Instant expiresAt,
			Long maxWebhooks) throws GEValidationException {
		label = ApiKeyValidator.label(label);
		description = ApiKeyValidator.description(description);
		permissions = ApiKeyValidator.permissions(permissions);
		expiresAt = ApiKeyValidator.expiresAt(expiresAt);
		maxWebhooks = ApiKeyValidator.maxWebhooks(maxWebhooks);

		String prefix = "sk_" + StringUtil.generateSafeString(8);
		String secret = StringUtil.generateSafeString(ACCESS_TOKEN_LENGTH);
		String fullApiKey = prefix + "_" + secret;
		Bytes hashedSecret = hmacComponent.hash(Bytes.wrap(secret.getBytes(StandardCharsets.UTF_8)));

		ApiKey apiKey = apiKeyCoreService
				.create(new ApiKey(
						permissions,
						label,
						description,
						prefix,
						hashedSecret,
						true,
						maxWebhooks,
						expiresAt));

		return new CreatedApiKey(apiKey, fullApiKey);
	}

	@Transactional(rollbackFor = Exception.class)
	public ApiKey updateApiKey(
			@NonNull Long id,
			@NonNull String label,
			String description,
			@NonNull Set<ApiKeyPermission> permissions,
			Instant expiresAt,
			Long maxWebhooks) throws GEValidationException, GENotFoundException {
		label = ApiKeyValidator.label(label);
		description = ApiKeyValidator.description(description);
		permissions = ApiKeyValidator.permissions(permissions);
		expiresAt = ApiKeyValidator.expiresAt(expiresAt);
		maxWebhooks = ApiKeyValidator.maxWebhooks(maxWebhooks);
		ApiKey apiKey = apiKeyCoreService.getById(id);

		apiKey.setLabel(label);
		apiKey.setDescription(description);
		apiKey.setPermissions(permissions);
		apiKey.setExpiresAt(expiresAt);
		apiKey.setMaxWebhooks(maxWebhooks);

		return apiKeyCoreService.update(apiKey);
	}

	@Transactional(rollbackFor = Exception.class)
	public ApiKey toggleApiKeyEnabled(@NonNull Long id, boolean enabled) throws GENotFoundException {
		ApiKey apiKey = apiKeyCoreService.getById(id);
		apiKey.setEnabled(enabled);
		return apiKeyCoreService.update(apiKey);
	}

	@AllArgsConstructor
	@Getter
	@FieldDefaults(level = PRIVATE)
	public static class CreatedApiKey {
		@NonNull
		ApiKey apiKey;
		@NonNull
		String secretKey;
	}
}
