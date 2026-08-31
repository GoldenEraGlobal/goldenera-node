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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.node.explorer.events.ExBlockConnectedEvent;
import global.goldenera.node.explorer.events.ExBlockReorgEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.UniversalWebhookEventSink;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "ge.general",
		name = { "postgresql-enable", "webhook-enable" },
		havingValue = "true",
		matchIfMissing = true)
public class ExplorerWebhookListenerService {
	private final UniversalWebhookEventSink webhookEventSink;
	private final DurableUniversalWebhookStore durableStore;

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void handleExBlockConnected(ExBlockConnectedEvent event) {
		boolean historicalCatchup = event.getConnectedSource() == ConnectedSource.SYNC;
		boolean eligible = historicalCatchup
				? durableStore.hasEligibleExplorerRules(event.getBlock().getHeight())
				: durableStore.hasEligibleRules(WebhookType.EXPLORER, null, null, null);
		if (!eligible) {
			return;
		}
		webhookEventSink.processNewBlockEvent(
				event.getBlock(), event.getEvents(), WebhookType.EXPLORER, historicalCatchup);
		int index = 0;
		for (Tx tx : event.getBlock().getTxs()) {
			webhookEventSink.processAddressActivityEvent(
					event.getBlock(), tx, WebhookTxStatus.CONFIRMED, index++, WebhookType.EXPLORER,
					historicalCatchup);
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void handleExBlockReorg(ExBlockReorgEvent event) {
		if (!durableStore.hasEligibleRules(WebhookType.EXPLORER, null, null, null)) {
			return;
		}
		webhookEventSink.processReorgEvent(
				event.getOldHeight(), event.getOldHash(), event.getNewHeight(), event.getNewHash(),
				WebhookType.EXPLORER);
	}
}
