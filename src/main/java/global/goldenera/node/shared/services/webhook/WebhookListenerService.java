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

import static global.goldenera.node.core.config.CoreAsyncConfig.WEBHOOK_EVENT_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockReorgEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Listens for core blockchain events and forwards them to BLOCKCHAIN webhooks.
 */
@Slf4j
@Service
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class WebhookListenerService {

	WebhookDispatchService webhookDispatchService;
	ObjectFactory<Executor> webhookEventExecutor;

	@Autowired
	public WebhookListenerService(
			WebhookDispatchService webhookDispatchService,
			@Qualifier(WEBHOOK_EVENT_EXECUTOR) ObjectFactory<Executor> webhookEventExecutor) {
		this.webhookDispatchService = webhookDispatchService;
		this.webhookEventExecutor = webhookEventExecutor;
	}

	WebhookListenerService(
			WebhookDispatchService webhookDispatchService,
			Executor webhookEventExecutor) {
		this(webhookDispatchService, () -> webhookEventExecutor);
	}

	// ==================== BLOCKCHAIN EVENTS ====================

	/**
	 * Handles new blocks from core blockchain.
	 * Sends to BLOCKCHAIN type webhooks.
	 */
	@EventListener
	public void handleBlockConnected(BlockConnectedEvent event) {
		submitWebhookEvent("block connected", () -> processBlockConnected(event));
	}

	private void processBlockConnected(BlockConnectedEvent event) {
		log.debug("Processing BlockConnectedEvent: {}", event.getBlock().getHash());
		webhookDispatchService.processNewBlockEvent(event.getBlock(), event.getEvents(), WebhookType.BLOCKCHAIN);
		int index = 0;
		for (Tx tx : event.getBlock().getTxs()) {
			webhookDispatchService.processAddressActivityEvent(event.getBlock(), tx,
					WebhookTxStatus.CONFIRMED, index++, WebhookType.BLOCKCHAIN);
		}
	}

	/**
	 * Handles blockchain reorganization events.
	 * Sends to BLOCKCHAIN type webhooks.
	 */
	@EventListener
	public void handleBlockReorg(BlockReorgEvent event) {
		submitWebhookEvent("block reorg", () -> processBlockReorg(event));
	}

	private void processBlockReorg(BlockReorgEvent event) {
		log.debug("Processing BlockReorgEvent: old=#{} -> new=#{}", event.getOldHeight(), event.getNewHeight());
		webhookDispatchService.processReorgEvent(
				event.getOldHeight(), event.getOldHash(),
				event.getNewHeight(), event.getNewHash(),
				WebhookType.BLOCKCHAIN);
	}

	/**
	 * Handles transactions entering the mempool.
	 * This is the key logic for PENDING vs. REVERTED.
	 * Sends to BLOCKCHAIN type webhooks only.
	 */
	@EventListener
	public void handleMempoolTxAdd(MempoolTxAddEvent event) {
		submitWebhookEvent("mempool add", () -> processMempoolTxAdd(event));
	}

	private void processMempoolTxAdd(MempoolTxAddEvent event) {
		log.trace("Processing MempoolTxAddEvent: {}", event.getEntry().getTx().getHash());
		switch (event.getReason()) {
			case NEW:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.PENDING, null, WebhookType.BLOCKCHAIN);
				break;
			case REORG:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.REVERTED, null, WebhookType.BLOCKCHAIN);
				break;
		}
	}

	/**
	 * Handles transactions being removed from the mempool.
	 * Sends to BLOCKCHAIN type webhooks only.
	 */
	@EventListener
	public void handleMempoolTxRemove(MempoolTxRemoveEvent event) {
		submitWebhookEvent("mempool remove", () -> processMempoolTxRemove(event));
	}

	private void processMempoolTxRemove(MempoolTxRemoveEvent event) {
		log.trace("Processing MempoolTxRemoveEvent: {}", event.getEntry().getHash());

		switch (event.getReason()) {
			case MINED:
				break;
			case RBF:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.REPLACED, null, WebhookType.BLOCKCHAIN);
				break;
			case STALE_NONCE:
			case EXPIRED:
			case INVALID:
			case EVICTED_FULL:
			case INSUFFICIENT_FUNDS:
				webhookDispatchService.processAddressActivityEvent(null, event.getEntry().getTx(),
						WebhookTxStatus.DROPPED, null, WebhookType.BLOCKCHAIN);
				break;
		}
	}

	private void submitWebhookEvent(String eventType, Runnable action) {
		webhookEventExecutor.getObject().execute(() -> {
			try {
				action.run();
			} catch (RuntimeException exception) {
				log.error("Failed to process {} webhook event", eventType, exception);
			}
		});
	}
}
