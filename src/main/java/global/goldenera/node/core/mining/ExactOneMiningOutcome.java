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
package global.goldenera.node.core.mining;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.BlockIngestionOutcome;

/** Stable result of an exact-one mining request. */
public record ExactOneMiningOutcome(
		Code code,
		Hash parentHash,
		Long blockHeight,
		Hash blockHash,
		BlockIngestionOutcome.Code ingestionCode) {

	public enum Code {
		ACCEPTED,
		NOT_ELIGIBLE,
		STALE_PARENT,
		TIMED_OUT,
		CANCELLED,
		REJECTED_NOT_PAUSED,
		REJECTED_SYNCING,
		REJECTED_BUSY,
		REJECTED_SHUTDOWN,
		REJECTED_BY_INGESTION,
		RETRYABLE,
		FAILED
	}

	public static ExactOneMiningOutcome of(Code code) {
		return new ExactOneMiningOutcome(code, null, null, null, null);
	}

	public boolean accepted() {
		return code == Code.ACCEPTED;
	}
}
