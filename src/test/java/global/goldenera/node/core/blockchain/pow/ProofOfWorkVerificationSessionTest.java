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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class ProofOfWorkVerificationSessionTest {

	@Test
	void oneWorkerMayReuseTheSessionAndCloseIsIdempotent() {
		AtomicInteger hashes = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		ProofOfWorkVerificationSession session = session(hashes, closes);

		assertThat(session.hash(new byte[] { 1 })).hasSize(ProofOfWorkHasher.HASH_LENGTH_BYTES);
		assertThat(session.hash(new byte[] { 2 })).hasSize(ProofOfWorkHasher.HASH_LENGTH_BYTES);
		session.close();
		session.close();

		assertThat(hashes).hasValue(2);
		assertThat(closes).hasValue(1);
		assertThatThrownBy(() -> session.hash(new byte[] { 3 }))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("closed");
	}

	@Test
	void aSessionCannotMoveToAnotherWorkerThread() throws Exception {
		ProofOfWorkVerificationSession session = session(new AtomicInteger(), new AtomicInteger());
		session.hash(new byte[] { 1 });

		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			assertThatThrownBy(() -> executor.submit(() -> session.hash(new byte[] { 2 })).get())
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasRootCauseMessage("Proof-of-work verification session is thread-confined");
		}
		session.close();
	}

	private ProofOfWorkVerificationSession session(AtomicInteger hashes, AtomicInteger closes) {
		return new ProofOfWorkVerificationSession(
				new ProofOfWorkVerificationContext(Bytes.of(1)),
				ProofOfWorkVerificationMode.RANDOMX_LIGHT,
				ignored -> {
					hashes.incrementAndGet();
					return new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
				},
				closes::incrementAndGet);
	}
}
