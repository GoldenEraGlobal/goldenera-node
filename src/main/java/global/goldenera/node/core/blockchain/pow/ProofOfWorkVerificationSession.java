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
package global.goldenera.node.core.blockchain.pow;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** One reusable, thread-confined proof-of-work verification session. */
public final class ProofOfWorkVerificationSession implements AutoCloseable {

	private final ProofOfWorkVerificationContext context;
	private final ProofOfWorkVerificationMode mode;
	private final Function<byte[], byte[]> hashFunction;
	private final Runnable closeAction;
	private final AtomicReference<Thread> owner = new AtomicReference<>();
	private final AtomicBoolean closed = new AtomicBoolean();

	public ProofOfWorkVerificationSession(
			ProofOfWorkVerificationContext context,
			ProofOfWorkVerificationMode mode,
			Function<byte[], byte[]> hashFunction,
			Runnable closeAction) {
		this.context = Objects.requireNonNull(context, "context");
		this.mode = Objects.requireNonNull(mode, "mode");
		this.hashFunction = Objects.requireNonNull(hashFunction, "hashFunction");
		this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
	}

	public ProofOfWorkVerificationContext context() {
		return context;
	}

	public ProofOfWorkVerificationMode mode() {
		return mode;
	}

	public byte[] hash(byte[] input) {
		Objects.requireNonNull(input, "Proof-of-work input must not be null");
		if (closed.get()) {
			throw new IllegalStateException("Proof-of-work verification session is closed");
		}
		Thread current = Thread.currentThread();
		Thread existing = owner.compareAndExchange(null, current);
		if (existing != null && existing != current) {
			throw new IllegalStateException("Proof-of-work verification session is thread-confined");
		}
		byte[] hash = hashFunction.apply(input);
		if (hash == null || hash.length != ProofOfWorkHasher.HASH_LENGTH_BYTES) {
			throw new IllegalStateException("Proof-of-work provider returned an invalid verification hash");
		}
		return hash;
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			closeAction.run();
		}
	}
}
