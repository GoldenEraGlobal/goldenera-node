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

import java.util.Optional;

import org.rocksdb.RocksDBException;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloor;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorCodec;

/** Reads the immutable checkpoint floor installed in blockchain metadata. */
@Component
public final class CoreSnapshotCheckpointFloorStore {

	private final RocksDBRepository repository;
	private final RocksDbColumnFamilies columnFamilies;

	public CoreSnapshotCheckpointFloorStore(
			RocksDBRepository repository,
			RocksDbColumnFamilies columnFamilies) {
		this.repository = repository;
		this.columnFamilies = columnFamilies;
	}

	public Optional<CoreSnapshotCheckpointFloor> load() {
		byte[] encoded;
		try {
			encoded = repository.get(
					columnFamilies.metadata(), CoreSnapshotCheckpointFloorCodec.STORAGE_KEY);
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to read snapshot checkpoint floor metadata", e);
		}
		if (encoded == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(CoreSnapshotCheckpointFloorCodec.decode(encoded));
		} catch (RuntimeException e) {
			throw new IllegalStateException("Snapshot checkpoint floor metadata is invalid", e);
		}
	}

	public boolean containsStateTrieNode(Hash hash) {
		try {
			return repository.get(columnFamilies.stateTrie(), hash.toArray()) != null;
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to read snapshot checkpoint trie root", e);
		}
	}
}
