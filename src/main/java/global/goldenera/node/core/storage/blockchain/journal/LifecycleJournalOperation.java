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

public enum LifecycleJournalOperation {
	CONNECT(0, LifecycleJournalStream.CANONICAL),
	DISCONNECT(1, LifecycleJournalStream.CANONICAL),
	REORG_COMMIT(2, LifecycleJournalStream.CANONICAL),
	PENDING(3, LifecycleJournalStream.MEMPOOL),
	REPLACED(4, LifecycleJournalStream.MEMPOOL),
	DROPPED(5, LifecycleJournalStream.MEMPOOL),
	REORG_READD(6, LifecycleJournalStream.MEMPOOL);

	private final int code;
	private final LifecycleJournalStream stream;

	LifecycleJournalOperation(int code, LifecycleJournalStream stream) {
		this.code = code;
		this.stream = stream;
	}

	public int code() {
		return code;
	}

	public LifecycleJournalStream stream() {
		return stream;
	}

	public static LifecycleJournalOperation fromCode(int code) {
		for (LifecycleJournalOperation operation : values()) {
			if (operation.code == code) {
				return operation;
			}
		}
		throw new IllegalArgumentException("Unknown lifecycle journal operation code " + code);
	}
}
