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

public record LifecycleJournalDraft(
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

	public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

	public LifecycleJournalDraft {
		Objects.requireNonNull(stream, "stream");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(primaryHash, "primaryHash");
		Objects.requireNonNull(occurredAt, "occurredAt");
		if (height < -1L) {
			throw new IllegalArgumentException("Lifecycle journal height cannot be below -1");
		}
		LifecycleJournalValidation.validate(
				stream, operation, groupId, groupOrdinal, groupSize, height, relatedHash, sourceCode, reasonCode);
		payload = payload == null ? new byte[0] : payload.clone();
		if (payload.length > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Lifecycle journal payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
		}
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}

	public static LifecycleJournalDraft connect(
			UUID groupId, int ordinal, int groupSize, long height, Hash blockHash, Hash parentHash,
			Instant occurredAt, int sourceCode, byte[] payload) {
		return new LifecycleJournalDraft(LifecycleJournalStream.CANONICAL, LifecycleJournalOperation.CONNECT,
				groupId, ordinal, groupSize, height, blockHash, parentHash, occurredAt, sourceCode, -1, payload);
	}

	public static LifecycleJournalDraft disconnect(
			UUID groupId, int ordinal, int groupSize, long height, Hash blockHash, Hash parentHash,
			Instant occurredAt, int sourceCode) {
		return new LifecycleJournalDraft(LifecycleJournalStream.CANONICAL, LifecycleJournalOperation.DISCONNECT,
				groupId, ordinal, groupSize, height, blockHash, parentHash, occurredAt, sourceCode, -1, null);
	}

	public static LifecycleJournalDraft reorgCommit(
			UUID groupId, int ordinal, int groupSize, long newHeight, Hash newHeadHash, Hash oldHeadHash,
			Instant occurredAt, int sourceCode) {
		return new LifecycleJournalDraft(LifecycleJournalStream.CANONICAL, LifecycleJournalOperation.REORG_COMMIT,
				groupId, ordinal, groupSize, newHeight, newHeadHash, oldHeadHash, occurredAt, sourceCode, -1, null);
	}

	public static LifecycleJournalDraft pending(
			Hash txHash, long firstSeenHeight, Instant occurredAt, int reasonCode, byte[] payload) {
		return mempool(LifecycleJournalOperation.PENDING, txHash, null, firstSeenHeight,
				occurredAt, reasonCode, payload);
	}

	public static LifecycleJournalDraft replaced(
			Hash txHash, Hash replacementHash, long firstSeenHeight, Instant occurredAt,
			int reasonCode, byte[] payload) {
		return mempool(LifecycleJournalOperation.REPLACED, txHash, replacementHash, firstSeenHeight,
				occurredAt, reasonCode, payload);
	}

	public static LifecycleJournalDraft dropped(
			Hash txHash, long firstSeenHeight, Instant occurredAt, int reasonCode, byte[] payload) {
		return mempool(LifecycleJournalOperation.DROPPED, txHash, null, firstSeenHeight,
				occurredAt, reasonCode, payload);
	}

	public static LifecycleJournalDraft reorgReadd(
			Hash txHash, long firstSeenHeight, Instant occurredAt, int reasonCode, byte[] payload) {
		return mempool(LifecycleJournalOperation.REORG_READD, txHash, null, firstSeenHeight,
				occurredAt, reasonCode, payload);
	}

	private static LifecycleJournalDraft mempool(
			LifecycleJournalOperation operation, Hash txHash, Hash relatedHash, long firstSeenHeight, Instant occurredAt,
			int reasonCode, byte[] payload) {
		return new LifecycleJournalDraft(LifecycleJournalStream.MEMPOOL, operation, null, 0, 1, firstSeenHeight,
				txHash, relatedHash, occurredAt, -1, reasonCode, payload);
	}
}
