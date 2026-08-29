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
package global.goldenera.node.core.storage.blockchain.mempool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.time.Instant;

import global.goldenera.cryptoj.datatypes.Hash;

public final class PersistentMempoolCodec {

	private static final int MAGIC = 0x47454D31;

	public byte[] encode(StoredMempoolTransaction record) {
		try {
			byte[] raw = record.rawSignedTx();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 + raw.length);
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeInt(MAGIC);
				output.writeInt(record.version());
				output.writeInt(record.status().code());
				output.write(record.txHash().toArray());
				output.writeInt(raw.length);
				output.write(raw);
				output.writeLong(record.firstSeenTime().getEpochSecond());
				output.writeInt(record.firstSeenTime().getNano());
				output.writeLong(record.firstSeenHeight());
				output.writeInt(record.admissionReason().code());
				output.writeBoolean(record.replacesTxHash() != null);
				if (record.replacesTxHash() != null) {
					output.write(record.replacesTxHash().toArray());
				}
			}
			return bytes.toByteArray();
		} catch (IOException failure) {
			throw new IllegalStateException("Cannot encode persistent mempool record", failure);
		}
	}

	public StoredMempoolTransaction decode(byte[] encoded) {
		if (encoded == null) {
			throw new IllegalArgumentException("Persistent mempool bytes are required");
		}
		try (ByteArrayInputStream bytes = new ByteArrayInputStream(encoded);
				DataInputStream input = new DataInputStream(bytes)) {
			if (input.readInt() != MAGIC) {
				throw new IllegalArgumentException("Persistent mempool magic is invalid");
			}
			int version = input.readInt();
			StoredMempoolStatus status = StoredMempoolStatus.fromCode(input.readInt());
			Hash txHash = readHash(input);
			int rawLength = input.readInt();
			if (rawLength < 1 || rawLength > StoredMempoolTransaction.MAX_RAW_TX_BYTES) {
				throw new IllegalArgumentException("Persistent mempool raw transaction length is invalid");
			}
			byte[] raw = input.readNBytes(rawLength);
			if (raw.length != rawLength) {
				throw new IllegalArgumentException("Persistent mempool raw transaction is truncated");
			}
			Instant firstSeen = Instant.ofEpochSecond(input.readLong(), input.readInt());
			long firstSeenHeight = input.readLong();
			MempoolAdmissionReason reason = MempoolAdmissionReason.fromCode(input.readInt());
			Hash replaces = input.readBoolean() ? readHash(input) : null;
			if (bytes.available() != 0) {
				throw new IllegalArgumentException("Persistent mempool record has trailing bytes");
			}
			return new StoredMempoolTransaction(
					version, status, txHash, raw, firstSeen, firstSeenHeight, reason, replaces);
		} catch (EOFException failure) {
			throw new IllegalArgumentException("Persistent mempool record is truncated", failure);
		} catch (IOException | RuntimeException failure) {
			if (failure instanceof IllegalArgumentException invalid) {
				throw invalid;
			}
			throw new IllegalArgumentException("Cannot decode persistent mempool record", failure);
		}
	}

	private Hash readHash(DataInputStream input) throws IOException {
		byte[] bytes = input.readNBytes(Hash.SIZE);
		if (bytes.length != Hash.SIZE) {
			throw new EOFException("Persistent mempool hash is truncated");
		}
		return Hash.wrap(bytes);
	}
}
