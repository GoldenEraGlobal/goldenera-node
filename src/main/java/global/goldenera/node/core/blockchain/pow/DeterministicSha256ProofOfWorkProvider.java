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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;

/**
 * Deterministic sandbox-only proof-of-work algorithm.
 *
 * <p>The version-1 preimage is unambiguous:</p>
 *
 * <pre>
 * uint32be(domain length) || UTF-8 domain
 * || uint32be(manifest fingerprint length) || 32 fingerprint bytes
 * || uint64be(canonical PoW input length) || canonical PoW input
 * </pre>
 *
 * <p>The canonical PoW input is the same {@code BlockHeaderUtil.powInput}
 * byte sequence used by RandomX and includes the big-endian nonce as its final
 * eight bytes. The manifest fingerprint prevents proofs from being replayed on
 * another sandbox chain.</p>
 */
public final class DeterministicSha256ProofOfWorkProvider implements ProofOfWorkProvider {

	public static final String DOMAIN_V1 = "goldenera-sandbox-pow-v1";

	private static final HexFormat LOWER_HEX = HexFormat.of();

	private final byte[] framingPrefix;

	public DeterministicSha256ProofOfWorkProvider(String domain, String manifestFingerprint) {
		if (!DOMAIN_V1.equals(domain)) {
			throw new IllegalArgumentException("Unsupported deterministic PoW domain: " + domain);
		}
		byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
		byte[] fingerprintBytes = decodeFingerprint(manifestFingerprint);
		this.framingPrefix = ByteBuffer.allocate(
				Integer.BYTES + domainBytes.length + Integer.BYTES + fingerprintBytes.length)
				.putInt(domainBytes.length)
				.put(domainBytes)
				.putInt(fingerprintBytes.length)
				.put(fingerprintBytes)
				.array();
	}

	public static DeterministicSha256ProofOfWorkProvider from(SandboxManifestContext manifestContext) {
		Objects.requireNonNull(manifestContext, "manifestContext");
		return new DeterministicSha256ProofOfWorkProvider(
				manifestContext.manifest().pow().deterministic().domain(),
				manifestContext.fingerprint());
	}

	@Override
	public void prepareForMining(long height) {
		// No height-specific resources are needed.
	}

	@Override
	public ProofOfWorkHasher openMiningHasher() {
		return hasher();
	}

	@Override
	public ProofOfWorkVerificationContext verificationContext(long height,
			Function<Long, Optional<byte[]>> seedBlockProvider) {
		Objects.requireNonNull(seedBlockProvider, "seedBlockProvider");
		return ProofOfWorkProvider.super.verificationContext(height, seedBlockProvider);
	}

	@Override
	public ProofOfWorkVerificationSession openVerificationSession(ProofOfWorkVerificationContext context) {
		MessageDigest digest = sha256();
		return new ProofOfWorkVerificationSession(
				context,
				ProofOfWorkVerificationMode.DETERMINISTIC_SHA256_V1,
				input -> hash(digest, input),
				() -> { });
	}

	@Override
	public boolean isInitializationInProgress() {
		return false;
	}

	private ProofOfWorkHasher hasher() {
		MessageDigest digest = sha256();
		return new ProofOfWorkHasher(input -> hash(digest, input), () -> { });
	}

	private byte[] hash(MessageDigest digest, byte[] canonicalPowInput) {
		if (canonicalPowInput.length < Long.BYTES) {
			throw new IllegalArgumentException("Canonical proof-of-work input must include an eight-byte nonce");
		}
		digest.reset();
		digest.update(framingPrefix);
		digest.update(ByteBuffer.allocate(Long.BYTES).putLong(canonicalPowInput.length).array());
		return digest.digest(canonicalPowInput);
	}

	private MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Required SHA-256 algorithm is unavailable", e);
		}
	}

	private byte[] decodeFingerprint(String fingerprint) {
		Objects.requireNonNull(fingerprint, "manifestFingerprint");
		if (fingerprint.length() != ProofOfWorkHasher.HASH_LENGTH_BYTES * 2
				|| !fingerprint.equals(fingerprint.toLowerCase(Locale.ROOT))) {
			throw new IllegalArgumentException("Manifest fingerprint must be 64 lowercase hexadecimal characters");
		}
		try {
			byte[] decoded = LOWER_HEX.parseHex(fingerprint);
			if (decoded.length != ProofOfWorkHasher.HASH_LENGTH_BYTES) {
				throw new IllegalArgumentException("Manifest fingerprint must decode to 32 bytes");
			}
			return decoded;
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Manifest fingerprint must be 64 lowercase hexadecimal characters", e);
		}
	}
}
