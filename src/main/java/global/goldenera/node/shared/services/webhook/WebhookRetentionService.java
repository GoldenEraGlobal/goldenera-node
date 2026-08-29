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

import static global.goldenera.node.shared.config.WebhookRetentionConfig.WEBHOOK_RETENTION_SCHEDULER;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.properties.LifecycleJournalProperties;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalRepository;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.properties.WebhookRetentionProperties;
import global.goldenera.node.shared.services.webhook.WebhookRetentionStore.CleanupCounts;
import global.goldenera.node.shared.services.webhook.WebhookRetentionStore.JournalConsumers;
import global.goldenera.node.shared.services.webhook.WebhookRetentionStore.JournalCursor;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@ConditionalOnProperty(
		prefix = "ge.general",
		name = { "postgresql-enable", "webhook-enable" },
		havingValue = "true")
@ConditionalOnProperty(
		prefix = "ge.webhook.retention",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class WebhookRetentionService {

	private final WebhookRetentionStore store;
	private final LifecycleJournalRepository journal;
	private final WebhookRetentionProperties properties;
	private final MeterRegistry registry;
	private final Clock clock;
	private final AtomicBoolean running = new AtomicBoolean();

	@Autowired
	public WebhookRetentionService(
			WebhookRetentionStore store,
			LifecycleJournalRepository journal,
			WebhookRetentionProperties properties,
			LifecycleJournalProperties journalProperties,
			MeterRegistry registry) {
		this(store, journal, properties, registry, Clock.systemUTC());
		if (properties.getJournalMaxRetainedEntries() >= journalProperties.getHardMaxRetainedEntries()) {
			throw new IllegalArgumentException(
					"Webhook journal soft retention limit must be below the core hard limit");
		}
	}

	WebhookRetentionService(
			WebhookRetentionStore store,
			LifecycleJournalRepository journal,
			WebhookRetentionProperties properties,
			MeterRegistry registry,
			Clock clock) {
		this.store = store;
		this.journal = journal;
		this.properties = properties;
		this.registry = registry;
		this.clock = clock;
	}

	@Scheduled(
			initialDelayString = "${ge.webhook.retention.initial-delay:PT5M}",
			fixedDelayString = "${ge.webhook.retention.run-interval:PT1H}",
			scheduler = WEBHOOK_RETENTION_SCHEDULER)
	public void runRetention() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			cleanupPostgres();
			pruneJournal(LifecycleJournalStream.CANONICAL);
			pruneJournal(LifecycleJournalStream.MEMPOOL);
		} catch (RuntimeException failure) {
			registry.counter("webhook.retention.failures").increment();
			log.error("Webhook retention iteration failed", failure);
		} finally {
			running.set(false);
		}
	}

	private void cleanupPostgres() {
		Instant cutoff = clock.instant().minus(properties.getAuditWindow());
		for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
			CleanupCounts deleted = store.cleanupBatch(cutoff, properties.getBatchSize());
			recordDeleted("universal_delivery", deleted.universalDeliveries());
			recordDeleted("bridge_delivery", deleted.bridgeDeliveries());
			recordDeleted("universal_source_event", deleted.sourceEvents());
			recordDeleted("bridge_reorg_correlation", deleted.completeReorgCorrelations());
			if (deleted.total() < properties.getBatchSize()) {
				return;
			}
		}
		registry.counter("webhook.retention.batch_limit").increment();
		log.warn("Webhook retention reached its per-run batch limit");
	}

	private void pruneJournal(LifecycleJournalStream stream) {
		LifecycleJournalHead head = journal.head(stream);
		long retained = retainedEntries(head);
		registry.summary("lifecycle.journal.retained.entries", "stream", stream.name()).record(retained);
		if (retained <= properties.getJournalMaxRetainedEntries()) {
			return;
		}

		JournalConsumers consumers = store.journalConsumers(stream);
		Optional<Long> minimumCursor = minimumRequiredCursor(consumers, head);
		if (minimumCursor.isEmpty()) {
			blocked(stream, "cursor_unavailable", retained, head);
			return;
		}

		long safeThrough = minimumCursor.get() - properties.getJournalSafetyEntries();
		long requiredThrough = head.sequence() - properties.getJournalMaxRetainedEntries();
		long target = Math.min(safeThrough, requiredThrough);
		long alreadyPrunedThrough = head.floorSequence() - 1L;
		if (target > alreadyPrunedThrough) {
			journal.pruneThrough(stream, target);
			registry.counter("lifecycle.journal.pruned", "stream", stream.name())
					.increment(target - alreadyPrunedThrough);
		}
		if (safeThrough < requiredThrough) {
			blocked(stream, "consumer_lag", retained, head);
		}
	}

	private Optional<Long> minimumRequiredCursor(JournalConsumers consumers, LifecycleJournalHead head) {
		List<Long> required = new ArrayList<>(2);
		if (consumers.universalRequired()) {
			if (!addMatchingCursor(required, consumers.universal(), head)) {
				return Optional.empty();
			}
		}
		if (consumers.bridgeRequired()) {
			if (!addMatchingCursor(required, consumers.bridge(), head)) {
				return Optional.empty();
			}
		}
		return Optional.of(required.stream().mapToLong(Long::longValue).min().orElse(head.sequence()));
	}

	private boolean addMatchingCursor(
			List<Long> required, Optional<JournalCursor> cursor, LifecycleJournalHead head) {
		if (cursor.isEmpty() || cursor.get().epoch() == null || !head.epoch().equals(cursor.get().epoch())) {
			return false;
		}
		required.add(Math.min(cursor.get().sequence(), head.sequence()));
		return true;
	}

	private long retainedEntries(LifecycleJournalHead head) {
		return Math.max(0L, head.sequence() - head.floorSequence() + 1L);
	}

	private void blocked(
			LifecycleJournalStream stream, String reason, long retained, LifecycleJournalHead head) {
		registry.counter("lifecycle.journal.retention.blocked", "stream", stream.name(), "reason", reason)
				.increment();
		log.warn("Lifecycle journal retention blocked for {}: reason={}, retained={}, head={}, floor={}",
				stream, reason, retained, head.sequence(), head.floorSequence());
	}

	private void recordDeleted(String table, int count) {
		if (count > 0) {
			registry.counter("webhook.retention.deleted", "table", table).increment(count);
		}
	}
}
