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
package global.goldenera.node.bridge.webhook;

import static global.goldenera.node.bridge.config.BridgeAsyncConfig.BRIDGE_JOURNAL_EXECUTOR;
import static global.goldenera.node.shared.config.WebhookAsyncConfig.CORE_WEBHOOK_SCHEDULER;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalBootstrap;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalCursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.bridge.webhook.BridgeLifecycleProjectionCursorStore.Cursor;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@DependsOn(LifecycleJournalBootstrap.BEAN_NAME)
@ConditionalOnProperty(
		prefix = "ge.general",
		name = { "postgresql-enable", "webhook-enable" },
		havingValue = "true",
		matchIfMissing = true)
public class BridgeLifecycleJournalProjector implements BridgeCoreJournalConsumer {

	static final int READ_LIMIT = 256;
	static final int MAX_PROJECTED_ENTRIES_PER_STREAM_RUN = 256;
	static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

	private final LifecycleJournalQuery journalQuery;
	private final BridgeLifecycleProjectionCursorStore cursorStore;
	private final BridgeLifecycleProjectionService projectionService;
	private final TaskScheduler scheduler;
	private final Executor journalExecutor;
	private final MeterRegistry registry;
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean dirty = new AtomicBoolean();
	private final AtomicBoolean projectionSubmitted = new AtomicBoolean();

	public BridgeLifecycleJournalProjector(
			LifecycleJournalQuery journalQuery,
			BridgeLifecycleProjectionCursorStore cursorStore,
			BridgeLifecycleProjectionService projectionService,
			@Qualifier(CORE_WEBHOOK_SCHEDULER) TaskScheduler scheduler,
			@Qualifier(BRIDGE_JOURNAL_EXECUTOR) Executor journalExecutor,
			MeterRegistry registry) {
		this.journalQuery = journalQuery;
		this.cursorStore = cursorStore;
		this.projectionService = projectionService;
		this.scheduler = scheduler;
		this.journalExecutor = journalExecutor;
		this.registry = registry;
	}

	@PostConstruct
	void schedule() {
		scheduler.scheduleWithFixedDelay(this::wake, POLL_INTERVAL);
	}

	@Override
	public void wake() {
		dirty.set(true);
		submitProjection();
	}

	void projectAvailable() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		dirty.set(false);
		boolean moreAvailable = false;
		try {
			moreAvailable = projectCanonical();
			moreAvailable |= projectMempool();
		} catch (RuntimeException failure) {
			log.error("Bridge lifecycle journal projection failed", failure);
		} finally {
			running.set(false);
		}
		if (moreAvailable) {
			dirty.set(true);
		}
	}

	private void submitProjection() {
		if (!projectionSubmitted.compareAndSet(false, true)) {
			return;
		}
		try {
			journalExecutor.execute(() -> {
				try {
					projectAvailable();
				} finally {
					projectionSubmitted.set(false);
					if (dirty.get()) {
						submitProjection();
					}
				}
			});
		} catch (RuntimeException failure) {
			projectionSubmitted.set(false);
			dirty.set(true);
			log.error("Cannot submit Bridge lifecycle projection", failure);
		}
	}

	private boolean projectCanonical() {
		int projectedEntries = 0;
		while (projectedEntries < MAX_PROJECTED_ENTRIES_PER_STREAM_RUN) {
			Cursor cursor = cursorStore.current(LifecycleJournalStream.CANONICAL);
			LifecycleJournalHead head = journalQuery.head(LifecycleJournalStream.CANONICAL);
			if (cursor == null) {
				cursor = cursorStore.initialize(head);
			}
			if (reanchorIfNeeded(cursor, head)) {
				return false;
			}
			cursor = recoverFloorIfNeeded(cursor, head);
			List<LifecycleJournalEntry> entries = journalQuery.readAfter(
					LifecycleJournalStream.CANONICAL,
					new LifecycleJournalCursor(cursor.epoch(), cursor.sequence()), READ_LIMIT);
			List<LifecycleJournalEntry> group = loadGroup(entries);
			if (group.isEmpty()) {
				return false;
			}
			projectionService.applyCanonicalGroup(group);
			projectedEntries += group.size();
		}
		return true;
	}

	private boolean projectMempool() {
		int projectedEntries = 0;
		while (projectedEntries < MAX_PROJECTED_ENTRIES_PER_STREAM_RUN) {
			Cursor cursor = cursorStore.current(LifecycleJournalStream.MEMPOOL);
			LifecycleJournalHead head = journalQuery.head(LifecycleJournalStream.MEMPOOL);
			if (cursor == null) {
				cursor = cursorStore.initialize(head);
			}
			if (reanchorIfNeeded(cursor, head)) {
				return false;
			}
			cursor = recoverFloorIfNeeded(cursor, head);
			List<LifecycleJournalEntry> entries = journalQuery.readAfter(
					LifecycleJournalStream.MEMPOOL,
					new LifecycleJournalCursor(cursor.epoch(), cursor.sequence()), READ_LIMIT);
			if (entries.isEmpty()) {
				return false;
			}
			projectionService.applyMempool(entries.get(0));
			projectedEntries++;
		}
		return true;
	}

	private List<LifecycleJournalEntry> loadGroup(List<LifecycleJournalEntry> entries) {
		if (entries.isEmpty()) {
			return List.of();
		}
		LifecycleJournalEntry first = entries.get(0);
		if (first.operation() == LifecycleJournalOperation.CONNECT
				&& ConnectedSource.fromCode(first.sourceCode()) == ConnectedSource.SYNC) {
			return List.of(first);
		}
		UUID groupId = first.groupId();
		if (groupId == null) {
			return List.of(first);
		}
		List<LifecycleJournalEntry> group = new ArrayList<>(first.groupSize());
		for (int offset = 0; offset < first.groupSize(); offset++) {
			long sequence = first.sequence() + offset;
			LifecycleJournalEntry entry = offset < entries.size()
					? entries.get(offset)
					: journalQuery.find(LifecycleJournalStream.CANONICAL, sequence).orElse(null);
			if (entry == null || !entry.epoch().equals(first.epoch()) || entry.sequence() != sequence
					|| !Objects.equals(groupId, entry.groupId())) {
				return List.of();
			}
			group.add(entry);
		}
		return List.copyOf(group);
	}

	private boolean reanchorIfNeeded(Cursor cursor, LifecycleJournalHead head) {
		if (cursor != null && cursor.epoch().equals(head.epoch())) {
			return false;
		}
		cursorStore.reanchor(head);
		registry.counter("bridge.lifecycle.journal.reanchor", "stream", head.stream().name()).increment();
		log.warn("Bridge {} lifecycle cursor re-anchored to epoch {} sequence {}; prior lineage is unavailable",
				head.stream(), head.epoch(), head.sequence());
		return true;
	}

	private Cursor recoverFloorIfNeeded(Cursor cursor, LifecycleJournalHead head) {
		long recoveredSequence = head.floorSequence() - 1L;
		if (cursor.sequence() >= recoveredSequence) {
			return cursor;
		}
		long lostEntries = recoveredSequence - cursor.sequence();
		if (!cursorStore.recoverFloor(head, cursor)) {
			Cursor current = cursorStore.current(head.stream());
			return current == null ? cursor : current;
		}
		registry.counter("bridge.lifecycle.journal.gap", "stream", head.stream().name()).increment(lostEntries);
		log.error("Bridge {} journal lost {} entries to hard retention; recovered cursor to {}",
				head.stream(), lostEntries, recoveredSequence);
		return new Cursor(head.epoch(), recoveredSequence);
	}
}
