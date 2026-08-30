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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalCursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalFloorException;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolCanonicalProjectionCursor;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.JournalCursor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CoreLifecycleJournalWebhookConsumerTest {
	@Test
	void startsPollingOnlyAfterApplicationReadyAndOnlyOnce() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		CoreLifecycleJournalWebhookConsumer consumer = new CoreLifecycleJournalWebhookConsumer(
				mock(LifecycleJournalQuery.class),
				mock(DurableUniversalWebhookStore.class),
				mock(CoreLifecycleJournalWebhookProjector.class),
				scheduler,
				Runnable::run,
				new SimpleMeterRegistry());
		verifyNoInteractions(scheduler);

		consumer.start();
		consumer.start();

		verify(scheduler).scheduleWithFixedDelay(
				any(Runnable.class), eq(CoreLifecycleJournalWebhookConsumer.POLL_INTERVAL));
	}

	@Test
	void epochMismatchReanchorsAtHeadWithoutReplayingForeignLineage() {
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		UUID oldEpoch = UUID.randomUUID();
		UUID newEpoch = UUID.randomUUID();
		for (LifecycleJournalStream stream : LifecycleJournalStream.values()) {
			when(journal.head(stream)).thenReturn(
					new LifecycleJournalHead(stream, newEpoch, 50L, 1L, 100L, Hash.ZERO));
			when(store.journalCursor(WebhookType.BLOCKCHAIN, stream.code()))
					.thenReturn(new JournalCursor(oldEpoch, 20L));
		}
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		CoreLifecycleJournalWebhookConsumer consumer = new CoreLifecycleJournalWebhookConsumer(
				journal, store, mock(CoreLifecycleJournalWebhookProjector.class), mock(TaskScheduler.class),
				Runnable::run, registry);

		consumer.drain();

		for (LifecycleJournalStream stream : LifecycleJournalStream.values()) {
			verify(store).reanchorJournalLineage(
					eq(WebhookType.BLOCKCHAIN), eq(stream.code()), eq(newEpoch), eq(50L), any());
			verify(journal, never()).readAfter(
					eq(stream), any(), anyInt());
		}
	}

	@Test
	void initialNullEpochStartsAtRetainedFloorAndProcessesFirstEntry() {
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		CoreLifecycleJournalWebhookProjector projector = mock(CoreLifecycleJournalWebhookProjector.class);
		UUID epoch = UUID.randomUUID();
		LifecycleJournalHead canonicalHead =
				new LifecycleJournalHead(LifecycleJournalStream.CANONICAL, epoch, 1L, 1L, 0L, Hash.ZERO);
		LifecycleJournalHead mempoolHead =
				new LifecycleJournalHead(LifecycleJournalStream.MEMPOOL, epoch, 0L, 1L, 0L, Hash.ZERO);
		LifecycleJournalEntry entry = new LifecycleJournalEntry(
				1, epoch, 1L, UUID.randomUUID(), LifecycleJournalStream.CANONICAL,
				LifecycleJournalOperation.CONNECT, UUID.randomUUID(), 0, 1, 1L,
				Hash.ZERO, Hash.ZERO, Instant.now(), 3, -1, new byte[0]);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(canonicalHead);
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(mempoolHead);
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.CANONICAL.code()))
				.thenReturn(new JournalCursor(null, 0L));
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.MEMPOOL.code()))
				.thenReturn(new JournalCursor(epoch, 0L));
		when(journal.readAfter(
				LifecycleJournalStream.CANONICAL, new LifecycleJournalCursor(epoch, 0L), 256))
				.thenReturn(List.of(entry));
		when(journal.readAfter(
				LifecycleJournalStream.MEMPOOL, new LifecycleJournalCursor(epoch, 0L), 256))
				.thenReturn(List.of());
		CoreLifecycleJournalWebhookConsumer consumer = new CoreLifecycleJournalWebhookConsumer(
				journal, store, projector, mock(TaskScheduler.class), Runnable::run, new SimpleMeterRegistry());

		consumer.drain();

		verify(store).reanchorJournalLineage(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.CANONICAL.code()),
				eq(epoch), eq(0L), any());
		verify(projector).project(entry);
	}

	@Test
	void cursorBelowFloorRecoversAndContinuesBothStreams() {
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		UUID epoch = UUID.randomUUID();
		LifecycleJournalHead canonical = new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 20L, 10L, 20L, Hash.ZERO);
		LifecycleJournalHead mempool = new LifecycleJournalHead(
				LifecycleJournalStream.MEMPOOL, epoch, 0L, 1L, 20L, Hash.ZERO);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(canonical);
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(mempool);
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.CANONICAL.code()))
				.thenReturn(new JournalCursor(epoch, 3L), new JournalCursor(epoch, 9L));
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.MEMPOOL.code()))
				.thenReturn(new JournalCursor(epoch, 0L));
		when(journal.readAfter(
				LifecycleJournalStream.CANONICAL, new LifecycleJournalCursor(epoch, 3L), 256))
				.thenThrow(new LifecycleJournalFloorException(LifecycleJournalStream.CANONICAL, 3L, 10L));
		when(journal.readAfter(
				LifecycleJournalStream.CANONICAL, new LifecycleJournalCursor(epoch, 9L), 256))
				.thenReturn(List.of());
		when(journal.readAfter(
				LifecycleJournalStream.MEMPOOL, new LifecycleJournalCursor(epoch, 0L), 256))
				.thenReturn(List.of());
		when(store.recoverJournalFloor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.CANONICAL.code()),
				eq(epoch), eq(3L), eq(9L), any())).thenReturn(true);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		CoreLifecycleJournalWebhookConsumer consumer = new CoreLifecycleJournalWebhookConsumer(
				journal, store, mock(CoreLifecycleJournalWebhookProjector.class), mock(TaskScheduler.class),
				Runnable::run, registry);

		consumer.drain();

		verify(store).recoverJournalFloor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.CANONICAL.code()),
				eq(epoch), eq(3L), eq(9L), any());
		verify(journal).readAfter(
				LifecycleJournalStream.MEMPOOL, new LifecycleJournalCursor(epoch, 0L), 256);
		assertThat(registry.counter("universal.webhook.journal.gap", "stream", "CANONICAL").count())
				.isEqualTo(6.0d);
	}

	@Test
	void mempoolProjectionWaitsForCrashDurableCanonicalToMempoolCursor() {
		UUID epoch = UUID.randomUUID();
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		LifecycleJournalHead canonical = new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 5L, 1L, 5L, Hash.ZERO);
		LifecycleJournalHead mempool = new LifecycleJournalHead(
				LifecycleJournalStream.MEMPOOL, epoch, 1L, 1L, 5L, Hash.ZERO);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(canonical);
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(mempool);
		when(journal.readAfter(
				LifecycleJournalStream.CANONICAL, new LifecycleJournalCursor(epoch, 5L), 256))
				.thenReturn(List.of());
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.CANONICAL.code()))
				.thenReturn(new JournalCursor(epoch, 5L));
		PersistentMempoolStore persistentMempool = mock(PersistentMempoolStore.class);
		when(persistentMempool.canonicalProjectionCursor()).thenReturn(Optional.of(
				new MempoolCanonicalProjectionCursor(1, epoch, 4L)));
		CoreLifecycleJournalWebhookConsumer consumer = new CoreLifecycleJournalWebhookConsumer(
				journal, store, mock(CoreLifecycleJournalWebhookProjector.class), mock(TaskScheduler.class),
				Runnable::run, new SimpleMeterRegistry(), persistentMempool);

		consumer.drain();

		verify(journal, never()).readAfter(
				eq(LifecycleJournalStream.MEMPOOL), any(LifecycleJournalCursor.class), anyInt());
	}
}
