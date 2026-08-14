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

import java.math.BigInteger;
import java.util.Objects;

/** Unsigned 256-bit proof-of-work target shared by mining and validation. */
public final class ProofOfWorkTarget {

	private static final int TARGET_LENGTH_BYTES = 32;

	private final BigInteger value;
	private final byte[] bytes;

	private ProofOfWorkTarget(BigInteger value, byte[] bytes) {
		this.value = value;
		this.bytes = bytes;
	}

	public static ProofOfWorkTarget of(BigInteger value) {
		Objects.requireNonNull(value, "target");
		if (value.signum() < 0 || value.bitLength() > TARGET_LENGTH_BYTES * Byte.SIZE) {
			throw new IllegalArgumentException("Proof-of-work target must be an unsigned 256-bit integer");
		}

		byte[] target = new byte[TARGET_LENGTH_BYTES];
		byte[] encoded = value.toByteArray();
		int sourceOffset = Math.max(0, encoded.length - TARGET_LENGTH_BYTES);
		int length = encoded.length - sourceOffset;
		System.arraycopy(encoded, sourceOffset, target, TARGET_LENGTH_BYTES - length, length);
		return new ProofOfWorkTarget(value, target);
	}

	public BigInteger value() {
		return value;
	}

	public boolean accepts(byte[] hash) {
		Objects.requireNonNull(hash, "Proof-of-work hash must not be null");
		if (hash.length != ProofOfWorkHasher.HASH_LENGTH_BYTES) {
			throw new IllegalArgumentException("Proof-of-work hash must contain exactly "
					+ ProofOfWorkHasher.HASH_LENGTH_BYTES + " bytes");
		}
		for (int i = 0; i < TARGET_LENGTH_BYTES; i++) {
			int hashByte = hash[i] & 0xff;
			int targetByte = bytes[i] & 0xff;
			if (hashByte < targetByte) {
				return true;
			}
			if (hashByte > targetByte) {
				return false;
			}
		}
		return true;
	}
}
