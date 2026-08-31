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
package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.config.CoreAsyncConfig.MEMPOOL_EVENT_EXECUTOR;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalCursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolCanonicalProjectionAdvance;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolCanonicalProjectionCursor;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public final class MempoolCanonicalJournalProjector {
	private static final int EMPTY_MEMPOOL_FAST_FORWARD_LIMIT = 4_096;

	private final LifecycleJournalQuery journal;
	private final PersistentMempoolStore persistentMempool;
	private final ChainQuery chainQuery;
	private final MempoolStore mempoolStore;
	private final MempoolManager mempoolManager;
	private final MempoolRecoveryGate recoveryGate;
	private final Executor executor;
	private final AtomicBoolean submitted = new AtomicBoolean();
	private final AtomicBoolean dirty = new AtomicBoolean();

	public MempoolCanonicalJournalProjector(
			LifecycleJournalQuery journal,
			PersistentMempoolStore persistentMempool,
			ChainQuery chainQuery,
			MempoolStore mempoolStore,
			MempoolManager mempoolManager,
			MempoolRecoveryGate recoveryGate,
			@Qualifier(MEMPOOL_EVENT_EXECUTOR) Executor executor) {
		this.journal = journal;
		this.persistentMempool = persistentMempool;
		this.chainQuery = chainQuery;
		this.mempoolStore = mempoolStore;
		this.mempoolManager = mempoolManager;
		this.recoveryGate = recoveryGate;
		this.executor = executor;
	}

	public void wake() {
		dirty.set(true);
		if (!recoveryGate.isRecovered() || !submitted.compareAndSet(false, true)) {
			return;
		}
		try {
			executor.execute(() -> {
				try {
					do {
						dirty.set(false);
						drainToHead();
					} while (dirty.get());
				} catch (RuntimeException failure) {
					log.error("Canonical-to-mempool journal projection failed", failure);
				} finally {
					submitted.set(false);
					if (dirty.get() && recoveryGate.isRecovered()) {
						wake();
					}
				}
			});
		} catch (RuntimeException failure) {
			submitted.set(false);
			throw failure;
		}
	}

	@Scheduled(fixedDelayString = "PT1S")
	void poll() {
		wake();
	}

	public void drainToHead() {
		while (projectNext()) {
			// Drain synchronously so startup cannot expose a chain/mempool cursor gap.
		}
	}

	private boolean projectNext() {
		LifecycleJournalHead head = journal.head(LifecycleJournalStream.CANONICAL);
		MempoolCanonicalProjectionCursor cursor = persistentMempool.canonicalProjectionCursor()
				.orElseThrow(() -> new GEFailedException("Mempool canonical projection cursor is unavailable"));
		if (!cursor.epoch().equals(head.epoch())) {
			throw new GEFailedException("Mempool canonical projection epoch differs from canonical journal");
		}
		if (cursor.sequence() > head.sequence()) {
			throw new GEFailedException("Mempool canonical projection cursor is ahead of canonical journal");
		}
		List<LifecycleJournalEntry> entries = journal.readAfter(
				LifecycleJournalStream.CANONICAL,
				new LifecycleJournalCursor(cursor.epoch(), cursor.sequence()),
				EMPTY_MEMPOOL_FAST_FORWARD_LIMIT);
		if (entries.isEmpty()) {
			return false;
		}
		LifecycleJournalEntry entry = entries.getFirst();
		LifecycleJournalEntry last = entry;
		if (mempoolStore.getCount() == 0L && entry.operation() == LifecycleJournalOperation.CONNECT) {
			for (LifecycleJournalEntry candidate : entries) {
				if (candidate.operation() != LifecycleJournalOperation.CONNECT) {
					break;
				}
				last = candidate;
			}
		}
		MempoolCanonicalProjectionAdvance advance = new MempoolCanonicalProjectionAdvance(
				entry.epoch(), cursor.sequence(), last.sequence());
		if (last == entry) {
			mempoolStore.executeCanonicalPersistenceBatch(advance, () -> apply(entry));
		} else {
			mempoolStore.executeCanonicalPersistenceBatch(advance, () -> {
				// No transaction can be mined or promoted while the authoritative mempool is empty.
			});
		}
		return true;
	}

	private void apply(LifecycleJournalEntry entry) {
		if (entry.operation() == LifecycleJournalOperation.REORG_COMMIT) {
			mempoolManager.applyCanonicalReorgCommit();
			return;
		}
		StoredBlock stored = chainQuery.getStoredBlockByHash(entry.primaryHash())
				.orElseThrow(() -> new GEFailedException(
						"Canonical journal block data is unavailable for mempool projection: " + entry.primaryHash()));
		switch (entry.operation()) {
			case CONNECT -> {
				if (stored.getHeight() > 0L) {
					mempoolManager.applyCanonicalConnect(stored.getBlock());
				}
			}
			case DISCONNECT -> {
				if (stored.getHeight() > 0L) {
					mempoolManager.applyCanonicalDisconnect(stored.getBlock());
				}
			}
			default -> throw new GEFailedException(
					"Unsupported canonical operation for mempool projection " + entry.operation());
		}
	}
}
