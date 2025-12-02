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

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Getter
public class RocksDBRepository {

	private final RocksDB blockchainDB;
	private final RocksDbColumnFamilies columnFamilies;

	public byte[] get(ColumnFamilyHandle cf, byte[] key) throws RocksDBException {
		return blockchainDB.get(cf, key);
	}

	public void write(WriteBatch batch, boolean sync) throws RocksDBException {
		try (WriteOptions options = new WriteOptions().setSync(sync)) {
			blockchainDB.write(options, batch);
		}
	}

	public RocksIterator newIterator(ColumnFamilyHandle cf) {
		return blockchainDB.newIterator(cf);
	}

	@FunctionalInterface
	public interface BatchOperation {
		void execute(WriteBatch batch) throws Exception;
	}

	/**
	 * Executes a set of operations in a single atomic RocksDB batch.
	 * Handles the creation of WriteBatch and the final write.
	 */
	public void executeAtomicBatch(BatchOperation operation) {
		try (WriteBatch batch = new WriteBatch()) {
			operation.execute(batch);
			write(batch, true);
		} catch (Exception e) {
			throw new RuntimeException("Atomic batch execution failed", e);
		}
	}
}