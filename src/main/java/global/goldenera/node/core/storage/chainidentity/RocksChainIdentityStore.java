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

import java.util.Optional;

import org.rocksdb.RocksDBException;
import org.springframework.stereotype.Repository;

import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RocksChainIdentityStore implements ChainIdentityStore {

	static final byte[] STORAGE_KEY = "CHAIN_IDENTITY_V1".getBytes(UTF_8);

	private final RocksDBRepository repository;
	private final RocksDbColumnFamilies columnFamilies;

	@Override
	public String name() {
		return "RocksDB";
	}

	@Override
	public Optional<StoredChainIdentity> find() {
		try {
			byte[] encoded = repository.get(columnFamilies.metadata(), STORAGE_KEY);
			return encoded == null
					? Optional.empty()
					: Optional.of(StoredChainIdentityCodec.decode(encoded));
		} catch (RocksDBException e) {
			throw new ChainStorageGuardException("Failed to read RocksDB chain identity", e);
		}
	}

	@Override
	public synchronized void bindIfAbsent(StoredChainIdentity identity) {
		if (find().isPresent()) {
			return;
		}
		byte[] encoded = StoredChainIdentityCodec.encode(identity);
		try {
			repository.executeAtomicBatch(batch -> batch.put(columnFamilies.metadata(), STORAGE_KEY, encoded));
		} catch (RuntimeException e) {
			throw new ChainStorageGuardException("Failed to bind RocksDB chain identity", e);
		}
	}
}
