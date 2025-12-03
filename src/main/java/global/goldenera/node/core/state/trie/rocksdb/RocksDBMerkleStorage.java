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
package global.goldenera.node.core.state.trie.rocksdb;

import static lombok.AccessLevel.PRIVATE;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import com.github.benmanes.caffeine.cache.Cache;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleStorage;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class RocksDBMerkleStorage implements MerkleStorage {

	RocksDB db;
	ColumnFamilyHandle cfHandle;
	Cache<Hash, Bytes> cache;

	Map<Hash, Bytes> pendingPuts = new LinkedHashMap<>();

	public RocksDBMerkleStorage(RocksDB db, RocksDbColumnFamilies columnFamilies, Cache<Hash, Bytes> cache) {
		this.db = db;
		this.cfHandle = columnFamilies.stateTrie();
		this.cache = cache;
	}

	@Override
	public Optional<Bytes> get(Bytes location, Bytes32 hashKey) {
		Hash hash = Hash.wrap(hashKey.toArray());

		if (pendingPuts.containsKey(hash)) {
			return Optional.of(pendingPuts.get(hash));
		}

		Bytes cachedData = cache.getIfPresent(hash);
		if (cachedData != null) {
			return Optional.of(cachedData);
		}

		try {
			byte[] dataBytes = db.get(cfHandle, hash.toArray());

			if (dataBytes != null) {
				Bytes data = Bytes.wrap(dataBytes);
				cache.put(hash, data);
				return Optional.of(data);
			} else {
				log.warn("Trie node NOT FOUND in database: hash={}, location={}", hash, location);
				return Optional.empty();
			}
		} catch (RocksDBException e) {
			log.error("Failed to read from RocksDB Trie: hash={}, location={}", hash, location, e);
			throw new RuntimeException("Failed to read from RocksDB Trie", e);
		}
	}

	@Override
	public void put(Bytes location, Bytes32 hash, Bytes content) {
		pendingPuts.put(Hash.wrap(hash), content);
	}

	public void commitToBatch(@NonNull WriteBatch batch) throws RocksDBException {
		if (pendingPuts.isEmpty()) {
			return;
		}

		for (Map.Entry<Hash, Bytes> entry : pendingPuts.entrySet()) {
			batch.put(cfHandle, entry.getKey().toArray(), entry.getValue().toArray());
		}

		cache.putAll(pendingPuts);

		pendingPuts.clear();
	}

	@Override
	public void commit() {
		throw new UnsupportedOperationException(
				"MerkleStorage commit must now be part of an atomic WriteBatch. Call commitToBatch(WriteBatch) instead.");
	}

	@Override
	public void rollback() {
		pendingPuts.clear();
	}
}