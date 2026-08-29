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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import global.goldenera.cryptoj.datatypes.Hash;

public record LifecycleJournalEntry(
		int version,
		UUID epoch,
		long sequence,
		UUID eventKey,
		LifecycleJournalStream stream,
		LifecycleJournalOperation operation,
		UUID groupId,
		int groupOrdinal,
		int groupSize,
		long height,
		Hash primaryHash,
		Hash relatedHash,
		Instant occurredAt,
		int sourceCode,
		int reasonCode,
		byte[] payload) {

	public static final int CURRENT_VERSION = 1;

	public LifecycleJournalEntry {
		if (version != CURRENT_VERSION) {
			throw new IllegalArgumentException("Unsupported lifecycle journal entry version " + version);
		}
		Objects.requireNonNull(epoch, "epoch");
		if (sequence < 1L) {
			throw new IllegalArgumentException("Lifecycle journal sequence must be positive");
		}
		Objects.requireNonNull(eventKey, "eventKey");
		Objects.requireNonNull(stream, "stream");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(primaryHash, "primaryHash");
		Objects.requireNonNull(occurredAt, "occurredAt");
		LifecycleJournalValidation.validate(
				stream, operation, groupId, groupOrdinal, groupSize, height, relatedHash, sourceCode, reasonCode);
		payload = payload == null ? new byte[0] : payload.clone();
		if (payload.length > LifecycleJournalDraft.MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Lifecycle journal payload is too large");
		}
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}
}
