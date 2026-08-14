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
package global.goldenera.node.core.state;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.core.state.trie.rocksdb.RocksDBMerkleStorageFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;

public final class PersistentWorldStateTestSupport implements AutoCloseable {

	private final DBOptions dbOptions = new DBOptions()
			.setCreateIfMissing(true)
			.setCreateMissingColumnFamilies(true);
	private final List<ColumnFamilyOptions> columnOptions = List.of(
			new ColumnFamilyOptions(), new ColumnFamilyOptions());
	private final List<ColumnFamilyHandle> handles = new ArrayList<>();
	private final RocksDB database;
	private final WorldStateFactory factory;

	public PersistentWorldStateTestSupport(Path directory) throws RocksDBException {
		RocksDB.loadLibrary();
		List<ColumnFamilyDescriptor> descriptors = List.of(
				new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnOptions.get(0)),
				new ColumnFamilyDescriptor(
						RocksDbColumnFamilies.CF_STATE_TRIE.getBytes(StandardCharsets.UTF_8),
						columnOptions.get(1)));
		database = RocksDB.open(dbOptions, directory.toString(), descriptors, handles);
		RocksDbColumnFamilies columnFamilies = new RocksDbColumnFamilies();
		columnFamilies.addHandle("default", handles.get(0));
		columnFamilies.addHandle(RocksDbColumnFamilies.CF_STATE_TRIE, handles.get(1));
		RocksDBMerkleStorageFactory storageFactory = new RocksDBMerkleStorageFactory(
				database, columnFamilies, Caffeine.newBuilder().build());
		WorldStateSerialization serialization = new WorldStateSerialization();
		factory = new WorldStateFactory(
				storageFactory,
				serialization.rootStateSerializer(),
				serialization.rootStateDeserializer(),
				serialization.balanceSerializer(),
				serialization.balanceDeserializer(),
				serialization.nonceSerializer(),
				serialization.nonceDeserializer(),
				serialization.addressAliasSerializer(),
				serialization.addressAliasDeserializer(),
				serialization.authoritySerializer(),
				serialization.authorityDeserializer(),
				serialization.validatorSerializer(),
				serialization.validatorDeserializer(),
				serialization.bipStateSerializer(),
				serialization.bipStateDeserializer(),
				serialization.networkParamsSerializer(),
				serialization.networkParamsDeserializer(),
				serialization.miningWindowSerializer(),
				serialization.miningWindowDeserializer(),
				serialization.tokenSerializer(),
				serialization.tokenDeserializer());
	}

	public WorldState createEmpty(boolean mining) {
		return factory.create(Hash.wrap(MerkleTrie.EMPTY_TRIE_NODE_HASH), mining);
	}

	public WorldState reload(Hash root, boolean mining) {
		return factory.create(root, mining);
	}

	public WorldStateFactory factory() {
		return factory;
	}

	public Hash persist(WorldState state) throws RocksDBException {
		try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
			state.persistToBatch(batch);
			database.write(writeOptions, batch);
			return state.getFinalStateRoot();
		}
	}

	@Override
	public void close() {
		for (int index = handles.size() - 1; index >= 0; index--) {
			handles.get(index).close();
		}
		database.close();
		columnOptions.forEach(ColumnFamilyOptions::close);
		dbOptions.close();
	}

}
