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
package global.goldenera.node.core.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotPreOpenInitializer;

class BlockchainDBConfigLegacyUpgradeTest {

	private static final byte[] LEGACY_KEY = "legacy-key".getBytes(UTF_8);
	private static final byte[] LEGACY_VALUE = "legacy-value".getBytes(UTF_8);
	private static final List<String> LEGACY_COLUMN_FAMILIES = List.of(
			RocksDbColumnFamilies.CF_STATE_TRIE,
			RocksDbColumnFamilies.CF_BLOCKS,
			RocksDbColumnFamilies.CF_TX_INDEX,
			RocksDbColumnFamilies.CF_HASH_BY_HEIGHT,
			RocksDbColumnFamilies.CF_METADATA,
			RocksDbColumnFamilies.CF_TOKENS,
			RocksDbColumnFamilies.CF_AUTHORITIES,
			RocksDbColumnFamilies.CF_VALIDATORS,
			RocksDbColumnFamilies.CF_ENTITY_UNDO_LOG);

	@TempDir
	Path tempDirectory;

	@Test
	void productionConfigAddsEquivocationsColumnFamilyWithoutLosingLegacyData() throws Exception {
		Path databasePath = tempDirectory.resolve("blockchain");
		createLegacyDatabase(databasePath);

		assertThat(listColumnFamilies(databasePath))
				.doesNotContain(RocksDbColumnFamilies.CF_EQUIVOCATIONS);

		openWithProductionConfigAndVerify(databasePath);

		assertThat(listColumnFamilies(databasePath))
				.contains(RocksDbColumnFamilies.CF_EQUIVOCATIONS)
				.containsAll(LEGACY_COLUMN_FAMILIES);

		openWithProductionConfigAndVerify(databasePath);
	}

	private void createLegacyDatabase(Path databasePath) throws RocksDBException {
		RocksDB.loadLibrary();
		List<ColumnFamilyHandle> handles = new ArrayList<>();
		List<ColumnFamilyOptions> options = new ArrayList<>();
		List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
		options.add(new ColumnFamilyOptions());
		descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, options.get(0)));
		for (String name : LEGACY_COLUMN_FAMILIES) {
			ColumnFamilyOptions columnOptions = new ColumnFamilyOptions();
			options.add(columnOptions);
			descriptors.add(new ColumnFamilyDescriptor(name.getBytes(UTF_8), columnOptions));
		}

		try (DBOptions dbOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true);
				RocksDB database = RocksDB.open(dbOptions, databasePath.toString(), descriptors, handles)) {
			database.put(handles.get(LEGACY_COLUMN_FAMILIES.indexOf(RocksDbColumnFamilies.CF_METADATA) + 1),
					LEGACY_KEY, LEGACY_VALUE);
		} finally {
			handles.forEach(ColumnFamilyHandle::close);
			options.forEach(ColumnFamilyOptions::close);
		}
	}

	private void openWithProductionConfigAndVerify(Path databasePath) throws Exception {
		BlockchainDbProperties properties = testProperties(databasePath);
		BlockchainDBConfig config = new BlockchainDBConfig(properties);
		RocksDbColumnFamilies families = config.rocksDbColumnFamilies();

		CoreSnapshotPreOpenInitializer completedPreOpen = mock(CoreSnapshotPreOpenInitializer.class);
		try (RocksDB database = config.blockchainDB(families, completedPreOpen)) {
			assertThat(database.get(families.metadata(), LEGACY_KEY)).isEqualTo(LEGACY_VALUE);
			assertThat(database.get(families.equivocations(), LEGACY_KEY)).isNull();
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
		}
	}

	private BlockchainDbProperties testProperties(Path databasePath) {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(databasePath.toString());
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

	private List<String> listColumnFamilies(Path databasePath) throws RocksDBException {
		try (Options options = new Options()) {
			return RocksDB.listColumnFamilies(options, databasePath.toString()).stream()
					.map(bytes -> new String(bytes, UTF_8))
					.toList();
		}
	}
}
