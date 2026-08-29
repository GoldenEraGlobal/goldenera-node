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
package global.goldenera.node.core.storage.blockchain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolMutationBatch;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolMutationResult;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolStateMutation;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;

class MempoolLifecycleJournalWriterTest {

	@Test
	void springSelectsTheDependencyConstructor() {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(PersistentMempoolStore.class, () -> persistentStore);
			context.register(MempoolLifecycleJournalWriter.class);
			context.refresh();

			assertThat(context.getBean(MempoolLifecycleJournalWriter.class)).isNotNull();
		}
	}

	@Test
	void fiveHundredAdditionsUseOneRepositoryWriteAndPreserveInputOrder() throws Exception {
		Tx transaction = transfer(1, 1L);
		List<MempoolTxAddEvent> additions = new ArrayList<>();
		for (int index = 0; index < 500; index++) {
			MempoolEntry entry = new MempoolEntry(
					transaction, Instant.EPOCH.plusSeconds(index), index, null);
			additions.add(new MempoolTxAddEvent(this, entry, MempoolTxAddEvent.AddReason.SYNC));
		}
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		MempoolLifecycleJournalWriter writer = new MempoolLifecycleJournalWriter(persistentStore);

		writer.appendBeforeWake(List.of(), additions);

		ArgumentCaptor<MempoolMutationBatch> captured = ArgumentCaptor.forClass(MempoolMutationBatch.class);
		verify(persistentStore, times(1)).commit(captured.capture());
		assertThat(captured.getValue().mutations()).hasSize(500);
		assertThat(captured.getValue().mutations())
				.allSatisfy(mutation -> assertThat(mutation).isInstanceOf(MempoolStateMutation.UpsertActive.class));
		assertThat(captured.getValue().mutations().stream()
				.map(MempoolStateMutation.UpsertActive.class::cast)
				.map(upsert -> upsert.record().firstSeenHeight()).toList())
				.containsExactlyElementsOf(LongStream.range(0, 500).boxed().toList());
		assertThat(captured.getValue().mutations()).allSatisfy(mutation -> {
			LifecycleJournalDraft draft = mutation.journalDraft();
			assertThat(draft.operation()).isEqualTo(LifecycleJournalOperation.PENDING);
			assertThat(draft.reasonCode()).isEqualTo(MempoolLifecycleReason.SYNC.code());
		});
	}

	@Test
	void replacementRemovalPrecedesAdditionInSameJournalBatch() throws Exception {
		Tx original = transfer(2, 1L);
		Tx replacement = transfer(3, 1L);
		MempoolEntry originalEntry = new MempoolEntry(original, Instant.EPOCH, 10L, null);
		MempoolEntry replacementEntry = new MempoolEntry(replacement, Instant.EPOCH.plusSeconds(1), 10L, null);
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		MempoolLifecycleJournalWriter writer = new MempoolLifecycleJournalWriter(persistentStore);

		writer.appendBeforeWake(
				List.of(new MempoolTxRemoveEvent(
						this, originalEntry, MempoolTxRemoveEvent.RemoveReason.RBF, replacement.getHash())),
				List.of(new MempoolTxAddEvent(
						this, replacementEntry, MempoolTxAddEvent.AddReason.NEW)));

		ArgumentCaptor<MempoolMutationBatch> captured = ArgumentCaptor.forClass(MempoolMutationBatch.class);
		verify(persistentStore).commit(captured.capture());
		assertThat(captured.getValue().mutations()).extracting(mutation -> mutation.journalDraft().operation())
				.containsExactly(LifecycleJournalOperation.REPLACED, LifecycleJournalOperation.PENDING);
		LifecycleJournalDraft replaced = captured.getValue().mutations().getFirst().journalDraft();
		assertThat(replaced.primaryHash()).isEqualTo(original.getHash());
		assertThat(replaced.relatedHash()).isEqualTo(replacement.getHash());
		assertThat(TxDecoder.INSTANCE.decode(Bytes.wrap(replaced.payload())).getHash()).isEqualTo(original.getHash());
		MempoolStateMutation.UpsertActive upsert = (MempoolStateMutation.UpsertActive)
				captured.getValue().mutations().get(1);
		assertThat(upsert.record().replacesTxHash()).isEqualTo(original.getHash());
	}

	@Test
	void minedRemovalDeletesPersistentStateWithoutLifecycleDraft() throws Exception {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		MempoolLifecycleJournalWriter writer = new MempoolLifecycleJournalWriter(persistentStore);
		MempoolEntry entry = new MempoolEntry(transfer(4, 2L));

		writer.appendBeforeWake(
				List.of(new MempoolTxRemoveEvent(this, entry, MempoolTxRemoveEvent.RemoveReason.MINED)),
				List.of());

		ArgumentCaptor<MempoolMutationBatch> captured = ArgumentCaptor.forClass(MempoolMutationBatch.class);
		verify(persistentStore).commit(captured.capture());
		assertThat(captured.getValue().mutations()).singleElement()
				.satisfies(mutation -> {
					assertThat(mutation).isInstanceOf(MempoolStateMutation.DeleteActive.class);
					assertThat(mutation.journalDraft()).isNull();
				});
	}

	@Test
	void uncertainCommitRetriesWithSameBatchIdentityAndDoesNotDuplicateWakeSource() throws Exception {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		when(persistentStore.commit(any()))
				.thenThrow(new IllegalStateException("uncertain write"))
				.thenReturn(new MempoolMutationResult(false, 7L, List.of()));
		MempoolLifecycleJournalWriter writer = new MempoolLifecycleJournalWriter(persistentStore);
		MempoolEntry entry = new MempoolEntry(transfer(5, 3L));

		writer.appendBeforeWake(
				List.of(),
				List.of(new MempoolTxAddEvent(this, entry, MempoolTxAddEvent.AddReason.NEW)));

		ArgumentCaptor<MempoolMutationBatch> captured = ArgumentCaptor.forClass(MempoolMutationBatch.class);
		verify(persistentStore, times(2)).commit(captured.capture());
		assertThat(captured.getAllValues()).extracting(MempoolMutationBatch::batchId)
				.containsExactly(captured.getValue().batchId(), captured.getValue().batchId());
	}

	private Tx transfer(int privateKey, long nonce) throws Exception {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.timestamp(Instant.parse("2026-08-29T10:00:00Z"))
				.recipient(Address.fromHexString(String.format("0x%040x", 100 + privateKey)))
				.amount(Wei.valueOf(BigInteger.ONE))
				.fee(Wei.valueOf(BigInteger.ONE))
				.nonce(nonce)
				.sign(PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", privateKey))));
	}
}
