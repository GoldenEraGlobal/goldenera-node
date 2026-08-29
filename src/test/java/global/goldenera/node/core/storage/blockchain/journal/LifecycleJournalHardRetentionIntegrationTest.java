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
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.properties.LifecycleJournalProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LifecycleJournalHardRetentionIntegrationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void hardLimitPrunesWithoutPostgresAndOldConsumerFailsAtFloor() throws Exception {
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(databaseProperties()).open(temporaryDirectory, families);
		try {
			RocksDBRepository rocks = new RocksDBRepository(database, families);
			LifecycleJournalProperties retention = new LifecycleJournalProperties();
			retention.setHardMaxRetainedEntries(3L);
			LifecycleJournalRepository journal = new LifecycleJournalRepository(
					rocks, families, retention, new SimpleMeterRegistry());
			journal.initializeAnchorIfMissing(0L, Hash.ZERO);
			for (int sequence = 1; sequence <= 4; sequence++) {
				Hash blockHash = hash(sequence);
				rocks.executeAtomicBatch(batch -> journal.appendToBatch(
						LifecycleJournalStream.CANONICAL,
						batch,
						List.of(LifecycleJournalDraft.connect(
								null, 0, 1, blockHash.toArray()[Hash.SIZE - 1], blockHash, Hash.ZERO,
								Instant.parse("2026-08-29T00:00:00Z"), 0, null))));
			}

			LifecycleJournalHead head = journal.head(LifecycleJournalStream.CANONICAL);
			assertThat(head.sequence()).isEqualTo(4L);
			assertThat(head.floorSequence()).isEqualTo(4L);
			assertThat(journal.find(LifecycleJournalStream.CANONICAL, 1L)).isEmpty();
			assertThat(journal.find(LifecycleJournalStream.CANONICAL, 4L)).isPresent();
			assertThatThrownBy(() -> journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					new LifecycleJournalCursor(head.epoch(), 0L),
					10)).isInstanceOf(LifecycleJournalFloorException.class);
		} finally {
			families.close();
			database.close();
		}
	}

	@Test
	void pruneTargetInsideCanonicalGroupRetainsTheWholeGroup() throws Exception {
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(databaseProperties()).open(temporaryDirectory, families);
		try {
			RocksDBRepository rocks = new RocksDBRepository(database, families);
			LifecycleJournalProperties retention = new LifecycleJournalProperties();
			retention.setHardMaxRetainedEntries(100L);
			LifecycleJournalRepository journal = new LifecycleJournalRepository(
					rocks, families, retention, new SimpleMeterRegistry());
			journal.initializeAnchorIfMissing(0L, Hash.ZERO);
			UUID groupId = UUID.randomUUID();
			List<LifecycleJournalDraft> group = IntStream.range(0, 5)
					.mapToObj(ordinal -> LifecycleJournalDraft.connect(
							groupId, ordinal, 5, ordinal + 1L, hash(ordinal + 1), Hash.ZERO,
							Instant.parse("2026-08-29T00:00:00Z"), 0, null))
					.toList();
			rocks.executeAtomicBatch(batch -> journal.appendToBatch(
					LifecycleJournalStream.CANONICAL, batch, group));

			journal.pruneThrough(LifecycleJournalStream.CANONICAL, 2L);

			LifecycleJournalHead retained = journal.head(LifecycleJournalStream.CANONICAL);
			assertThat(retained.floorSequence()).isEqualTo(1L);
			assertThat(journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					new LifecycleJournalCursor(retained.epoch(), 0L),
					10)).extracting(LifecycleJournalEntry::groupOrdinal)
					.containsExactly(0, 1, 2, 3, 4);

			journal.pruneThrough(LifecycleJournalStream.CANONICAL, 5L);
			assertThat(journal.head(LifecycleJournalStream.CANONICAL).floorSequence()).isEqualTo(6L);
		} finally {
			families.close();
			database.close();
		}
	}

	private BlockchainDbProperties databaseProperties() {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(temporaryDirectory.toString());
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

	private Hash hash(int value) {
		return Hash.fromHexString("0x" + "%064x".formatted(value));
	}
}
