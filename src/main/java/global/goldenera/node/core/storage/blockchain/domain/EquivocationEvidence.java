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
			signedHeader.verify(height, identity);
			Hash hash = signedHeader.blockHash();
			if (!uniqueHashes.add(hash) || previous != null && previous.compareTo(hash) > 0) {
				throw new IllegalArgumentException("Signed evidence headers must be unique and sorted by block hash");
			}
			previous = hash;
		}
	}

	public boolean isConflict() {
		return signedHeaders.size() > 1;
	}

	public record SignedHeader(Bytes canonicalHeader) {
		public SignedHeader {
			if (canonicalHeader == null || canonicalHeader.isEmpty()) {
				throw new IllegalArgumentException("Canonical signed header cannot be empty");
			}
			canonicalHeader = canonicalHeader.copy();
		}

		public static SignedHeader from(BlockHeader header) {
			return new SignedHeader(BlockHeaderEncoder.INSTANCE.encode(header, true));
		}

		public BlockHeader decode() {
			return BlockHeaderDecoder.INSTANCE.decode(canonicalHeader);
		}

		public Hash blockHash() {
			return decode().getHash();
		}

		public Signature signature() {
			return decode().getSignature();
		}

		public BlockHeader verify(long expectedHeight, Address expectedIdentity) {
			BlockHeader header = decode();
			Bytes reencoded = BlockHeaderEncoder.INSTANCE.encode(header, true);
			if (!canonicalHeader.equals(reencoded)
					|| header.getHeight() != expectedHeight
					|| !header.getIdentity().equals(expectedIdentity)
					|| header.getSignature() == null
					|| header.getSignature().equals(Signature.ZERO)
					|| !header.getSignature().validate(BlockHeaderUtil.hashForSigning(header), expectedIdentity)) {
				throw new IllegalArgumentException("Signed header is not valid evidence for height and identity");
			}
			return header;
		}
	}
}
