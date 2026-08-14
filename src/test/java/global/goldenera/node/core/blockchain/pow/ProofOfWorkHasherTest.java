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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProofOfWorkHasherTest {

	@Test
	void rejectsNullHash() {
		assertInvalidHash(null, "null hash");
	}

	@Test
	void rejectsShortHash() {
		assertInvalidHash(new byte[31], "31 bytes");
	}

	@Test
	void rejectsLongHash() {
		assertInvalidHash(new byte[33], "33 bytes");
	}

	private void assertInvalidHash(byte[] result, String message) {
		try (ProofOfWorkHasher hasher = new ProofOfWorkHasher(ignored -> result, () -> { })) {
			assertThatThrownBy(() -> hasher.hash(new byte[] { 1 }))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(message);
		}
	}
}
