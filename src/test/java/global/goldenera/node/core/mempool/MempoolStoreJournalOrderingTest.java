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

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.address;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolStore.StorageAddResult;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleJournalWriter;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolRecoveryPage;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolTransaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStoreJournalOrderingTest {

	@Test
	void concurrentReplacementCannotJournalBeforeOriginalPending() throws Exception {
		MempoolLifecycleJournalWriter writer = mock(MempoolLifecycleJournalWriter.class);
		CountDownLatch firstJournalEntered = new CountDownLatch(1);
		CountDownLatch allowFirstJournal = new CountDownLatch(1);
		doAnswer(invocation -> {
			firstJournalEntered.countDown();
			allowFirstJournal.await();
			return null;
		}).doAnswer(invocation -> null).when(writer).commitBeforeWake(any(), anyList(), anyList());
		MempoolStore store = store(writer, mock(ApplicationEventPublisher.class), null);
		MempoolEntry original = transfer(1, ALICE, 1L, 100L);
		MempoolEntry replacement = transfer(2, ALICE, 1L, 110L);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<StorageAddResult> first = executor.submit(() ->
					store.addTransaction(original, 0L, MempoolTxAddEvent.AddReason.NEW));
			assertThat(firstJournalEntered.await(2L, TimeUnit.SECONDS)).isTrue();
			Future<StorageAddResult> second = executor.submit(() ->
					store.addTransaction(replacement, 0L, MempoolTxAddEvent.AddReason.NEW));

			Thread.sleep(50L);
			assertThat(second).isNotDone();
			allowFirstJournal.countDown();
			assertThat(first.get()).matches(StorageAddResult::isSuccess);
			assertThat(second.get()).matches(StorageAddResult::isSuccess);
		}

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxRemoveEvent>> removals = ArgumentCaptor.forClass(List.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxAddEvent>> additions = ArgumentCaptor.forClass(List.class);
		verify(writer, org.mockito.Mockito.times(2)).commitBeforeWake(any(), removals.capture(), additions.capture());
		assertThat(removals.getAllValues().get(0)).isEmpty();
		assertThat(additions.getAllValues().get(0)).singleElement()
				.extracting(event -> event.getEntry().getHash()).isEqualTo(original.getHash());
		assertThat(removals.getAllValues().get(1)).singleElement()
				.satisfies(event -> {
					assertThat(event.getReason()).isEqualTo(MempoolTxRemoveEvent.RemoveReason.RBF);
					assertThat(event.getEntry().getHash()).isEqualTo(original.getHash());
				});
		assertThat(additions.getAllValues().get(1)).singleElement()
				.extracting(event -> event.getEntry().getHash()).isEqualTo(replacement.getHash());
	}

	@Test
	void journalFailureRollsBackDerivedMemoryAndSuppressesWakePublication() {
		MempoolLifecycleJournalWriter writer = mock(MempoolLifecycleJournalWriter.class);
		doThrow(new IllegalStateException("journal unavailable"))
				.when(writer).commitBeforeWake(any(), anyList(), anyList());
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		when(persistentStore.scanActive(null, 64))
				.thenReturn(new MempoolRecoveryPage(List.of(), null, false));
		MempoolStore store = store(writer, publisher, persistentStore);
		MempoolEntry entry = transfer(3, ALICE, 1L, 100L);

		assertThatThrownBy(() -> store.addTransaction(entry, 0L, MempoolTxAddEvent.AddReason.NEW))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("journal unavailable");
		assertThat(store.getTxByHash(entry.getHash())).isEmpty();
		verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void rollbackRestoreFailsClosedOnOversizedPersistentScan() {
		MempoolLifecycleJournalWriter writer = mock(MempoolLifecycleJournalWriter.class);
		doThrow(new IllegalStateException("journal unavailable"))
				.when(writer).commitBeforeWake(any(), anyList(), anyList());
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		List<StoredMempoolTransaction> oversized = IntStream.range(0, 4)
				.mapToObj(index -> {
					StoredMempoolTransaction record = mock(StoredMempoolTransaction.class);
					when(record.rawSignedTx()).thenReturn(new byte[] { 1 });
					return record;
				})
				.toList();
		when(persistentStore.scanActive(null, 64))
				.thenReturn(new MempoolRecoveryPage(oversized, null, false));
		MempoolStore store = new MempoolStore(
				new SimpleMeterRegistry(), properties(2), mock(ChainHeadStateCache.class),
				mock(ApplicationEventPublisher.class), writer, persistentStore);

		assertThatThrownBy(() -> store.addTransaction(
				transfer(30, ALICE, 1L, 100L), 0L, MempoolTxAddEvent.AddReason.NEW))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("could not be restored")
				.satisfies(failure -> assertThat(failure.getCause().getSuppressed())
						.anySatisfy(suppressed -> assertThat(suppressed.getMessage()).contains("record count")));
		assertThatThrownBy(store::getCount)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("terminally unavailable");
	}

	@Test
	void fiveHundredAdmissionsCommitAsOnePersistenceBatch() {
		MempoolLifecycleJournalWriter writer = mock(MempoolLifecycleJournalWriter.class);
		MempoolStore store = new MempoolStore(
				new SimpleMeterRegistry(), properties(1_000), mock(ChainHeadStateCache.class),
				mock(ApplicationEventPublisher.class), writer, null);

		store.executePersistenceBatch(() -> {
			for (int index = 1; index <= 500; index++) {
				StorageAddResult result = store.addTransaction(
						transfer(index, address(index + 10_000), 1L, 100L),
						0L,
						MempoolTxAddEvent.AddReason.SYNC);
				assertThat(result).matches(StorageAddResult::isSuccess);
			}
		});

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxAddEvent>> additions = ArgumentCaptor.forClass(List.class);
		verify(writer).commitBeforeWake(any(), anyList(), additions.capture());
		assertThat(additions.getValue()).hasSize(500);
	}

	private MempoolStore store(
			MempoolLifecycleJournalWriter writer,
			ApplicationEventPublisher publisher,
			PersistentMempoolStore persistentStore) {
		return new MempoolStore(
				new SimpleMeterRegistry(), properties(100), mock(ChainHeadStateCache.class), publisher, writer,
				persistentStore);
	}
}
