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

import java.util.UUID;

import global.goldenera.cryptoj.datatypes.Hash;

final class LifecycleJournalValidation {

	private LifecycleJournalValidation() {
	}

	static void validate(
			LifecycleJournalStream stream,
			LifecycleJournalOperation operation,
			UUID groupId,
			int groupOrdinal,
			int groupSize,
			long height,
			Hash relatedHash,
			int sourceCode,
			int reasonCode) {
		if (operation.stream() != stream || groupSize < 1 || groupOrdinal < 0 || groupOrdinal >= groupSize) {
			throw new IllegalArgumentException("Invalid lifecycle journal stream or group metadata");
		}
		if (groupSize > 1 && groupId == null) {
			throw new IllegalArgumentException("Multi-entry lifecycle journal groups require a group id");
		}
		switch (operation) {
			case CONNECT, DISCONNECT -> {
				if (height < 0L || relatedHash == null || sourceCode < 0 || reasonCode != -1) {
					throw new IllegalArgumentException("Canonical block journal metadata is invalid");
				}
			}
			case REORG_COMMIT -> {
				if (groupId == null || height < 0L || relatedHash == null || sourceCode < 0 || reasonCode != -1) {
					throw new IllegalArgumentException("Canonical reorg journal metadata is invalid");
				}
			}
			case PENDING, DROPPED, REORG_READD -> {
				if (height < -1L || relatedHash != null || sourceCode != -1 || reasonCode < 0) {
					throw new IllegalArgumentException("Mempool journal metadata is invalid");
				}
			}
			case REPLACED -> {
				if (height < -1L || relatedHash == null || sourceCode != -1 || reasonCode < 0) {
					throw new IllegalArgumentException("Mempool replacement journal metadata is invalid");
				}
			}
		}
	}
}
