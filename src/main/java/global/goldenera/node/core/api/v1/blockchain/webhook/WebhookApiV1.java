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
package global.goldenera.node.core.api.v1.blockchain.webhook;

import static lombok.AccessLevel.PRIVATE;

import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookCreateInDtoV1;
import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookDtoV1;
import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookDtoV1_Page;
import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookSetEnabledInDtoV1;
import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookUpdateInDtoV1;
import global.goldenera.node.core.api.v1.blockchain.webhook.mappers.WebhookMapper;
import global.goldenera.node.core.webhook.business.WebhookService;
import global.goldenera.node.core.webhook.core.WebhookCoreService;
import global.goldenera.node.core.webhook.entities.Webhook;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@PreAuthorize("hasAuthority('READ_WRITE_WEBHOOK')")
@RequestMapping(value = "/api/core/v1/blockchain/webhook")
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class WebhookApiV1 {

	WebhookCoreService webhookCoreService;
	WebhookService webhookService;
	WebhookMapper webhookMapper;

	@GetMapping("page")
	public WebhookDtoV1_Page apiV1WebhookGetPage(
			@RequestParam int pageNumber,
			@RequestParam int pageSize,
			@RequestParam(required = false) Sort.Direction direction,
			@RequestParam(required = false) String labelLike, Authentication authentication) {
		ApiKey apiKey = validateAuth(authentication);
		return webhookMapper
				.map(webhookCoreService.getPage(pageNumber, pageSize, direction, apiKey.getId(), labelLike));
	}

	@GetMapping("{id}")
	public WebhookDtoV1 apiV1WebhookGetById(@PathVariable String id, Authentication authentication) {
		UUID webhookId = validateWebhookId(id);
		ApiKey apiKey = validateAuth(authentication);
		Webhook webhook = webhookCoreService.getById(webhookId);
		if (!webhook.getCreatedByApiKey().equals(apiKey)) {
			throw new GEValidationException("You do not have permission to access this webhook");
		}
		return webhookMapper.map(webhook);
	}

	@PutMapping("{id}")
	public WebhookDtoV1 apiV1WebhookUpdate(
			@PathVariable String id,
			@RequestBody WebhookUpdateInDtoV1 payload,
			Authentication authentication) {
		UUID webhookId = validateWebhookId(id);
		ApiKey apiKey = validateAuth(authentication);
		Webhook webhook = webhookService.updateWebhook(apiKey, webhookId, payload);
		return webhookMapper.map(webhook);
	}

	@PutMapping("{id}/set-enabled")
	public WebhookDtoV1 apiV1WebhookSetEnabled(
			@PathVariable String id,
			@RequestBody WebhookSetEnabledInDtoV1 payload,
			Authentication authentication) {
		UUID webhookId = validateWebhookId(id);
		ApiKey apiKey = validateAuth(authentication);
		Webhook webhook = webhookService.toggleWebhookEnabled(apiKey, webhookId, payload.isEnabled());
		return webhookMapper.map(webhook);
	}

	@DeleteMapping("{id}")
	public void apiV1WebhookDelete(
			@PathVariable String id,
			Authentication authentication) {
		UUID webhookId = validateWebhookId(id);
		ApiKey apiKey = validateAuth(authentication);
		webhookService.deleteWebhook(apiKey, webhookId);
	}

	@PostMapping
	public WebhookDtoV1.CreatedWebhookDtoV1 apiV1WebhookCreate(
			@RequestBody WebhookCreateInDtoV1 payload,
			Authentication authentication) {
		ApiKey apiKey = validateAuth(authentication);
		WebhookService.CreatedWebhook createdWebhook = webhookService.createWebhook(apiKey, payload);
		return webhookMapper.map(createdWebhook);
	}

	private ApiKey validateAuth(Authentication authentication) {
		if (authentication == null || authentication.getPrincipal() == null
				|| !(authentication.getPrincipal() instanceof ApiKey apiKey)) {
			throw new GEValidationException("Invalid authentication");
		}
		return apiKey;
	}

	private UUID validateWebhookId(String id) {
		UUID webhookId;
		try {
			webhookId = UUID.fromString(id);
		} catch (Exception e) {
			throw new GEValidationException("Invalid webhook id: " + id);
		}
		return webhookId;
	}
}