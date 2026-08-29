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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookCreateInDtoV1;
import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookUpdateInDtoV1;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.services.core.WebhookCoreService;
import global.goldenera.node.shared.utils.WebhookValidator;
import global.goldenera.node.shared.utils.WebhookValidator.UrlData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class WebhookService {

	private static final int DTO_VERSION = 1;
	WebhookCoreService webhookCoreService;
	GeneralProperties generalProperties;
	ObjectProvider<UniversalWebhookActivationService> activationService;

	@Autowired
	public WebhookService(
			WebhookCoreService webhookCoreService,
			GeneralProperties generalProperties,
			ObjectProvider<UniversalWebhookActivationService> activationService) {
		this.webhookCoreService = webhookCoreService;
		this.generalProperties = generalProperties;
		this.activationService = activationService;
	}

	WebhookService(WebhookCoreService webhookCoreService, GeneralProperties generalProperties) {
		this.webhookCoreService = webhookCoreService;
		this.generalProperties = generalProperties;
		this.activationService = null;
	}

	@Transactional(rollbackFor = Exception.class)
	public CreatedWebhook createWebhook(
			@NonNull ApiKey apiKey,
			@NonNull WebhookCreateInDtoV1 payload) {
		if (payload.getType() == WebhookType.BRIDGE) {
			throw new GEValidationException("BRIDGE webhook destinations are managed by the bridge API");
		}
		if (payload.getType() == WebhookType.EXPLORER && !generalProperties.isExplorerEnable()) {
			throw new GEValidationException("Explorer webhooks require ge.general.explorer-enable=true");
		}
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
		if (apiKey.getWebhookSecretKey() == null) {
			throw new GEFailedException(
					"This legacy API key has no webhook signing secret; create a new API key before creating webhooks");
		}

		Webhook webhook = webhookCoreService.create(new Webhook(
				payload.getType(),
				DTO_VERSION,
				label,
				description,
				urlData.getUrl(),
				apiKey,
				queryParams,
				headers));

		return new CreatedWebhook(webhook);
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
		requirePublicWebhook(webhook);
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
		requirePublicWebhook(webhook);
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
		requirePublicWebhook(webhook);
		if (!webhook.getCreatedByApiKey().equals(apiKey)) {
			throw new GEFailedException("You do not have permission to update the enabled status of this webhook");
		}
		webhook.setEnabled(enabled);
		Webhook updated = webhookCoreService.update(webhook);
		UniversalWebhookActivationService activator = activationService == null ? null : activationService.getIfAvailable();
		if (enabled && activator != null) {
			activator.resetAfterReEnable(updated);
		}
		return updated;
	}

	private void requirePublicWebhook(Webhook webhook) {
		if (webhook.getType() == WebhookType.BRIDGE) {
			throw new GEValidationException("BRIDGE webhook destinations are managed by the bridge API");
		}
	}

	@AllArgsConstructor
	@Getter
	@FieldDefaults(level = PRIVATE)
	public static class CreatedWebhook {
		@NonNull
		Webhook webhook;
	}

}
