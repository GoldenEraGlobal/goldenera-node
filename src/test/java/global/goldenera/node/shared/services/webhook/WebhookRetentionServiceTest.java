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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalRepository;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.properties.WebhookRetentionProperties;
import global.goldenera.node.shared.services.webhook.WebhookRetentionStore.CleanupCounts;
import global.goldenera.node.shared.services.webhook.WebhookRetentionStore.JournalConsumers;
import global.goldenera.node.shared.services.webhook.WebhookRetentionStore.JournalCursor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WebhookRetentionServiceTest {

	private static final UUID EPOCH = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

	private WebhookRetentionStore store;
	private LifecycleJournalRepository journal;
	private WebhookRetentionProperties properties;
	private SimpleMeterRegistry registry;

	@BeforeEach
	void setUp() {
		store = mock(WebhookRetentionStore.class);
		journal = mock(LifecycleJournalRepository.class);
		properties = new WebhookRetentionProperties();
		properties.setBatchSize(100);
		properties.setMaxBatchesPerRun(2);
		properties.setJournalMaxRetainedEntries(500L);
		properties.setJournalSafetyEntries(100L);
		registry = new SimpleMeterRegistry();
		when(store.cleanupBatch(NOW.minus(properties.getAuditWindow()), 100))
				.thenReturn(new CleanupCounts(0, 0, 0, 0));
		when(journal.head(LifecycleJournalStream.MEMPOOL))
				.thenReturn(head(LifecycleJournalStream.MEMPOOL, 10L, 1L));
	}

	@Test
	void prunesOnlyToMinimumCursorSafetyAndReportsHardCapBlocked() {
		when(journal.head(LifecycleJournalStream.CANONICAL))
				.thenReturn(head(LifecycleJournalStream.CANONICAL, 2_000L, 1L));
		when(store.journalConsumers(LifecycleJournalStream.CANONICAL)).thenReturn(new JournalConsumers(
				true, Optional.of(new JournalCursor(EPOCH, 1_300L)),
				true, Optional.of(new JournalCursor(EPOCH, 1_200L))));

		service().runRetention();

		verify(journal).pruneThrough(LifecycleJournalStream.CANONICAL, 1_100L);
		assertThat(registry.counter(
				"lifecycle.journal.retention.blocked", "stream", "CANONICAL", "reason", "consumer_lag")
				.count()).isEqualTo(1d);
	}

	@Test
	void noSubscribersDoNotLeaveUnusedConsumersBlockingHardCap() {
		when(journal.head(LifecycleJournalStream.CANONICAL))
				.thenReturn(head(LifecycleJournalStream.CANONICAL, 2_000L, 1L));
		when(store.journalConsumers(LifecycleJournalStream.CANONICAL)).thenReturn(new JournalConsumers(
				false, Optional.empty(), false, Optional.empty()));

		service().runRetention();

		verify(journal).pruneThrough(LifecycleJournalStream.CANONICAL, 1_500L);
	}

	@Test
	void epochMismatchFailsClosedWithoutPruning() {
		when(journal.head(LifecycleJournalStream.CANONICAL))
				.thenReturn(head(LifecycleJournalStream.CANONICAL, 2_000L, 1L));
		when(store.journalConsumers(LifecycleJournalStream.CANONICAL)).thenReturn(new JournalConsumers(
				true, Optional.of(new JournalCursor(UUID.randomUUID(), 2_000L)),
				false, Optional.empty()));

		service().runRetention();

		verify(journal, never()).pruneThrough(LifecycleJournalStream.CANONICAL, 1_500L);
		assertThat(registry.counter(
				"lifecycle.journal.retention.blocked", "stream", "CANONICAL", "reason", "cursor_unavailable")
				.count()).isEqualTo(1d);
	}

	@Test
	void floorAndHardCapAvoidUnnecessaryPrune() {
		when(journal.head(LifecycleJournalStream.CANONICAL))
				.thenReturn(head(LifecycleJournalStream.CANONICAL, 2_000L, 1_501L));

		service().runRetention();

		verify(store, never()).journalConsumers(LifecycleJournalStream.CANONICAL);
		verify(journal, never()).pruneThrough(LifecycleJournalStream.CANONICAL, 1_500L);
	}

	private WebhookRetentionService service() {
		return new WebhookRetentionService(
				store, journal, properties, registry, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private LifecycleJournalHead head(LifecycleJournalStream stream, long sequence, long floor) {
		return new LifecycleJournalHead(stream, EPOCH, sequence, floor, 0L, Hash.ZERO);
	}
}
