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

import static lombok.AccessLevel.PRIVATE;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookCreateInDtoV1;
import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookUpdateInDtoV1;
import global.goldenera.node.shared.components.AESGCMComponent;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.services.core.WebhookCoreService;
import global.goldenera.node.shared.utils.StringUtil;
import global.goldenera.node.shared.utils.WebhookValidator;
import global.goldenera.node.shared.utils.WebhookValidator.UrlData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class WebhookService {

	private static final int DTO_VERSION = 1;
	private static final int SECRET_KEY_LENGTH = 64;

	AESGCMComponent aesGcmComponent;
	WebhookCoreService webhookCoreService;

	@Transactional(rollbackFor = Exception.class)
	public CreatedWebhook createWebhook(
			@NonNull ApiKey apiKey,
			@NonNull WebhookCreateInDtoV1 payload) {
		String label = WebhookValidator.label(payload.getLabel());
		String description = WebhookValidator.description(payload.getDescription());
		UrlData urlData = WebhookValidator.url(payload.getUrl());
		Map<String, Object> headers = payload.getHeaders();
		Map<String, Object> queryParams = payload.getQueryParams();
		if (headers == null) {
			headers = new HashMap<>();
		}
		if (queryParams == null) {
			queryParams = new HashMap<>();
		}
		if (!urlData.getQueryParams().isEmpty()) {
			queryParams.putAll(urlData.getQueryParams());
		}
		WebhookValidator.validateHeadersOrQuery(headers);
		WebhookValidator.validateHeadersOrQuery(queryParams);

		if (!apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)) {
			throw new GEFailedException("You do not have permission to create webhooks");
		}

		long count = webhookCoreService.getCountByApiKeyId(apiKey.getId());
		if (apiKey.getMaxWebhooks() != null && count >= apiKey.getMaxWebhooks()) {
			throw new GEFailedException("You have reached the maximum number of webhooks");
		}

		String secret = StringUtil.generateSafeString(SECRET_KEY_LENGTH);
		Bytes secretBytes = Bytes.wrap(secret.getBytes(StandardCharsets.UTF_8));
		Bytes encryptedSecret = aesGcmComponent.encrypt(secretBytes);

		Webhook webhook = webhookCoreService.create(new Webhook(
				payload.getType(),
				DTO_VERSION,
				label,
				description,
				urlData.getUrl(),
				encryptedSecret,
				apiKey,
				queryParams,
				headers));

		return new CreatedWebhook(webhook, secret);
	}

	@Transactional(rollbackFor = Exception.class)
	public Webhook updateWebhook(
			@NonNull ApiKey apiKey,
			@NonNull UUID id,
			@NonNull WebhookUpdateInDtoV1 payload) {
		String label = WebhookValidator.label(payload.getLabel());
		String description = WebhookValidator.description(payload.getDescription());
		Map<String, Object> headers = payload.getHeaders();
		Map<String, Object> queryParams = payload.getQueryParams();
		if (headers == null) {
			headers = new HashMap<>();
		}
		if (queryParams == null) {
			queryParams = new HashMap<>();
		}
		WebhookValidator.validateHeadersOrQuery(headers);
		WebhookValidator.validateHeadersOrQuery(queryParams);

		if (!apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)) {
			throw new GEFailedException("You do not have permission to update webhooks");
		}

		Webhook webhook = webhookCoreService.getById(id);
		if (!webhook.getCreatedByApiKey().equals(apiKey)) {
			throw new GEFailedException("You do not have permission to update this webhook");
		}

		webhook.setLabel(label);
		webhook.setDescription(description);
		webhook.setQueryParams(queryParams);
		webhook.setHeaders(headers);

		return webhookCoreService.update(webhook);
	}

	@Transactional(rollbackFor = Exception.class)
	public void deleteWebhook(
			@NonNull ApiKey apiKey,
			@NonNull UUID id) {
		if (!apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)) {
			throw new GEFailedException("You do not have permission to delete webhooks");
		}

		Webhook webhook = webhookCoreService.getById(id);
		if (!webhook.getCreatedByApiKey().equals(apiKey)) {
			throw new GEFailedException("You do not have permission to delete this webhook");
		}
		webhookCoreService.delete(webhook.getId());
	}

	@Transactional(rollbackFor = Exception.class)
	public Webhook toggleWebhookEnabled(
			@NonNull ApiKey apiKey,
			@NonNull UUID id,
			boolean enabled) {
		if (!apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)) {
			throw new GEFailedException("You do not have permission to update the enabled status of webhooks");
		}

		Webhook webhook = webhookCoreService.getById(id);
		if (!webhook.getCreatedByApiKey().equals(apiKey)) {
			throw new GEFailedException("You do not have permission to update the enabled status of this webhook");
		}
		webhook.setEnabled(enabled);
		return webhookCoreService.update(webhook);
	}

	@AllArgsConstructor
	@Getter
	@FieldDefaults(level = PRIVATE)
	public static class CreatedWebhook {
		@NonNull
		Webhook webhook;
		@NonNull
		String secretKey;
	}
}
