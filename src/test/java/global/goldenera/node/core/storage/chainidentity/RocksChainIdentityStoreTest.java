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
package global.goldenera.node.core.storage.chainidentity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;

class RocksChainIdentityStoreTest {

	private static final StoredChainIdentity IDENTITY = new StoredChainIdentity(
			1, 1, "sandbox", "0x" + "a".repeat(64), "b".repeat(64));
	private static final StoredChainIdentity OTHER_IDENTITY = new StoredChainIdentity(
			1, 1, "other", "0x" + "c".repeat(64), "d".repeat(64));

	@TempDir
	Path tempDirectory;

	@Test
	void persistsInMetadataAndNeverOverwritesExistingIdentity() throws Exception {
		try (RocksFixture fixture = RocksFixture.open(tempDirectory.resolve("rocks"))) {
			assertThat(fixture.store.find()).isEmpty();

			fixture.store.bindIfAbsent(IDENTITY);
			fixture.store.bindIfAbsent(OTHER_IDENTITY);

			assertThat(fixture.store.find()).contains(IDENTITY);
			assertThat(fixture.database.get(fixture.families.metadata(), RocksChainIdentityStore.STORAGE_KEY))
					.isEqualTo(StoredChainIdentityCodec.encode(IDENTITY));
		}
	}

	@Test
	void malformedMetadataFailsClosed() throws Exception {
		try (RocksFixture fixture = RocksFixture.open(tempDirectory.resolve("malformed"))) {
			fixture.database.put(fixture.families.metadata(), RocksChainIdentityStore.STORAGE_KEY, new byte[] { 1, 2 });

			assertThatThrownBy(fixture.store::find)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("metadata");
		}
	}

	private static final class RocksFixture implements AutoCloseable {
		private final RocksDB database;
		private final List<ColumnFamilyHandle> handles;
		private final List<ColumnFamilyOptions> options;
		private final DBOptions dbOptions;
		private final RocksDbColumnFamilies families;
		private final RocksChainIdentityStore store;

		private RocksFixture(
				RocksDB database,
				List<ColumnFamilyHandle> handles,
				List<ColumnFamilyOptions> options,
				DBOptions dbOptions,
				RocksDbColumnFamilies families,
				RocksChainIdentityStore store) {
			this.database = database;
			this.handles = handles;
			this.options = options;
			this.dbOptions = dbOptions;
			this.families = families;
			this.store = store;
		}

		private static RocksFixture open(Path path) throws Exception {
			RocksDB.loadLibrary();
			List<ColumnFamilyOptions> options = List.of(new ColumnFamilyOptions(), new ColumnFamilyOptions());
			List<ColumnFamilyDescriptor> descriptors = List.of(
					new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, options.get(0)),
					new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_METADATA.getBytes(UTF_8), options.get(1)));
			List<ColumnFamilyHandle> handles = new ArrayList<>();
			DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
			RocksDB database = RocksDB.open(dbOptions, path.toString(), descriptors, handles);
			RocksDbColumnFamilies families = new RocksDbColumnFamilies();
			families.addHandle("default", handles.get(0));
			families.addHandle(RocksDbColumnFamilies.CF_METADATA, handles.get(1));
			RocksDBRepository repository = new RocksDBRepository(database, families);
			return new RocksFixture(
					database,
					handles,
					options,
					dbOptions,
					families,
					new RocksChainIdentityStore(repository, families));
		}

		@Override
		public void close() {
			handles.forEach(ColumnFamilyHandle::close);
			database.close();
			options.forEach(ColumnFamilyOptions::close);
			dbOptions.close();
		}
	}
}
