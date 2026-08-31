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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.bridge.webhook.BridgeLifecycleProjectionCursorStore.Cursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalCursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BridgeLifecycleJournalProjectorTest {

	@Test
	void epochMismatchReanchorsCursorAndSubscriptionsWithoutReplayingPriorLineage() {
		UUID oldEpoch = UUID.randomUUID();
		UUID newEpoch = UUID.randomUUID();
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		BridgeLifecycleProjectionCursorStore cursors = mock(BridgeLifecycleProjectionCursorStore.class);
		BridgeLifecycleProjectionService projection = mock(BridgeLifecycleProjectionService.class);
		LifecycleJournalHead canonicalHead = new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, newEpoch, 10L, 1L, 9L, Hash.ZERO);
		when(cursors.current(LifecycleJournalStream.CANONICAL)).thenReturn(new Cursor(oldEpoch, 999L));
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(canonicalHead);
		when(cursors.current(LifecycleJournalStream.MEMPOOL)).thenReturn(new Cursor(newEpoch, 0L));
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.MEMPOOL, newEpoch, 0L, 1L, -1L, Hash.ZERO));
		BridgeLifecycleJournalProjector projector = new BridgeLifecycleJournalProjector(
				journal, cursors, projection, mock(TaskScheduler.class), Runnable::run, new SimpleMeterRegistry());

		projector.projectAvailable();

		verify(cursors).reanchor(canonicalHead);
		verify(journal, never()).readAfter(
				eq(LifecycleJournalStream.CANONICAL),
				any(LifecycleJournalCursor.class),
				anyInt());
		verify(projection, never()).applyCanonicalGroup(any());
	}

	@Test
	void freshCursorStartsAtFloorBoundaryAndProcessesAlreadyPresentFirstEvent() {
		UUID epoch = UUID.randomUUID();
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		BridgeLifecycleProjectionCursorStore cursors = mock(BridgeLifecycleProjectionCursorStore.class);
		BridgeLifecycleProjectionService projection = mock(BridgeLifecycleProjectionService.class);
		LifecycleJournalHead canonicalHead = new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 1L, 1L, 1L, Hash.ZERO);
		LifecycleJournalEntry entry = entry(epoch, LifecycleJournalStream.CANONICAL, 1L);
		when(cursors.current(LifecycleJournalStream.CANONICAL))
				.thenReturn(null, new Cursor(epoch, 1L));
		when(cursors.initialize(canonicalHead)).thenReturn(new Cursor(epoch, 0L));
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(canonicalHead);
		when(journal.readAfter(eq(LifecycleJournalStream.CANONICAL), any(LifecycleJournalCursor.class), eq(256)))
				.thenReturn(List.of(entry), List.of());
		when(cursors.current(LifecycleJournalStream.MEMPOOL)).thenReturn(new Cursor(epoch, 0L));
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.MEMPOOL, epoch, 0L, 1L, -1L, Hash.ZERO));
		when(journal.readAfter(eq(LifecycleJournalStream.MEMPOOL), any(LifecycleJournalCursor.class), eq(256)))
				.thenReturn(List.of());
		BridgeLifecycleJournalProjector projector = new BridgeLifecycleJournalProjector(
				journal, cursors, projection, mock(TaskScheduler.class), Runnable::run, new SimpleMeterRegistry());

		projector.projectAvailable();

		verify(cursors).initialize(canonicalHead);
		verify(projection).applyCanonicalGroup(List.of(entry));
	}

	@Test
	void mempoolDrainIsBoundedAndSchedulesContinuation() {
		UUID epoch = UUID.randomUUID();
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		BridgeLifecycleProjectionCursorStore cursors = mock(BridgeLifecycleProjectionCursorStore.class);
		BridgeLifecycleProjectionService projection = mock(BridgeLifecycleProjectionService.class);
		TaskScheduler scheduler = mock(TaskScheduler.class);
		AtomicLong cursor = new AtomicLong();
		when(cursors.current(LifecycleJournalStream.CANONICAL)).thenReturn(new Cursor(epoch, 0L));
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 0L, 1L, -1L, Hash.ZERO));
		when(journal.readAfter(eq(LifecycleJournalStream.CANONICAL), any(LifecycleJournalCursor.class), eq(256)))
				.thenReturn(List.of());
		when(cursors.current(LifecycleJournalStream.MEMPOOL))
				.thenAnswer(invocation -> new Cursor(epoch, cursor.get()));
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.MEMPOOL, epoch, 300L, 1L, -1L, Hash.ZERO));
		when(journal.readAfter(eq(LifecycleJournalStream.MEMPOOL), any(LifecycleJournalCursor.class), eq(256)))
				.thenAnswer(invocation -> {
					LifecycleJournalCursor requested = invocation.getArgument(1);
					return List.of(entry(epoch, LifecycleJournalStream.MEMPOOL, requested.sequence() + 1L));
				});
		doAnswer(invocation -> {
			LifecycleJournalEntry projected = invocation.getArgument(0);
			cursor.set(projected.sequence());
			return null;
		}).when(projection).applyMempool(any());
		BridgeLifecycleJournalProjector projector = new BridgeLifecycleJournalProjector(
				journal, cursors, projection, scheduler, Runnable::run, new SimpleMeterRegistry());

		projector.projectAvailable();

		verify(projection, times(256)).applyMempool(any());
		verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
	}

	@Test
	void repeatedWakeSignalsAreCoalescedIntoOneScheduledTask() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		Executor executor = mock(Executor.class);
		BridgeLifecycleJournalProjector projector = new BridgeLifecycleJournalProjector(
				mock(LifecycleJournalQuery.class),
				mock(BridgeLifecycleProjectionCursorStore.class),
				mock(BridgeLifecycleProjectionService.class),
				scheduler,
				executor,
				new SimpleMeterRegistry());
		projector.start();

		for (int i = 0; i < 1_000; i++) {
			projector.wake();
		}

		verify(executor).execute(any(Runnable.class));
		verifyNoInteractions(scheduler);
	}

	@Test
	void cursorBelowFloorRecoversAtFirstRetainedEntryWithoutStallingMempool() {
		UUID epoch = UUID.randomUUID();
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		BridgeLifecycleProjectionCursorStore cursors = mock(BridgeLifecycleProjectionCursorStore.class);
		BridgeLifecycleProjectionService projection = mock(BridgeLifecycleProjectionService.class);
		LifecycleJournalHead canonical = new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 20L, 10L, 20L, Hash.ZERO);
		Cursor stale = new Cursor(epoch, 3L);
		when(cursors.current(LifecycleJournalStream.CANONICAL)).thenReturn(stale);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(canonical);
		when(cursors.recoverFloor(canonical, stale)).thenReturn(true);
		when(journal.readAfter(
				eq(LifecycleJournalStream.CANONICAL),
				eq(new LifecycleJournalCursor(epoch, 9L)), eq(256))).thenReturn(List.of());
		when(cursors.current(LifecycleJournalStream.MEMPOOL)).thenReturn(new Cursor(epoch, 0L));
		when(journal.head(LifecycleJournalStream.MEMPOOL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.MEMPOOL, epoch, 0L, 1L, 20L, Hash.ZERO));
		when(journal.readAfter(
				eq(LifecycleJournalStream.MEMPOOL), any(LifecycleJournalCursor.class), eq(256)))
				.thenReturn(List.of());
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		BridgeLifecycleJournalProjector projector = new BridgeLifecycleJournalProjector(
				journal, cursors, projection, mock(TaskScheduler.class), Runnable::run, registry);

		projector.projectAvailable();

		verify(cursors).recoverFloor(canonical, stale);
		verify(journal).readAfter(
				eq(LifecycleJournalStream.MEMPOOL), any(LifecycleJournalCursor.class), eq(256));
		assertThat(registry.counter("bridge.lifecycle.journal.gap", "stream", "CANONICAL").count())
				.isEqualTo(6.0d);
	}

	private LifecycleJournalEntry entry(UUID epoch, LifecycleJournalStream stream, long sequence) {
		LifecycleJournalOperation operation = stream == LifecycleJournalStream.CANONICAL
				? LifecycleJournalOperation.CONNECT
				: LifecycleJournalOperation.PENDING;
		return new LifecycleJournalEntry(
				LifecycleJournalEntry.CURRENT_VERSION,
				epoch,
				sequence,
				UUID.randomUUID(),
				stream,
				operation,
				null,
				0,
				1,
				stream == LifecycleJournalStream.CANONICAL ? sequence : -1L,
				Hash.ZERO,
				stream == LifecycleJournalStream.CANONICAL ? Hash.ZERO : null,
				Instant.parse("2026-08-29T00:00:00Z"),
				stream == LifecycleJournalStream.CANONICAL ? 3 : -1,
				stream == LifecycleJournalStream.MEMPOOL ? 0 : -1,
				new byte[0]);
	}
}
