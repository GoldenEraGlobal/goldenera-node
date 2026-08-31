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

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class DeterministicSha256ProofOfWorkProviderTest {

	private static final String FINGERPRINT =
			"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
	private static final byte[] CANONICAL_INPUT_WITH_NONCE_42 = HexFormat.of()
			.parseHex("0011223344556677000000000000002a");

	@Test
	void matchesVersionOneFixedVector() {
		DeterministicSha256ProofOfWorkProvider provider = provider(FINGERPRINT);

		byte[] result;
		try (ProofOfWorkHasher hasher = provider.openMiningHasher()) {
			result = hasher.hash(CANONICAL_INPUT_WITH_NONCE_42);
		}

		assertThat(HexFormat.of().formatHex(result))
				.isEqualTo("c06e9d2d35fae7571af08ec84b3b2731df737d0534ba9d634e6f55a8c02c29f2");
	}

	@Test
	void minerAndValidatorUseIdenticalProofAndBindFinalBigEndianNonce() {
		DeterministicSha256ProofOfWorkProvider provider = provider(FINGERPRINT);
		byte[] nonce42 = CANONICAL_INPUT_WITH_NONCE_42.clone();
		byte[] nonce43 = CANONICAL_INPUT_WITH_NONCE_42.clone();
		nonce43[nonce43.length - 1] = 0x2b;

		byte[] mined;
		try (ProofOfWorkHasher hasher = provider.openMiningHasher()) {
			mined = hasher.hash(nonce42);
		}
		try (ProofOfWorkHasher verifier = provider.openVerificationHasher(27L, ignored -> Optional.empty())) {
			assertThat(verifier.hash(nonce42)).isEqualTo(mined);
			assertThat(verifier.hash(nonce43)).isNotEqualTo(mined);
		}
	}

	@Test
	void fingerprintSeparatesOtherwiseIdenticalSandboxProofs() {
		String otherFingerprint = "ff" + FINGERPRINT.substring(2);

		assertThat(hash(provider(FINGERPRINT), CANONICAL_INPUT_WITH_NONCE_42))
				.isNotEqualTo(hash(provider(otherFingerprint), CANONICAL_INPUT_WITH_NONCE_42));
	}

	@Test
	void resultIsExactlyThirtyTwoBytesAndUsesNormalUnsignedTargetComparison() {
		byte[] result = hash(provider(FINGERPRINT), CANONICAL_INPUT_WITH_NONCE_42);
		BigInteger resultValue = new BigInteger(1, result);

		assertThat(result).hasSize(ProofOfWorkHasher.HASH_LENGTH_BYTES);
		assertThat(ProofOfWorkTarget.of(resultValue).accepts(result)).isTrue();
		assertThat(ProofOfWorkTarget.of(resultValue.subtract(BigInteger.ONE)).accepts(result)).isFalse();
	}

	@Test
	void rejectsUnsupportedDomainAndMalformedFingerprint() {
		assertThatThrownBy(() -> new DeterministicSha256ProofOfWorkProvider(
				"goldenera-sandbox-pow-v2", FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported deterministic PoW domain");
		assertThatThrownBy(() -> provider(FINGERPRINT.toUpperCase()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lowercase hexadecimal");
		try (ProofOfWorkHasher hasher = provider(FINGERPRINT).openMiningHasher()) {
			assertThatThrownBy(() -> hasher.hash(new byte[Long.BYTES - 1]))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("eight-byte nonce");
		}
	}

	private DeterministicSha256ProofOfWorkProvider provider(String fingerprint) {
		return new DeterministicSha256ProofOfWorkProvider(
				DeterministicSha256ProofOfWorkProvider.DOMAIN_V1,
				fingerprint);
	}

	private byte[] hash(DeterministicSha256ProofOfWorkProvider provider, byte[] input) {
		try (ProofOfWorkHasher hasher = provider.openMiningHasher()) {
			return hasher.hash(input);
		}
	}
}
