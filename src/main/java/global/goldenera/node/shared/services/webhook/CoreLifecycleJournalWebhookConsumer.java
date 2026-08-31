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

import static global.goldenera.node.shared.config.WebhookAsyncConfig.CORE_WEBHOOK_SCHEDULER;
import static global.goldenera.node.shared.config.WebhookAsyncConfig.UNIVERSAL_WEBHOOK_JOURNAL_EXECUTOR;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalBootstrap;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalCursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalFloorException;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.JournalCursor;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@DependsOn(LifecycleJournalBootstrap.BEAN_NAME)
@ConditionalOnProperty(name = { "ge.general.postgresql-enable", "ge.general.webhook-enable" }, havingValue = "true")
public class CoreLifecycleJournalWebhookConsumer {
	static final int READ_LIMIT = 256;
	static final int MAX_BATCHES_PER_DRAIN = 8;
	static final Duration MINIMUM_POLL_INTERVAL = Duration.ofMillis(250);
	// Bounds detection latency for journal writes that do not produce a local wake signal.
	static final Duration RECOVERY_POLL_INTERVAL = Duration.ofSeconds(30);

	private final LifecycleJournalQuery journal;
	private final DurableUniversalWebhookStore store;
	private final CoreLifecycleJournalWebhookProjector projector;
	private final MeterRegistry registry;
	private final PersistentMempoolStore persistentMempool;
	private final AtomicBoolean running = new AtomicBoolean();
	private final AdaptivePollingLoop pollingLoop;

	@Autowired
	public CoreLifecycleJournalWebhookConsumer(
			LifecycleJournalQuery journal,
			DurableUniversalWebhookStore store,
			CoreLifecycleJournalWebhookProjector projector,
			@Qualifier(CORE_WEBHOOK_SCHEDULER) TaskScheduler scheduler,
			@Qualifier(UNIVERSAL_WEBHOOK_JOURNAL_EXECUTOR) Executor executor,
			MeterRegistry registry,
			PersistentMempoolStore persistentMempool) {
		this.journal = journal;
		this.store = store;
		this.projector = projector;
		this.registry = registry;
		this.persistentMempool = persistentMempool;
		this.pollingLoop = new AdaptivePollingLoop(
				scheduler, executor, this::drain,
				MINIMUM_POLL_INTERVAL, RECOVERY_POLL_INTERVAL);
	}

	CoreLifecycleJournalWebhookConsumer(
			LifecycleJournalQuery journal,
			DurableUniversalWebhookStore store,
			CoreLifecycleJournalWebhookProjector projector,
			TaskScheduler scheduler,
			Executor executor,
			MeterRegistry registry) {
		this(journal, store, projector, scheduler, executor, registry, null);
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		pollingLoop.start();
	}

	@PreDestroy
	void stop() {
		pollingLoop.stop();
	}

	public void wake() {
		pollingLoop.wake();
	}

	boolean drain() {
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		boolean moreAvailable = false;
		try {
			moreAvailable = drain(LifecycleJournalStream.CANONICAL);
			moreAvailable |= drain(LifecycleJournalStream.MEMPOOL);
		} catch (RuntimeException exception) {
			log.error("Universal webhook lifecycle journal projection failed", exception);
			throw exception;
		} finally {
			running.set(false);
		}
		return moreAvailable;
	}

	private boolean drain(LifecycleJournalStream stream) {
		if (stream == LifecycleJournalStream.MEMPOOL && !canonicalMempoolProjectionCaughtUp()) {
			return false;
		}
		for (int batch = 0; batch < MAX_BATCHES_PER_DRAIN; batch++) {
			LifecycleJournalHead head = journal.head(stream);
			JournalCursor cursor = store.journalCursor(WebhookType.BLOCKCHAIN, stream.code());
			if (cursor.epoch() == null) {
				long initialSequence = head.floorSequence() - 1L;
				store.reanchorJournalLineage(
						WebhookType.BLOCKCHAIN, stream.code(), head.epoch(), initialSequence, Instant.now());
				cursor = new JournalCursor(head.epoch(), initialSequence);
			} else if (!head.epoch().equals(cursor.epoch())) {
				store.reanchorJournalLineage(
						WebhookType.BLOCKCHAIN, stream.code(), head.epoch(), head.sequence(), Instant.now());
				return false;
			}
			List<LifecycleJournalEntry> entries;
			try {
				entries = journal.readAfter(
						stream, new LifecycleJournalCursor(cursor.epoch(), cursor.sequence()), READ_LIMIT);
			} catch (LifecycleJournalFloorException gap) {
				recoverFloor(stream, cursor, journal.head(stream));
				continue;
			}
			for (LifecycleJournalEntry entry : entries) {
				projector.project(entry);
			}
			if (entries.size() < READ_LIMIT) {
				return false;
			}
		}
		return true;
	}

	private boolean canonicalMempoolProjectionCaughtUp() {
		if (persistentMempool == null) {
			return true;
		}
		LifecycleJournalHead canonicalHead = journal.head(LifecycleJournalStream.CANONICAL);
		return persistentMempool.canonicalProjectionCursor()
				.filter(cursor -> cursor.epoch().equals(canonicalHead.epoch()))
				.filter(cursor -> cursor.sequence() >= canonicalHead.sequence())
				.isPresent();
	}

	private void recoverFloor(
			LifecycleJournalStream stream,
			JournalCursor cursor,
			LifecycleJournalHead head) {
		if (!head.epoch().equals(cursor.epoch())) {
			store.reanchorJournalLineage(
					WebhookType.BLOCKCHAIN, stream.code(), head.epoch(), head.sequence(), Instant.now());
			return;
		}
		long recoveredSequence = head.floorSequence() - 1L;
		long lostEntries = Math.max(0L, recoveredSequence - cursor.sequence());
		if (!store.recoverJournalFloor(
				WebhookType.BLOCKCHAIN,
				stream.code(),
				head.epoch(),
				cursor.sequence(),
				recoveredSequence,
				Instant.now())) {
			return;
		}
		registry.counter("universal.webhook.journal.gap", "stream", stream.name()).increment(lostEntries);
		log.error("Universal webhook {} journal lost {} entries to hard retention; recovered cursor to {}",
				stream, lostEntries, recoveredSequence);
	}
}
