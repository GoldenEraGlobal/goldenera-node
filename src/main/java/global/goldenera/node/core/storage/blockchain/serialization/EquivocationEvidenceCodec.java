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
package global.goldenera.node.core.storage.blockchain.serialization;

import static com.google.common.base.Preconditions.checkArgument;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence.SignedHeader;

/** Versioned, fixed-width codec for node-local equivocation evidence. */
@Component
public class EquivocationEvidenceCodec {

	static final int VERSION = 1;
	static final int HEADER_BYTES = Hash.SIZE + Signature.SIZE;
	static final int FIXED_BYTES = Integer.BYTES + Long.BYTES + Address.SIZE
			+ Long.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES;

	public byte[] encode(EquivocationEvidence evidence) {
		checkArgument(evidence.height() >= 0, "Evidence height cannot be negative");
		checkArgument(evidence.identity() != null, "Evidence identity cannot be null");
		checkArgument(!evidence.signedHeaders().isEmpty(), "Evidence must contain a signed header");
		ByteBuffer output = ByteBuffer.allocate(FIXED_BYTES + evidence.signedHeaders().size() * HEADER_BYTES);
		output.putInt(VERSION);
		output.putLong(evidence.height());
		output.put(evidence.identity().toArray());
		putInstant(output, evidence.firstSeenAt());
		putInstant(output, evidence.lastSeenAt());
		output.putInt(evidence.signedHeaders().size());
		for (SignedHeader header : evidence.signedHeaders()) {
			output.put(header.blockHash().toArray());
			output.put(header.signature().toArray());
		}
		return output.array();
	}

	public EquivocationEvidence decode(byte[] encoded) {
		checkArgument(encoded != null && encoded.length >= FIXED_BYTES + HEADER_BYTES,
				"Invalid equivocation evidence length");
		ByteBuffer input = ByteBuffer.wrap(encoded);
		int version = input.getInt();
		checkArgument(version == VERSION, "Unsupported equivocation evidence version: %s", version);
		long height = input.getLong();
		byte[] identityBytes = new byte[Address.SIZE];
		input.get(identityBytes);
		Instant firstSeenAt = getInstant(input);
		Instant lastSeenAt = getInstant(input);
		int count = input.getInt();
		checkArgument(count > 0, "Evidence must contain a signed header");
		checkArgument(input.remaining() == Math.multiplyExact(count, HEADER_BYTES),
				"Invalid equivocation evidence payload length");
		List<SignedHeader> headers = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			byte[] hash = new byte[Hash.SIZE];
			byte[] signature = new byte[Signature.SIZE];
			input.get(hash);
			input.get(signature);
			headers.add(new SignedHeader(Hash.wrap(hash), Signature.wrap(signature)));
		}
		return new EquivocationEvidence(height, Address.wrap(identityBytes), headers, firstSeenAt, lastSeenAt);
	}

	private void putInstant(ByteBuffer output, Instant instant) {
		output.putLong(instant.getEpochSecond());
		output.putInt(instant.getNano());
	}

	private Instant getInstant(ByteBuffer input) {
		return Instant.ofEpochSecond(input.getLong(), input.getInt());
	}
}
