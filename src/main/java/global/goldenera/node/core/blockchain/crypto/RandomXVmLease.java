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
package global.goldenera.node.core.blockchain.crypto;

import java.util.Objects;

import global.goldenera.randomx.RandomXVM;

/** Node-side idempotent ownership boundary for one native RandomX VM. */
public final class RandomXVmLease implements AutoCloseable {

	private final RandomXVM vm;
	private final Runnable releaseAction;
	private boolean closed;

	RandomXVmLease(RandomXVM vm, Runnable releaseAction) {
		this.vm = Objects.requireNonNull(vm, "vm");
		this.releaseAction = Objects.requireNonNull(releaseAction, "releaseAction");
	}

	public synchronized byte[] calculateHash(byte[] input) {
		if (closed) {
			throw new IllegalStateException("RandomX VM lease is closed");
		}
		return vm.calculateHash(input);
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			vm.close();
		} finally {
			releaseAction.run();
		}
	}
}
