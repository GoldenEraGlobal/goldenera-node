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

import java.time.Instant;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolAdmissionReason;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.shared.enums.WebhookTxStatus;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.JournalCursor;

@Service
@ConditionalOnProperty(name = { "ge.general.postgresql-enable", "ge.general.webhook-enable" }, havingValue = "true")
public class CoreLifecycleJournalWebhookProjector {
	private final DurableUniversalWebhookStore store;
	private final WebhookDispatchService sink;
	private final ChainQuery chainQuery;
	private final PersistentMempoolStore persistentMempool;

	@Autowired
	public CoreLifecycleJournalWebhookProjector(
			DurableUniversalWebhookStore store,
			WebhookDispatchService sink,
			ChainQuery chainQuery,
			PersistentMempoolStore persistentMempool) {
		this.store = store;
		this.sink = sink;
		this.chainQuery = chainQuery;
		this.persistentMempool = persistentMempool;
	}

	CoreLifecycleJournalWebhookProjector(
			DurableUniversalWebhookStore store,
			WebhookDispatchService sink,
			ChainQuery chainQuery) {
		this(store, sink, chainQuery, null);
	}

	@Transactional(rollbackFor = Exception.class)
	public void project(LifecycleJournalEntry entry) {
		JournalCursor cursor = store.journalCursor(WebhookType.BLOCKCHAIN, entry.stream().code());
		if (!entry.epoch().equals(cursor.epoch())) {
			throw new IllegalStateException("Universal webhook projector received a mismatched journal epoch");
		}
		if (entry.sequence() <= cursor.sequence()) {
			return;
		}
		if (entry.sequence() != cursor.sequence() + 1L) {
			throw new IllegalStateException(
					"Universal webhook journal cursor gap: " + cursor.sequence() + " -> " + entry.sequence());
		}
		if (!store.hasEligibleRules(
				WebhookType.BLOCKCHAIN, entry.epoch(), entry.stream().code(), entry.sequence())) {
			advance(entry, cursor);
			return;
		}

		if (entry.stream().code() == 0) {
			projectCanonical(entry);
		} else {
			projectMempool(entry);
		}
		advance(entry, cursor);
	}

	private void advance(LifecycleJournalEntry entry, JournalCursor cursor) {
		if (!store.advanceJournalCursor(
				WebhookType.BLOCKCHAIN, entry.stream().code(), entry.epoch(),
				cursor.sequence(), entry.sequence(), Instant.now())) {
			throw new IllegalStateException("Universal webhook journal cursor was concurrently modified");
		}
	}

	private void projectCanonical(LifecycleJournalEntry entry) {
		switch (entry.operation()) {
			case CONNECT -> {
				StoredBlock stored = chainQuery.getStoredBlockByHashOrThrow(entry.primaryHash());
				sink.processNewBlockEvent(
						stored.getBlock(), stored.getEvents(), WebhookType.BLOCKCHAIN,
						entry.eventKey(), entry.epoch(), entry.stream().code(), entry.sequence(), null);
				int index = 0;
				for (Tx tx : stored.getBlock().getTxs()) {
					sink.processAddressActivityEvent(
							stored.getBlock(), tx, WebhookTxStatus.CONFIRMED, index++, WebhookType.BLOCKCHAIN,
							entry.eventKey(), entry.epoch(), entry.stream().code(), entry.sequence(), null);
				}
			}
			case REORG_COMMIT -> {
				Long oldHeight = entry.relatedHash() == null
						? null
						: chainQuery.getStoredBlockByHash(entry.relatedHash())
								.map(StoredBlock::getHeight)
								.orElse(null);
				sink.processReorgEvent(
						oldHeight, entry.relatedHash(), entry.height(), entry.primaryHash(), WebhookType.BLOCKCHAIN,
						entry.eventKey(), entry.epoch(), entry.stream().code(), entry.sequence());
			}
			case DISCONNECT -> {
				// REORG_COMMIT is the public universal reorg notification.
			}
			default -> throw new IllegalStateException("Unexpected canonical journal operation " + entry.operation());
		}
	}

	private void projectMempool(LifecycleJournalEntry entry) {
		if (suppressedAdmission(entry)) {
			return;
		}
		Tx tx = TxDecoder.INSTANCE.decode(Bytes.wrap(entry.payload()));
		WebhookTxStatus status = switch (entry.operation()) {
			case PENDING -> WebhookTxStatus.PENDING;
			case REORG_READD -> WebhookTxStatus.REVERTED;
			case REPLACED -> WebhookTxStatus.REPLACED;
			case DROPPED -> WebhookTxStatus.DROPPED;
			default -> throw new IllegalStateException("Unexpected mempool journal operation " + entry.operation());
		};
		sink.processAddressActivityEvent(
				null, tx, status, null, WebhookType.BLOCKCHAIN,
				entry.eventKey(), entry.epoch(), entry.stream().code(), entry.sequence(), null);
	}

	private boolean suppressedAdmission(LifecycleJournalEntry entry) {
		if (entry.operation() != LifecycleJournalOperation.PENDING
				&& entry.operation() != LifecycleJournalOperation.REORG_READD) {
			return false;
		}
		if (chainQuery.getTransactionBlockHeight(entry.primaryHash()).isPresent()) {
			return true;
		}
		if (persistentMempool == null) {
			return false;
		}
		return persistentMempool.findActive(entry.primaryHash())
				.map(record -> entry.operation() == LifecycleJournalOperation.PENDING
						? record.admissionReason() == MempoolAdmissionReason.REORG
						: record.admissionReason() != MempoolAdmissionReason.REORG)
				.orElse(false);
	}
}
