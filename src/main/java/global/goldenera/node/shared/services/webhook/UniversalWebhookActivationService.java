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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.shared.entities.Webhook;
import global.goldenera.node.shared.enums.WebhookType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = { "ge.general.postgresql-enable", "ge.general.webhook-enable" }, havingValue = "true")
public class UniversalWebhookActivationService {
	private final DurableUniversalWebhookStore store;
	private final LifecycleJournalQuery journal;
	private final ChainQuery chainQuery;

	/** Re-enable policy: skip the disabled interval and resume from current committed heads. */
	@Transactional
	public void resetAfterReEnable(Webhook webhook) {
		if (webhook.getType() == WebhookType.BRIDGE) {
			return;
		}
		long sourceEventId = store.sourceCursor(webhook.getType());
		if (webhook.getType() == WebhookType.BLOCKCHAIN) {
			var canonical = journal.head(LifecycleJournalStream.CANONICAL);
			var mempool = journal.head(LifecycleJournalStream.MEMPOOL);
			store.resetRuleActivation(
					webhook.getId(), sourceEventId,
					canonical.epoch(), canonical.sequence(), mempool.epoch(), mempool.sequence(), -1L);
		} else {
			long height = chainQuery.getLatestBlockHeight().orElse(-1L);
			store.resetRuleActivation(webhook.getId(), sourceEventId, null, 0L, null, 0L, height);
		}
	}
}
