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
package global.goldenera.node.core.sync;

import lombok.NonNull;

/** Stable result of submitting a full block to the normal ingestion path. */
public record BlockIngestionOutcome(@NonNull Code code) {

	public enum Code {
		ACCEPTED,
		REJECTED_STATELESS,
		REJECTED_CONTEXTUAL,
		REJECTED_CONSENSUS_POLICY,
		REJECTED_EXECUTION,
		REJECTED_STATE_ROOT,
		ORPHAN_BUFFERED,
		GAP_DETECTED,
		ALREADY_EXISTS,
		INTERNAL_FAILURE
	}

	public static BlockIngestionOutcome of(Code code) {
		return new BlockIngestionOutcome(code);
	}

	public boolean accepted() {
		return code == Code.ACCEPTED;
	}
}
