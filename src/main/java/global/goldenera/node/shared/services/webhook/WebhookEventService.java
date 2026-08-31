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

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.shared.api.v1.webhook.dtos.WebhookEventDtoV1;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.shared.entities.ApiKey;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.services.core.WebhookCoreService;
import global.goldenera.node.shared.services.core.WebhookCoreService.WebhookEventFilter;
import global.goldenera.node.shared.services.core.WebhookCoreService.UniversalWebhookActivationBoundary;
import global.goldenera.node.shared.utils.WebhookValidator;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class WebhookEventService {

	private static final int MAX_WEBHOOK_EVENTS_PER_REQUEST = 5000;

	WebhookCoreService webhookCoreService;
	DurableUniversalWebhookStore durableStore;
	LifecycleJournalQuery lifecycleJournal;
	ChainQuery chainQuery;

	@Transactional(rollbackFor = Exception.class)
	public int subscribe(
			@NonNull UUID webhookId,
			@NonNull ApiKey apiKey,
			@NonNull List<WebhookEventDtoV1> events) {
		List<WebhookEventFilter> filters = WebhookValidator.WebhookEvent.validateEvents(events);
		if (!apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)) {
			throw new GEValidationException("You do not have permission to create webhook events");
		}
		if (events.isEmpty()) {
			throw new GEValidationException("Minimum one webhook event is required.");
		}
		if (events.size() > MAX_WEBHOOK_EVENTS_PER_REQUEST) {
			throw new GEValidationException(
					String.format("Maximum %d webhook events per request is allowed.", MAX_WEBHOOK_EVENTS_PER_REQUEST));
		}
		Webhook webhook = webhookCoreService.getById(webhookId);
		if (!webhook.getCreatedByApiKey().equals(apiKey)) {
			throw new GEValidationException("You do not have permission to create webhook events for this webhook");
		}
		if (webhook.getType() == WebhookType.BRIDGE) {
			throw new GEValidationException("BRIDGE webhook subscriptions are managed by the bridge API");
		}
		long sourceEventId = durableStore.sourceCursor(webhook.getType());
		UniversalWebhookActivationBoundary activation;
		if (webhook.getType() == WebhookType.BLOCKCHAIN) {
			var canonicalHead = lifecycleJournal.head(LifecycleJournalStream.CANONICAL);
			var mempoolHead = lifecycleJournal.head(LifecycleJournalStream.MEMPOOL);
			activation = new UniversalWebhookActivationBoundary(
					sourceEventId,
					canonicalHead.epoch(), canonicalHead.sequence(),
					mempoolHead.epoch(), mempoolHead.sequence(), -1L);
		} else {
			activation = new UniversalWebhookActivationBoundary(
					sourceEventId, null, 0L, null, 0L, chainQuery.getLatestBlockHeight().orElse(-1L));
		}
		return webhookCoreService.createBulkFilters(webhook, filters, activation);
	}

	@Transactional(rollbackFor = Exception.class)
	public int unsubscribe(
			@NonNull UUID webhookId,
			@NonNull ApiKey apiKey,
			@NonNull List<WebhookEventDtoV1> events) {
		List<WebhookEventFilter> filters = WebhookValidator.WebhookEvent.validateEvents(events);
		if (!apiKey.hasPermission(ApiKeyPermission.READ_WRITE_WEBHOOK)) {
			throw new GEValidationException("You do not have permission to delete webhook events");
		}
		if (events.isEmpty()) {
			throw new GEValidationException("Minimum one webhook event is required.");
		}
		if (events.size() > MAX_WEBHOOK_EVENTS_PER_REQUEST) {
			throw new GEValidationException(
					String.format("Maximum %d webhook events per request is allowed.", MAX_WEBHOOK_EVENTS_PER_REQUEST));
		}
		Webhook webhook = webhookCoreService.getById(webhookId);
		if (!webhook.getCreatedByApiKey().equals(apiKey)) {
			throw new GEValidationException("You do not have permission to delete webhook events for this webhook");
		}
		if (webhook.getType() == WebhookType.BRIDGE) {
			throw new GEValidationException("BRIDGE webhook subscriptions are managed by the bridge API");
		}
		return webhookCoreService.deleteBulkFilters(webhook, filters);
	}

}
