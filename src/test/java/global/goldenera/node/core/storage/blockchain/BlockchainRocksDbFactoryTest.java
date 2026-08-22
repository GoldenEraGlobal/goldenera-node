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
package global.goldenera.node.core.storage.blockchain;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import global.goldenera.node.core.properties.BlockchainDbProperties;

class BlockchainRocksDbFactoryTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void opensCanonicalElevenColumnFamilyLayoutAndPopulatesEveryHandle() throws Exception {
		Path databasePath = temporaryDirectory.resolve("blockchain");
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(testProperties(databasePath)).open(databasePath, families);
		try {
			byte[] key = "state-key".getBytes(UTF_8);
			byte[] value = "state-value".getBytes(UTF_8);
			database.put(families.stateTrie(), key, value);

			assertThat(families.getHandles()).containsOnlyKeys(BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES);
			assertThat(database.get(families.stateTrie(), key)).isEqualTo(value);
			assertThat(listColumnFamilies(databasePath))
					.containsExactlyInAnyOrderElementsOf(BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES);
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	@Test
	void rejectsReusingAColumnFamilyHolderWithoutReplacingLiveHandles() throws Exception {
		Path firstPath = temporaryDirectory.resolve("first");
		Path secondPath = temporaryDirectory.resolve("second");
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(testProperties(firstPath)).open(firstPath, families);
		try {
			ColumnFamilyHandle originalBlocks = families.blocks();

			assertThatThrownBy(() -> new BlockchainRocksDbFactory(testProperties(secondPath))
					.open(secondPath, families))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must be empty");
			assertThat(families.blocks()).isSameAs(originalBlocks);
			assertThat(secondPath).doesNotExist();
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	private List<String> listColumnFamilies(Path databasePath) throws Exception {
		try (Options options = new Options().setCreateIfMissing(false)) {
			return RocksDB.listColumnFamilies(options, databasePath.toString()).stream()
					.map(bytes -> new String(bytes, UTF_8))
					.toList();
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
}
