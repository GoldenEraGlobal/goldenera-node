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
import java.util.Optional;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;

/**
 * Consensus proof-of-work provider shared by mining and validation.
 */
public interface ProofOfWorkProvider {

	void prepareForMining(long height);

	ProofOfWorkHasher openMiningHasher();

	default ProofOfWorkVerificationContext verificationContext(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		Objects.requireNonNull(seedBlockProvider, "seedBlockProvider");
		return new ProofOfWorkVerificationContext(Bytes.EMPTY);
	}

	ProofOfWorkVerificationSession openVerificationSession(ProofOfWorkVerificationContext context);

	default ProofOfWorkHasher openVerificationHasher(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		ProofOfWorkVerificationSession session = openVerificationSession(
				verificationContext(height, seedBlockProvider));
		return new ProofOfWorkHasher(session::hash, session::close);
	}

	boolean isInitializationInProgress();

	/** Maximum useful verifier concurrency for this provider and runtime. */
	default int verificationConcurrencyLimit(int availableProcessors) {
		return Math.max(1, availableProcessors);
	}
}
