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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolRepository;

class LifecycleJournalLegacyUpgradeTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void oldLayoutIsAnchoredWithoutHistoryAndFirstNewTransitionGetsSequenceOne() throws Exception {
		Path path = temporaryDirectory.resolve("legacy-blockchain");
		Hash oldHead = hash('a');
		createPreJournalDatabase(path, 123L, oldHead);

		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(properties(path)).open(path, families);
		try {
			assertThat(database.get(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH))
					.isEqualTo(oldHead.toArray());
			assertThat(database.get(families.hashByHeight(), Bytes.ofUnsignedLong(123L).toArray()))
					.isEqualTo(oldHead.toArray());
			assertThat(families.lifecycleJournal()).isNotNull();
			assertThat(families.mempoolState()).isNotNull();

			RocksDBRepository rocks = new RocksDBRepository(database, families);
			LifecycleJournalRepository journal = new LifecycleJournalRepository(rocks, families);
			BlockRepository blockRepository = mock(BlockRepository.class);
			StoredBlock storedHead = mock(StoredBlock.class);
			when(storedHead.getHeight()).thenReturn(123L);
			when(storedHead.getHash()).thenReturn(oldHead);
			when(blockRepository.getLatestStoredBlock()).thenReturn(java.util.Optional.of(storedHead));
			new LifecycleJournalBootstrap(journal, blockRepository).afterPropertiesSet();
			PersistentMempoolRepository persistentMempool = new PersistentMempoolRepository(rocks, families, journal);
			persistentMempool.initializeForEpoch(journal.head(LifecycleJournalStream.MEMPOOL).epoch());
			assertThat(persistentMempool.head().batchSequence()).isZero();
			assertThat(persistentMempool.scanActive(null, 10).records()).isEmpty();
			LifecycleJournalHead anchored = journal.head(LifecycleJournalStream.CANONICAL);
			assertThat(anchored.sequence()).isZero();
			assertThat(anchored.anchorHeight()).isEqualTo(123L);
			assertThat(anchored.anchorHash()).isEqualTo(oldHead);
			assertThat(journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					new LifecycleJournalCursor(anchored.epoch(), 0L), 10)).isEmpty();

			Hash nextHead = hash('b');
			rocks.executeAtomicBatch(batch -> journal.appendToBatch(
					LifecycleJournalStream.CANONICAL,
					batch,
					List.of(LifecycleJournalDraft.connect(
							null, 0, 1, 124L, nextHead, oldHead, Instant.EPOCH, 2, null))));
			assertThat(journal.readAfter(
					LifecycleJournalStream.CANONICAL,
					new LifecycleJournalCursor(anchored.epoch(), 0L), 10))
					.singleElement()
					.extracting(LifecycleJournalEntry::sequence)
					.isEqualTo(1L);
		} finally {
			families.close();
			database.close();
		}
	}

	private void createPreJournalDatabase(Path path, long headHeight, Hash headHash) throws Exception {
		RocksDB.loadLibrary();
		List<String> oldNames = BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES.stream()
				.filter(name -> !RocksDbColumnFamilies.CF_LIFECYCLE_JOURNAL.equals(name))
				.filter(name -> !RocksDbColumnFamilies.CF_MEMPOOL_STATE.equals(name))
				.toList();
		List<ColumnFamilyOptions> options = new ArrayList<>();
		List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
		for (String name : oldNames) {
			ColumnFamilyOptions familyOptions = new ColumnFamilyOptions();
			options.add(familyOptions);
			byte[] encodedName = BlockchainRocksDbFactory.DEFAULT_COLUMN_FAMILY.equals(name)
					? RocksDB.DEFAULT_COLUMN_FAMILY
					: name.getBytes(UTF_8);
			descriptors.add(new ColumnFamilyDescriptor(encodedName, familyOptions));
		}
		List<ColumnFamilyHandle> handles = new ArrayList<>();
		try (DBOptions databaseOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true);
				RocksDB database = RocksDB.open(databaseOptions, path.toString(), descriptors, handles)) {
			int metadataIndex = oldNames.indexOf(RocksDbColumnFamilies.CF_METADATA);
			int heightIndex = oldNames.indexOf(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT);
			database.put(handles.get(metadataIndex), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, headHash.toArray());
			database.put(handles.get(heightIndex), Bytes.ofUnsignedLong(headHeight).toArray(), headHash.toArray());
		} finally {
			handles.forEach(ColumnFamilyHandle::close);
			options.forEach(ColumnFamilyOptions::close);
		}
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
}
