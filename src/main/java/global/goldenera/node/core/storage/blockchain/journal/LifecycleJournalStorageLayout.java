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
package global.goldenera.node.core.storage.blockchain.journal;

import static java.nio.charset.StandardCharsets.US_ASCII;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;

public final class LifecycleJournalStorageLayout {

	private static final byte[] CANONICAL_HEAD_KEY = "LIFECYCLE_JOURNAL_CANONICAL_HEAD_V1".getBytes(US_ASCII);
	private static final byte[] MEMPOOL_HEAD_KEY = "LIFECYCLE_JOURNAL_MEMPOOL_HEAD_V1".getBytes(US_ASCII);

	private LifecycleJournalStorageLayout() {
	}

	static byte[] headKey(LifecycleJournalStream stream) {
		return stream == LifecycleJournalStream.CANONICAL
				? CANONICAL_HEAD_KEY.clone()
				: MEMPOOL_HEAD_KEY.clone();
	}

	/** Clears operational journal state from a historical snapshot clone. */
	public static void clearForHistoricalSnapshot(
			WriteBatch batch, RocksDbColumnFamilies columnFamilies) throws RocksDBException {
		batch.deleteRange(columnFamilies.lifecycleJournal(), new byte[] { 0 }, new byte[] { 2 });
		batch.delete(columnFamilies.metadata(), CANONICAL_HEAD_KEY);
		batch.delete(columnFamilies.metadata(), MEMPOOL_HEAD_KEY);
	}
}
