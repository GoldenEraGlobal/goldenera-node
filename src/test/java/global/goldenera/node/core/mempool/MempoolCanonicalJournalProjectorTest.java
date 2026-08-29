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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
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

class MempoolCanonicalJournalProjectorTest {

	@Test
	void emptyMempoolFastForwardsConsecutiveConnectHistoryInOneDurableCursorCommit() {
		UUID epoch = UUID.randomUUID();
		LifecycleJournalEntry first = connect(epoch, 1L);
		LifecycleJournalEntry second = connect(epoch, 2L);
		Hash headHash = second.primaryHash();
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 2L, 1L, 2L, headHash));
		when(journal.readAfter(
				any(LifecycleJournalStream.class), any(LifecycleJournalCursor.class), any(Integer.class)))
				.thenAnswer(invocation -> {
					LifecycleJournalCursor requested = invocation.getArgument(1);
					return requested.sequence() == 0L ? List.of(first, second) : List.of();
				});
		AtomicReference<MempoolCanonicalProjectionCursor> cursor = new AtomicReference<>(
				new MempoolCanonicalProjectionCursor(1, epoch, 0L));
		PersistentMempoolStore persistent = mock(PersistentMempoolStore.class);
		when(persistent.canonicalProjectionCursor()).thenAnswer(invocation -> Optional.of(cursor.get()));
		MempoolStore store = mock(MempoolStore.class);
		when(store.getCount()).thenReturn(0L);
		AtomicReference<MempoolCanonicalProjectionAdvance> committed = new AtomicReference<>();
		doAnswer(invocation -> {
			MempoolCanonicalProjectionAdvance advance = invocation.getArgument(0);
			invocation.<Runnable>getArgument(1).run();
			committed.set(advance);
			cursor.set(new MempoolCanonicalProjectionCursor(1, epoch, advance.newSequence()));
			return null;
		}).when(store).executeCanonicalPersistenceBatch(any(), any());
		MempoolManager manager = mock(MempoolManager.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		MempoolCanonicalJournalProjector projector = new MempoolCanonicalJournalProjector(
				journal, persistent, chainQuery, store, manager,
				MempoolRecoveryGate.completedForTests(), Runnable::run);

		projector.drainToHead();

		assertThat(committed.get().expectedSequence()).isZero();
		assertThat(committed.get().newSequence()).isEqualTo(2L);
		verifyNoInteractions(chainQuery, manager);
	}

	@Test
	void crashBeforeAtomicCursorCommitReplaysDisconnectedBlockIdempotently() {
		UUID epoch = UUID.randomUUID();
		Hash blockHash = Hash.fromHexString("0x" + "12".repeat(32));
		LifecycleJournalEntry entry = mock(LifecycleJournalEntry.class);
		when(entry.epoch()).thenReturn(epoch);
		when(entry.sequence()).thenReturn(1L);
		when(entry.operation()).thenReturn(LifecycleJournalOperation.DISCONNECT);
		when(entry.primaryHash()).thenReturn(blockHash);

		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 1L, 1L, 1L, blockHash));
		when(journal.readAfter(
				any(LifecycleJournalStream.class), any(LifecycleJournalCursor.class), any(Integer.class)))
				.thenAnswer(invocation -> {
					LifecycleJournalCursor cursor = invocation.getArgument(1);
					return cursor.sequence() == 0L ? List.of(entry) : List.of();
				});

		AtomicReference<MempoolCanonicalProjectionCursor> cursor = new AtomicReference<>(
				new MempoolCanonicalProjectionCursor(1, epoch, 0L));
		PersistentMempoolStore persistent = mock(PersistentMempoolStore.class);
		when(persistent.canonicalProjectionCursor()).thenAnswer(invocation -> Optional.of(cursor.get()));
		MempoolStore store = mock(MempoolStore.class);
		doAnswer(invocation -> {
			MempoolCanonicalProjectionAdvance advance = invocation.getArgument(0);
			Runnable operation = invocation.getArgument(1);
			operation.run();
			cursor.set(new MempoolCanonicalProjectionCursor(1, advance.epoch(), advance.newSequence()));
			return null;
		}).when(store).executeCanonicalPersistenceBatch(any(), any());

		Block block = mock(Block.class);
		StoredBlock stored = mock(StoredBlock.class);
		when(stored.getHeight()).thenReturn(1L);
		when(stored.getBlock()).thenReturn(block);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getStoredBlockByHash(blockHash)).thenReturn(Optional.of(stored));
		MempoolManager manager = mock(MempoolManager.class);
		doThrow(new IllegalStateException("crash before RocksDB commit"))
				.doNothing()
				.when(manager).applyCanonicalDisconnect(block);

		MempoolCanonicalJournalProjector projector = new MempoolCanonicalJournalProjector(
				journal, persistent, chainQuery, store, manager,
				MempoolRecoveryGate.completedForTests(), Runnable::run);

		assertThatThrownBy(projector::drainToHead)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("crash");
		projector.drainToHead();

		verify(manager, times(2)).applyCanonicalDisconnect(block);
	}

	private LifecycleJournalEntry connect(UUID epoch, long sequence) {
		LifecycleJournalEntry entry = mock(LifecycleJournalEntry.class);
		when(entry.epoch()).thenReturn(epoch);
		when(entry.sequence()).thenReturn(sequence);
		when(entry.operation()).thenReturn(LifecycleJournalOperation.CONNECT);
		when(entry.primaryHash()).thenReturn(Hash.fromHexString("0x" + "%064x".formatted(sequence)));
		return entry;
	}
}
