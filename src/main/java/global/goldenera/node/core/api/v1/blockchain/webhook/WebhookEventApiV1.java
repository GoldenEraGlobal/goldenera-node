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

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookEventDtoV1;
import global.goldenera.node.core.api.v1.blockchain.webhook.dtos.WebhookEventDtoV1_Page;
import global.goldenera.node.core.api.v1.blockchain.webhook.mappers.WebhookEventMapper;
import global.goldenera.node.core.enums.WebhookEventType;
import global.goldenera.node.core.webhook.business.WebhookEventService;
import global.goldenera.node.core.webhook.core.WebhookCoreService;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/api/core/v1/blockchain/webhook/{webhookId}/event")
@PreAuthorize("hasAuthority('READ_WRITE_WEBHOOK')")
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class WebhookEventApiV1 {

	WebhookCoreService webhookCoreService;
	WebhookEventService webhookEventService;
	WebhookEventMapper webhookEventMapper;

	@GetMapping("page")
	public WebhookEventDtoV1_Page apiV1WebhookEventGetPage(
			@PathVariable String webhookId,
			@RequestParam int pageNumber,
			@RequestParam int pageSize,
			@RequestParam(required = false) Sort.Direction direction,
			@RequestParam(required = false) WebhookEventType type,
			@RequestParam(required = false) Address addressFilter,
			@RequestParam(required = false) Address tokenAddressFilter,
			Authentication authentication) {
		ApiKey apiKey = validateAuth(authentication);
		return webhookEventMapper
				.map(webhookCoreService.getEventPage(
						pageNumber,
						pageSize,
						direction,
						apiKey,
						validateWebhookId(webhookId),
						type,
						addressFilter,
						tokenAddressFilter));
	}

	@PostMapping("subscribe")
	public int apiV1WebhookEventSubscribe(
			@PathVariable String webhookId,
			@RequestBody List<WebhookEventDtoV1> events,
			Authentication authentication) {
		ApiKey apiKey = validateAuth(authentication);
		return webhookEventService.subscribe(validateWebhookId(webhookId), apiKey, events);
	}

	@PutMapping("unsubscribe")
	public int apiV1WebhookEventUnsubscribe(
			@PathVariable String webhookId,
			@RequestBody List<WebhookEventDtoV1> events,
			Authentication authentication) {
		ApiKey apiKey = validateAuth(authentication);
		return webhookEventService.unsubscribe(validateWebhookId(webhookId), apiKey, events);
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