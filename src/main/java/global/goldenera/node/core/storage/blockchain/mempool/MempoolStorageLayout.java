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
package global.goldenera.node.core.storage.blockchain.mempool;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.util.Arrays;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;

public final class MempoolStorageLayout {

	private static final byte[] HEAD_KEY = "PERSISTENT_MEMPOOL_HEAD_V1".getBytes(US_ASCII);
	private static final byte[] CANONICAL_PROJECTION_CURSOR_KEY =
			"MEMPOOL_CANONICAL_PROJECTION_CURSOR_V1".getBytes(US_ASCII);
	private static final byte[] MIN_HASH = new byte[Hash.SIZE];
	private static final byte[] MAX_HASH_EXCLUSIVE = maxHashExclusive();

	private MempoolStorageLayout() {
	}

	static byte[] headKey() {
		return HEAD_KEY.clone();
	}

	static byte[] canonicalProjectionCursorKey() {
		return CANONICAL_PROJECTION_CURSOR_KEY.clone();
	}

	public static void clearForHistoricalSnapshot(
			WriteBatch batch, RocksDbColumnFamilies columnFamilies) throws RocksDBException {
		batch.deleteRange(columnFamilies.mempoolState(), MIN_HASH, MAX_HASH_EXCLUSIVE);
		batch.delete(columnFamilies.metadata(), HEAD_KEY);
		batch.delete(columnFamilies.metadata(), CANONICAL_PROJECTION_CURSOR_KEY);
	}

	static void clearState(WriteBatch batch, RocksDbColumnFamilies columnFamilies) throws RocksDBException {
		batch.deleteRange(columnFamilies.mempoolState(), MIN_HASH, MAX_HASH_EXCLUSIVE);
	}

	private static byte[] maxHashExclusive() {
		byte[] maximum = new byte[Hash.SIZE + 1];
		Arrays.fill(maximum, (byte) 0xFF);
		return maximum;
	}
}
