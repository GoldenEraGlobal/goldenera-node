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

import static com.google.common.base.Preconditions.checkArgument;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Repository;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence;
import global.goldenera.node.core.storage.blockchain.serialization.EquivocationEvidenceCodec;
import lombok.RequiredArgsConstructor;

/** Persistent node-local repository keyed by block height and validator identity. */
@Repository
@RequiredArgsConstructor
public class EquivocationEvidenceRepository {

	private final RocksDBRepository rocksDBRepository;
	private final RocksDbColumnFamilies columnFamilies;
	private final EquivocationEvidenceCodec codec;

	public Optional<EquivocationEvidence> find(long height, Address identity) {
		try {
			byte[] value = rocksDBRepository.get(columnFamilies.equivocations(), key(height, identity));
			return value == null ? Optional.empty() : Optional.of(codec.decode(value));
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to read equivocation evidence", e);
		}
	}

	public void save(EquivocationEvidence evidence) {
		rocksDBRepository.executeAtomicBatch(batch -> batch.put(
				columnFamilies.equivocations(), key(evidence.height(), evidence.identity()), codec.encode(evidence)));
	}

	public List<EquivocationEvidence> findConflicts(int limit) {
		checkArgument(limit >= 1 && limit <= 1000, "Limit must be in range 1..1000");
		List<EquivocationEvidence> result = new ArrayList<>();
		try (RocksIterator iterator = rocksDBRepository.newIterator(columnFamilies.equivocations())) {
			iterator.seekToFirst();
			while (iterator.isValid() && result.size() < limit) {
				EquivocationEvidence evidence = codec.decode(iterator.value());
				if (evidence.isConflict()) {
					result.add(evidence);
				}
				iterator.next();
			}
			iterator.status();
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to scan equivocation evidence", e);
		}
		return result;
	}

	public long countConflicts() {
		long count = 0;
		try (RocksIterator iterator = rocksDBRepository.newIterator(columnFamilies.equivocations())) {
			iterator.seekToFirst();
			while (iterator.isValid()) {
				if (codec.decode(iterator.value()).isConflict()) {
					count++;
				}
				iterator.next();
			}
			iterator.status();
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to count equivocation evidence", e);
		}
		return count;
	}

	static byte[] key(long height, Address identity) {
		checkArgument(height >= 0, "Evidence height cannot be negative");
		checkArgument(identity != null, "Evidence identity cannot be null");
		return ByteBuffer.allocate(Long.BYTES + Address.SIZE)
				.putLong(height)
				.put(identity.toArray())
				.array();
	}
}
