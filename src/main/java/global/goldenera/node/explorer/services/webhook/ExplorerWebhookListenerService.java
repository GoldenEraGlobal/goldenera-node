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
package global.goldenera.node.explorer.services.webhook;

import static global.goldenera.node.core.config.CoreAsyncConfig.WEBHOOK_EVENT_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.node.explorer.events.ExBlockConnectedEvent;
import global.goldenera.node.explorer.events.ExBlockReorgEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.WebhookDispatchService;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@ConditionalOnProperty(
		prefix = "ge.general",
		name = "webhook-enable",
		havingValue = "true",
		matchIfMissing = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ExplorerWebhookListenerService {

	WebhookDispatchService webhookDispatchService;
	ExplorerRuntimeReadiness explorerReadiness;
	ObjectFactory<Executor> webhookEventExecutor;

	public ExplorerWebhookListenerService(
			WebhookDispatchService webhookDispatchService,
			ExplorerRuntimeReadiness explorerReadiness,
			@Qualifier(WEBHOOK_EVENT_EXECUTOR) ObjectFactory<Executor> webhookEventExecutor) {
		this.webhookDispatchService = webhookDispatchService;
		this.explorerReadiness = explorerReadiness;
		this.webhookEventExecutor = webhookEventExecutor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleExBlockConnected(ExBlockConnectedEvent event) {
		submitWebhookEvent("explorer block connected", () -> {
			log.debug("Processing ExBlockConnectedEvent: {}", event.getBlock().getHash());
			webhookDispatchService.processNewBlockEvent(
					event.getBlock(), event.getEvents(), WebhookType.EXPLORER);
			int index = 0;
			for (Tx tx : event.getBlock().getTxs()) {
				webhookDispatchService.processAddressActivityEvent(
						event.getBlock(), tx, WebhookTxStatus.CONFIRMED, index++, WebhookType.EXPLORER);
			}
		});
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleExBlockReorg(ExBlockReorgEvent event) {
		submitWebhookEvent("explorer block reorg", () -> {
			log.debug("Processing ExBlockReorgEvent: old=#{} -> new=#{}",
					event.getOldHeight(), event.getNewHeight());
			webhookDispatchService.processReorgEvent(
					event.getOldHeight(), event.getOldHash(),
					event.getNewHeight(), event.getNewHash(),
					WebhookType.EXPLORER);
		});
	}

	private void submitWebhookEvent(String eventType, Runnable action) {
		if (!explorerReadiness.isReady()) {
			return;
		}
		webhookEventExecutor.getObject().execute(() -> {
			if (!explorerReadiness.isReady()) {
				return;
			}
			try {
				action.run();
			} catch (RuntimeException exception) {
				log.error("Failed to process {} webhook event", eventType, exception);
			}
		});
	}
}
