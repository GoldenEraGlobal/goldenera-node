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
package global.goldenera.node.core.storage.blockchain.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.exceptions.CryptoJException;
import global.goldenera.cryptoj.serialization.blockheader.BlockHeaderDecoder;
import global.goldenera.cryptoj.serialization.blockheader.BlockHeaderEncoder;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;

/**
 * Node-local security evidence for distinct signed block headers produced by
 * one validator identity at one height. This is monitoring data and never
 * participates in consensus or state-root calculation.
 */
public record EquivocationEvidence(
		long height,
		Address identity,
		List<SignedHeader> signedHeaders,
		Instant firstSeenAt,
		Instant lastSeenAt) {

	public EquivocationEvidence {
		signedHeaders = List.copyOf(signedHeaders);
		if (height < 0 || identity == null || identity.equals(Address.ZERO)
				|| signedHeaders.isEmpty() || firstSeenAt == null || lastSeenAt == null
				|| firstSeenAt.isAfter(lastSeenAt)) {
			throw new IllegalArgumentException("Invalid equivocation evidence metadata");
		}
		Set<Hash> uniqueHashes = new HashSet<>();
		Hash previous = null;
		for (SignedHeader signedHeader : signedHeaders) {
			BlockHeader verifiedHeader = signedHeader.verify(height, identity);
			Hash hash = verifiedHeader.getHash();
			if (!uniqueHashes.add(hash) || previous != null && previous.compareTo(hash) > 0) {
				throw new IllegalArgumentException("Signed evidence headers must be unique and sorted by block hash");
			}
			previous = hash;
		}
	}

	public boolean isConflict() {
		return signedHeaders.size() > 1;
	}

	public static final class SignedHeader {
		private final Bytes canonicalHeader;
		private volatile BlockHeader authenticatedHeader;
		private volatile Address authenticatedIdentity;

		public SignedHeader(Bytes canonicalHeader) {
			if (canonicalHeader == null || canonicalHeader.isEmpty()) {
				throw new IllegalArgumentException("Canonical signed header cannot be empty");
			}
			this.canonicalHeader = canonicalHeader.copy();
		}

		public static SignedHeader from(BlockHeader header) {
			return new SignedHeader(BlockHeaderEncoder.INSTANCE.encode(header, true));
		}

		/**
		 * Captures a canonical snapshot, independently recovers its signer exactly once,
		 * and retains that authenticated result for subsequent immutable evidence
		 * construction and encoding. Persisted bytes are independently verified by
		 * {@link #verify} when decoded.
		 */
		public static AuthenticatedSignedHeader authenticate(BlockHeader header) {
			if (header == null) {
				throw new IllegalArgumentException("Signed header is required");
			}
			Signature signature = header.getSignature();
			if (signature == null || signature.equals(Signature.ZERO)) {
				throw new IllegalArgumentException("Signed header must contain a signature");
			}
			Bytes canonical = BlockHeaderEncoder.INSTANCE.encode(header, true);
			BlockHeader canonicalHeader = BlockHeaderDecoder.INSTANCE.decode(canonical);
			if (!signature.equals(canonicalHeader.getSignature())) {
				throw new IllegalArgumentException("Signed header changed while it was captured");
			}
			Address identity;
			try {
				identity = signature.recoverAddress(BlockHeaderUtil.hashForSigning(canonicalHeader));
			} catch (CryptoJException | RuntimeException e) {
				throw new IllegalArgumentException("Signed header signature cannot be authenticated", e);
			}
			if (identity == null || identity.equals(Address.ZERO)) {
				throw new IllegalArgumentException("Signed header identity cannot be zero");
			}
			SignedHeader signedHeader = new SignedHeader(canonical);
			signedHeader.authenticatedIdentity = identity;
			signedHeader.authenticatedHeader = canonicalHeader;
			return new AuthenticatedSignedHeader(identity, signedHeader);
		}

		public Bytes canonicalHeader() {
			return canonicalHeader;
		}

		public BlockHeader decode() {
			return BlockHeaderDecoder.INSTANCE.decode(canonicalHeader);
		}

		public Hash blockHash() {
			BlockHeader cachedHeader = authenticatedHeader;
			return cachedHeader == null ? decode().getHash() : cachedHeader.getHash();
		}

		public long height() {
			BlockHeader cachedHeader = authenticatedHeader;
			return cachedHeader == null ? decode().getHeight() : cachedHeader.getHeight();
		}

		public Signature signature() {
			BlockHeader cachedHeader = authenticatedHeader;
			return cachedHeader == null ? decode().getSignature() : cachedHeader.getSignature();
		}

		public BlockHeader verify(long expectedHeight, Address expectedIdentity) {
			BlockHeader cachedHeader = authenticatedHeader;
			Address cachedIdentity = authenticatedIdentity;
			if (cachedHeader != null && expectedIdentity.equals(cachedIdentity)) {
				if (cachedHeader.getHeight() != expectedHeight) {
					throw new IllegalArgumentException("Signed header is not valid evidence for height and identity");
				}
				return cachedHeader;
			}
			BlockHeader header = decode();
			Bytes reencoded = BlockHeaderEncoder.INSTANCE.encode(header, true);
			Address recoveredIdentity = null;
			if (header.getSignature() != null && !header.getSignature().equals(Signature.ZERO)) {
				try {
					recoveredIdentity = header.getSignature().recoverAddress(BlockHeaderUtil.hashForSigning(header));
				} catch (CryptoJException | RuntimeException ignored) {
					// The common failure below intentionally hides crypto implementation details.
				}
			}
			if (!canonicalHeader.equals(reencoded) || header.getHeight() != expectedHeight
					|| !expectedIdentity.equals(recoveredIdentity)) {
				throw new IllegalArgumentException("Signed header is not valid evidence for height and identity");
			}
			authenticatedIdentity = recoveredIdentity;
			authenticatedHeader = header;
			return header;
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof SignedHeader that
					&& canonicalHeader.equals(that.canonicalHeader);
		}

		@Override
		public int hashCode() {
			return canonicalHeader.hashCode();
		}

		@Override
		public String toString() {
			return "SignedHeader[canonicalHeader=" + canonicalHeader + "]";
		}

		public record AuthenticatedSignedHeader(Address identity, SignedHeader signedHeader) {
			public AuthenticatedSignedHeader {
				if (identity == null || signedHeader == null) {
					throw new IllegalArgumentException("Authenticated signed header is incomplete");
				}
			}
		}
	}
}
