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
import java.util.function.Function;

/**
 * A height- and mode-specific proof-of-work hashing session.
 *
 * <p>Implementations may own native resources and therefore must be closed.</p>
 */
public final class ProofOfWorkHasher implements AutoCloseable {

	public static final int HASH_LENGTH_BYTES = 32;

	private final Function<byte[], byte[]> hashFunction;
	private final Runnable closeAction;

	public ProofOfWorkHasher(Function<byte[], byte[]> hashFunction, Runnable closeAction) {
		this.hashFunction = Objects.requireNonNull(hashFunction, "hashFunction");
		this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
	}

	public byte[] hash(byte[] input) {
		Objects.requireNonNull(input, "Proof-of-work input must not be null");
		byte[] hash = hashFunction.apply(input);
		if (hash == null) {
			throw new IllegalStateException("Proof-of-work provider returned a null hash");
		}
		if (hash.length != HASH_LENGTH_BYTES) {
			throw new IllegalStateException("Proof-of-work provider returned " + hash.length
					+ " bytes; expected exactly " + HASH_LENGTH_BYTES);
		}
		return hash;
	}

	@Override
	public void close() {
		closeAction.run();
	}
}
