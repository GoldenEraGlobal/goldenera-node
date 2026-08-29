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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.properties.MempoolProperties;

public final class PersistentMempoolBoundedScanner {

	public static final int PAGE_SIZE = 64;
	private PersistentMempoolBoundedScanner() {
	}

	public static List<StoredMempoolTransaction> scanAll(
			PersistentMempoolStore store,
			MempoolProperties properties) {
		List<StoredMempoolTransaction> records = new ArrayList<>();
		scanOrdered(store, properties, records::add);
		return records;
	}

	/**
	 * Performs a lightweight first pass and then loads one raw record at a time in
	 * deterministic first-seen order. Recovery memory is therefore bounded by the
	 * live mempool plus hash/timestamp references, not by a second copy of every raw
	 * transaction.
	 */
	public static int scanOrdered(
			PersistentMempoolStore store,
			MempoolProperties properties,
			Consumer<StoredMempoolTransaction> consumer) {
		if (store == null || properties == null || properties.getMaxSize() == null
				|| properties.getMaxSize() < 1L || consumer == null) {
			throw new IllegalArgumentException("Persistent mempool bounded scan configuration is invalid");
		}
		long configuredMaximum = properties.getMaxSize();
		long allowance = Math.min(PAGE_SIZE, Math.max(1L, configuredMaximum / 10L));
		long countLimit = Math.addExact(configuredMaximum, allowance);
		List<RecordReference> references = new ArrayList<>();
		Hash cursor = null;
		boolean more;
		do {
			MempoolRecoveryPage page = store.scanActive(cursor, PAGE_SIZE);
			for (StoredMempoolTransaction record : page.records()) {
				if (references.size() >= countLimit) {
					throw new IllegalStateException(
							"Persistent mempool recovery record count exceeds bounded configured maximum");
				}
				references.add(new RecordReference(record.txHash(), record.firstSeenTime()));
			}
			cursor = page.nextCursor();
			more = page.hasMore();
		} while (more);
		references.sort(Comparator.comparing(RecordReference::firstSeenTime)
				.thenComparing(RecordReference::hash));
		for (RecordReference reference : references) {
			StoredMempoolTransaction record = store.findActive(reference.hash())
					.orElseThrow(() -> new IllegalStateException(
							"Persistent mempool changed during bounded recovery scan"));
			if (!record.firstSeenTime().equals(reference.firstSeenTime())) {
				throw new IllegalStateException("Persistent mempool metadata changed during recovery scan");
		}
			consumer.accept(record);
		}
		return references.size();
	}

	private record RecordReference(Hash hash, Instant firstSeenTime) {
	}
}
