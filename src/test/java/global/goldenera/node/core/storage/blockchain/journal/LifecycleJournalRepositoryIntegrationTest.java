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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;

class LifecycleJournalRepositoryIntegrationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void readsInOrderWithIndependentStreamSequencesAndFloor() throws Exception {
		try (Fixture fixture = fixture()) {
			Hash anchor = hash('1');
			fixture.journal.initializeAnchorIfMissing(10L, anchor);
			UUID group = UUID.randomUUID();
			fixture.rocks.executeAtomicBatch(batch -> fixture.journal.appendToBatch(
					LifecycleJournalStream.CANONICAL, batch, List.of(
							LifecycleJournalDraft.connect(group, 0, 2, 11L, hash('2'), anchor,
									Instant.EPOCH, 3, null),
							LifecycleJournalDraft.connect(group, 1, 2, 12L, hash('3'), hash('2'),
									Instant.EPOCH.plusSeconds(1), 3, null))));
			fixture.journal.appendMempool(List.of(LifecycleJournalDraft.pending(
					hash('4'), 12L, Instant.EPOCH, MempoolLifecycleReason.NEW.code(), new byte[] { 7 })));

			assertThat(fixture.journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					cursor(fixture, LifecycleJournalStream.CANONICAL, 0L), 10))
					.extracting(LifecycleJournalEntry::sequence)
					.containsExactly(1L, 2L);
			assertThat(fixture.journal.readAfter(
					LifecycleJournalStream.MEMPOOL,
					cursor(fixture, LifecycleJournalStream.MEMPOOL, 0L), 10))
					.singleElement().extracting(LifecycleJournalEntry::sequence).isEqualTo(1L);
			assertThat(fixture.journal.head(LifecycleJournalStream.CANONICAL).anchorHash()).isEqualTo(anchor);

