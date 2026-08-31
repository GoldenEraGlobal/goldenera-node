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
package global.goldenera.node.core.storage.blockchain.mempool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.properties.LifecycleJournalProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalDraft;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalRepository;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PersistentMempoolRepositoryIntegrationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void atomicallyUpsertsDeletesJournalsAndDeduplicatesImmediateRetry() throws Exception {
		try (Fixture fixture = fixture()) {
			StoredMempoolTransaction record = record(transaction(1, 1L), 10L, MempoolAdmissionReason.NEW, null);
			UUID upsertBatchId = UUID.randomUUID();
			MempoolMutationBatch upsert = new MempoolMutationBatch(upsertBatchId, List.of(
					MempoolStateMutation.upsert(record, pendingDraft(record))));

			MempoolMutationResult applied = fixture.store.commit(upsert);
			MempoolMutationResult duplicate = fixture.store.commit(upsert);

			assertThat(applied.applied()).isTrue();
			assertThat(applied.batchSequence()).isEqualTo(1L);
			assertThat(applied.journalEntries()).singleElement()
					.extracting(LifecycleJournalEntry::sequence).isEqualTo(1L);
			assertThat(duplicate.applied()).isFalse();
			assertThat(fixture.store.findActive(record.txHash())).contains(record);
			assertThat(fixture.journal.head(LifecycleJournalStream.MEMPOOL).sequence()).isEqualTo(1L);

			LifecycleJournalDraft dropped = LifecycleJournalDraft.dropped(
					record.txHash(), record.firstSeenHeight(), Instant.now(),
					MempoolLifecycleReason.INVALID.code(), record.rawSignedTx());
			MempoolMutationResult deleted = fixture.store.commit(new MempoolMutationBatch(
					UUID.randomUUID(), List.of(MempoolStateMutation.delete(record.txHash(), dropped))));
			assertThat(deleted.batchSequence()).isEqualTo(2L);
			assertThat(fixture.store.findActive(record.txHash())).isEmpty();
			assertThat(fixture.journal.head(LifecycleJournalStream.MEMPOOL).sequence()).isEqualTo(2L);
		}
	}

	@Test
	void abandonedAtomicBatchLeavesStateJournalAndBothHeadsUnchangedThenRetryIsStable() throws Exception {
		try (Fixture fixture = fixture()) {
			StoredMempoolTransaction record = record(transaction(2, 2L), 20L, MempoolAdmissionReason.SYNC, null);
			MempoolMutationBatch mutationBatch = new MempoolMutationBatch(UUID.randomUUID(), List.of(
					MempoolStateMutation.upsert(record, pendingDraft(record))));
			doAnswer(invocation -> {
				try (WriteBatch abandoned = new WriteBatch()) {
					RocksDBRepository.BatchOperation operation = invocation.getArgument(0);
					operation.execute(abandoned);
				}
				return null;
			}).when(fixture.rocks).executeAtomicBatch(any());

			MempoolMutationResult abandoned = fixture.store.commit(mutationBatch);
			assertThat(fixture.store.findActive(record.txHash())).isEmpty();
			assertThat(fixture.store.head().batchSequence()).isZero();
			assertThat(fixture.journal.head(LifecycleJournalStream.MEMPOOL).sequence()).isZero();

			doCallRealMethod().when(fixture.rocks).executeAtomicBatch(any());
			MempoolMutationResult retried = fixture.store.commit(mutationBatch);
			assertThat(retried.journalEntries().getFirst().eventKey())
					.isEqualTo(abandoned.journalEntries().getFirst().eventKey());
			assertThat(fixture.store.findActive(record.txHash())).contains(record);
		}
	}

	@Test
	void recoveryScanIsBoundedOrderedAndCursorBased() throws Exception {
		try (Fixture fixture = fixture()) {
			List<MempoolStateMutation> mutations = new ArrayList<>();
			for (int index = 1; index <= 3; index++) {
				StoredMempoolTransaction record = record(
						transaction(index + 10, index), index, MempoolAdmissionReason.SYNC, null);
				mutations.add(MempoolStateMutation.upsert(record, pendingDraft(record)));
			}
			fixture.store.commit(new MempoolMutationBatch(UUID.randomUUID(), mutations));

			MempoolRecoveryPage first = fixture.store.scanActive(null, 2);
			MempoolRecoveryPage second = fixture.store.scanActive(first.nextCursor(), 2);
			assertThat(first.records()).hasSize(2);
			assertThat(first.hasMore()).isTrue();
			assertThat(second.records()).hasSize(1);
			assertThat(second.hasMore()).isFalse();
			assertThat(Stream.concat(first.records().stream(), second.records().stream())
					.map(StoredMempoolTransaction::txHash).toList()).isSorted();
		}
	}

	@Test
	void epochChangeClearsOperationalStateAndResetsBatchHead() throws Exception {
		try (Fixture fixture = fixture()) {
			StoredMempoolTransaction record = record(
					transaction(20, 1L), 30L, MempoolAdmissionReason.NEW, null);
			fixture.store.commit(new MempoolMutationBatch(UUID.randomUUID(), List.of(
					MempoolStateMutation.upsert(record, pendingDraft(record)))));
			UUID newEpoch = UUID.randomUUID();

			fixture.store.initializeForEpoch(newEpoch);

			assertThat(fixture.store.findActive(record.txHash())).isEmpty();
			assertThat(fixture.store.head().epoch()).isEqualTo(newEpoch);
			assertThat(fixture.store.head().batchSequence()).isZero();
		}
	}

	@Test
	void moreThan4096MinedDeletionsCommitInOneAtomicBatch() throws Exception {
		try (Fixture fixture = fixture()) {
			List<MempoolStateMutation> deletions = IntStream.range(0, 5_000)
					.<MempoolStateMutation>mapToObj(index -> MempoolStateMutation.delete(
							Hash.fromHexString("0x" + "%064x".formatted(index + 1)), null))
					.toList();

			MempoolMutationResult result = fixture.store.commit(
					new MempoolMutationBatch(UUID.randomUUID(), deletions));

			assertThat(result.applied()).isTrue();
			assertThat(result.batchSequence()).isEqualTo(1L);
			assertThat(result.journalEntries()).isEmpty();
			assertThat(fixture.journal.head(LifecycleJournalStream.MEMPOOL).sequence()).isZero();
			assertThat(fixture.store.scanActive(null, 1).records()).isEmpty();
		}
	}

	@Test
	void canonicalProjectionCursorCommitsAtomicallyWithReorgReaddAndImmediateRetryIsIdempotent() throws Exception {
		try (Fixture fixture = fixture()) {
			UUID epoch = fixture.journal.head(LifecycleJournalStream.CANONICAL).epoch();
			fixture.store.initializeCanonicalProjectionCursor(epoch, 0L);
			StoredMempoolTransaction record = record(
					transaction(30, 1L), 40L, MempoolAdmissionReason.REORG, null);
			UUID batchId = UUID.randomUUID();
			MempoolMutationBatch batch = new MempoolMutationBatch(
					batchId,
					List.of(MempoolStateMutation.upsert(record, pendingDraft(record))),
					new MempoolCanonicalProjectionAdvance(epoch, 0L, 1L));

			MempoolMutationResult applied = fixture.store.commit(batch);
			MempoolMutationResult retry = fixture.store.commit(batch);

			assertThat(applied.applied()).isTrue();
			assertThat(retry.applied()).isFalse();
			assertThat(fixture.store.findActive(record.txHash())).contains(record);
			assertThat(fixture.store.canonicalProjectionCursor())
					.contains(new MempoolCanonicalProjectionCursor(
							MempoolCanonicalProjectionCursor.CURRENT_VERSION, epoch, 1L));
		}
	}

	private Fixture fixture() throws Exception {
		Path path = temporaryDirectory.resolve(UUID.randomUUID().toString());
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(properties(path)).open(path, families);
		RocksDBRepository rocks = spy(new RocksDBRepository(database, families));
		LifecycleJournalRepository journal = new LifecycleJournalRepository(
				rocks, families, new LifecycleJournalProperties(), new SimpleMeterRegistry());
		journal.initializeAnchorIfMissing(-1L, Hash.ZERO);
		PersistentMempoolRepository store = new PersistentMempoolRepository(rocks, families, journal);
		store.initializeForEpoch(journal.head(LifecycleJournalStream.MEMPOOL).epoch());
		store.initializeCanonicalProjectionCursor(journal.head(LifecycleJournalStream.CANONICAL).epoch(), 0L);
		return new Fixture(database, families, rocks, journal, store);
	}

	private LifecycleJournalDraft pendingDraft(StoredMempoolTransaction record) {
		return switch (record.admissionReason()) {
			case NEW -> LifecycleJournalDraft.pending(
					record.txHash(), record.firstSeenHeight(), record.firstSeenTime(),
					MempoolLifecycleReason.NEW.code(), record.rawSignedTx());
			case SYNC -> LifecycleJournalDraft.pending(
					record.txHash(), record.firstSeenHeight(), record.firstSeenTime(),
					MempoolLifecycleReason.SYNC.code(), record.rawSignedTx());
			case REORG -> LifecycleJournalDraft.reorgReadd(
					record.txHash(), record.firstSeenHeight(), record.firstSeenTime(),
					MempoolLifecycleReason.REORG.code(), record.rawSignedTx());
		};
	}

	private StoredMempoolTransaction record(
			Tx transaction,
			long firstSeenHeight,
			MempoolAdmissionReason reason,
			Hash replaces) {
		return new StoredMempoolTransaction(
				1, StoredMempoolStatus.ACTIVE, transaction.getHash(),
				TxEncoder.INSTANCE.encode(transaction, true).toArray(),
				Instant.parse("2026-08-29T12:00:00Z"), firstSeenHeight, reason, replaces);
	}

	private Tx transaction(int privateKey, long nonce) throws Exception {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.timestamp(Instant.parse("2026-08-29T12:00:00Z"))
				.recipient(Address.fromHexString(String.format("0x%040x", privateKey + 100)))
				.amount(Wei.valueOf(BigInteger.ONE))
				.fee(Wei.valueOf(BigInteger.ONE))
				.nonce(nonce)
				.sign(PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", privateKey))));
	}

	private BlockchainDbProperties properties(Path path) {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(path.toString());
		properties.setRocksdbBlockCacheMb(1);
		properties.setRocksdbWriteBufferMb(1);
		properties.setRocksdbMaxWriteBuffers(2);
		properties.setRocksdbMaxBackgroundJobs(1);
		properties.setRocksdbBlockSizeKb(4);
		properties.setRocksdbDirectReads(false);
		properties.setRocksdbDirectWrites(false);
		properties.setRocksdbBlobEnabled(false);
		return properties;
	}

	private record Fixture(
			RocksDB database,
			RocksDbColumnFamilies families,
			RocksDBRepository rocks,
			LifecycleJournalRepository journal,
			PersistentMempoolRepository store) implements AutoCloseable {
		@Override
		public void close() {
			families.close();
			database.close();
		}
	}
}
