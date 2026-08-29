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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MempoolMutationBatch(
		UUID batchId,
		List<MempoolStateMutation> mutations,
		MempoolCanonicalProjectionAdvance canonicalProjectionAdvance) {

	/** Above the consensus maximum of 50,000 transactions in one block. */
	public static final int MAX_MUTATIONS = 65_536;
	public static final long MAX_BATCH_BYTES = 64L * 1024L * 1024L;
	/** New RBF bytes can appear as state + admission journal + replaced journal. */
	public static final long MAX_RAW_ADMISSION_BYTES_PER_BATCH = MAX_BATCH_BYTES / 3L;

	public MempoolMutationBatch(UUID batchId, List<MempoolStateMutation> mutations) {
		this(batchId, mutations, null);
	}

	public MempoolMutationBatch {
		Objects.requireNonNull(batchId, "batchId");
		mutations = mutations == null ? List.of() : List.copyOf(mutations);
		if ((mutations.isEmpty() && canonicalProjectionAdvance == null) || mutations.size() > MAX_MUTATIONS) {
			throw new IllegalArgumentException("Persistent mempool mutation batch size is invalid");
		}
		long bytes = 0L;
		for (MempoolStateMutation mutation : mutations) {
			Objects.requireNonNull(mutation, "mutation");
			if (mutation instanceof MempoolStateMutation.UpsertActive upsert) {
				bytes = Math.addExact(bytes, upsert.record().rawSignedTx().length);
			}
			if (mutation.journalDraft() != null) {
				bytes = Math.addExact(bytes, mutation.journalDraft().payload().length);
			}
		}
		if (bytes > MAX_BATCH_BYTES) {
			throw new IllegalArgumentException("Persistent mempool mutation batch exceeds byte limit");
		}
	}
}