			fixture.journal.pruneThrough(LifecycleJournalStream.CANONICAL, 2L);
			assertThat(fixture.journal.find(LifecycleJournalStream.CANONICAL, 1L)).isEmpty();
			assertThat(fixture.journal.find(LifecycleJournalStream.CANONICAL, 2L)).isEmpty();
			assertThatThrownBy(() -> fixture.journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					cursor(fixture, LifecycleJournalStream.CANONICAL, 0L), 10))
					.isInstanceOf(LifecycleJournalFloorException.class);
		}
	}

	@Test
	void uncommittedBatchIsInvisibleAndRetryReusesStableSequenceAndEventKey() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.journal.initializeAnchorIfMissing(-1L, Hash.ZERO);
			LifecycleJournalDraft draft = LifecycleJournalDraft.connect(
					null, 0, 1, 0L, hash('5'), Hash.ZERO, Instant.EPOCH, 0, null);
			UUID stagedEventKey;
			try (WriteBatch abandoned = new WriteBatch()) {
				stagedEventKey = fixture.journal.appendToBatch(
						LifecycleJournalStream.CANONICAL, abandoned, List.of(draft)).getFirst().eventKey();
			}

			assertThat(fixture.journal.head(LifecycleJournalStream.CANONICAL).sequence()).isZero();
			assertThat(fixture.journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					cursor(fixture, LifecycleJournalStream.CANONICAL, 0L), 10)).isEmpty();

			fixture.rocks.executeAtomicBatch(batch -> fixture.journal.appendToBatch(
					LifecycleJournalStream.CANONICAL, batch, List.of(draft)));
			LifecycleJournalEntry committed = fixture.journal.find(
					LifecycleJournalStream.CANONICAL, 1L).orElseThrow();
			assertThat(committed.eventKey()).isEqualTo(stagedEventKey);
		}
	}

	@Test
	void snapshotResetCreatesNewEpochSoSameSequenceHasDifferentEventKeyAndOldCursorFails() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.journal.initializeAnchorIfMissing(10L, hash('6'));
			LifecycleJournalCursor oldCursor = fixture.journal.head(LifecycleJournalStream.CANONICAL).cursor();
			LifecycleJournalDraft firstDraft = LifecycleJournalDraft.connect(
					null, 0, 1, 11L, hash('7'), hash('6'), Instant.EPOCH, 2, null);
			fixture.rocks.executeAtomicBatch(batch -> fixture.journal.appendToBatch(
					LifecycleJournalStream.CANONICAL, batch, List.of(firstDraft)));
			LifecycleJournalEntry oldEntry = fixture.journal.find(
					LifecycleJournalStream.CANONICAL, 1L).orElseThrow();

			fixture.rocks.executeAtomicBatch(batch ->
					LifecycleJournalStorageLayout.clearForHistoricalSnapshot(batch, fixture.families));
			fixture.journal.initializeAnchorIfMissing(20L, hash('8'));
			LifecycleJournalHead resetHead = fixture.journal.head(LifecycleJournalStream.CANONICAL);
			assertThat(resetHead.epoch()).isNotEqualTo(oldCursor.epoch());
			assertThat(resetHead.sequence()).isZero();
			LifecycleJournalDraft secondDraft = LifecycleJournalDraft.connect(
					null, 0, 1, 21L, hash('9'), hash('8'), Instant.EPOCH, 2, null);
			fixture.rocks.executeAtomicBatch(batch -> fixture.journal.appendToBatch(
					LifecycleJournalStream.CANONICAL, batch, List.of(secondDraft)));
			LifecycleJournalEntry newEntry = fixture.journal.find(
					LifecycleJournalStream.CANONICAL, 1L).orElseThrow();

			assertThat(newEntry.eventKey()).isNotEqualTo(oldEntry.eventKey());
			assertThatThrownBy(() -> fixture.journal.readAfter(
					LifecycleJournalStream.CANONICAL, oldCursor, 10))
					.isInstanceOf(LifecycleJournalEpochException.class);
		}
	}

	@Test
	void persistsDisconnectConnectAndReorgCommitAsOneOrderedCanonicalGroup() throws Exception {
		try (Fixture fixture = fixture()) {
			Hash ancestor = hash('a');
			Hash oldHead = hash('b');
			Hash newHead = hash('c');
			fixture.journal.initializeAnchorIfMissing(50L, oldHead);
			UUID groupId = UUID.randomUUID();
			fixture.rocks.executeAtomicBatch(batch -> fixture.journal.appendToBatch(
					LifecycleJournalStream.CANONICAL, batch, List.of(
							LifecycleJournalDraft.disconnect(
									groupId, 0, 3, 50L, oldHead, ancestor, Instant.EPOCH, 4),
							LifecycleJournalDraft.connect(
									groupId, 1, 3, 50L, newHead, ancestor, Instant.EPOCH, 4, null),
							LifecycleJournalDraft.reorgCommit(
									groupId, 2, 3, 50L, newHead, oldHead, Instant.EPOCH, 4))));

			List<LifecycleJournalEntry> group = fixture.journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					new LifecycleJournalCursor(
							fixture.journal.head(LifecycleJournalStream.CANONICAL).epoch(), 0L),
					10);
			assertThat(group).extracting(LifecycleJournalEntry::operation).containsExactly(
					LifecycleJournalOperation.DISCONNECT,
					LifecycleJournalOperation.CONNECT,
					LifecycleJournalOperation.REORG_COMMIT);
			assertThat(group).extracting(LifecycleJournalEntry::groupOrdinal).containsExactly(0, 1, 2);
			assertThat(group).allSatisfy(entry -> {
				assertThat(entry.groupId()).isEqualTo(groupId);
				assertThat(entry.groupSize()).isEqualTo(3);
			});
		}
	}

	private Fixture fixture() throws Exception {
		Path path = temporaryDirectory.resolve(UUID.randomUUID().toString());
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(properties(path)).open(path, families);
		RocksDBRepository rocks = new RocksDBRepository(database, families);
		return new Fixture(database, families, rocks, new LifecycleJournalRepository(rocks, families));
	}

	private LifecycleJournalCursor cursor(
			Fixture fixture, LifecycleJournalStream stream, long sequence) {
		return new LifecycleJournalCursor(fixture.journal.head(stream).epoch(), sequence);
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

	private Hash hash(char digit) {
		return Hash.fromHexString("0x" + String.valueOf(digit).repeat(64));
	}

	private record Fixture(
			RocksDB database,
			RocksDbColumnFamilies families,
			RocksDBRepository rocks,
			LifecycleJournalRepository journal) implements AutoCloseable {
		@Override
		public void close() {
			families.close();
			database.close();
		}
	}
}
