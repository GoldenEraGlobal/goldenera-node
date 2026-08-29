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
package global.goldenera.node.core.storage.blockchain.journal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import global.goldenera.cryptoj.datatypes.Hash;

public final class LifecycleJournalCodec {

	private static final int MAGIC = 0x47454A31;

	public byte[] encode(LifecycleJournalEntry entry) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(192 + entry.payload().length);
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeInt(MAGIC);
				output.writeInt(entry.version());
				writeUuid(output, entry.epoch());
				output.writeLong(entry.sequence());
				writeUuid(output, entry.eventKey());
				output.writeByte(entry.stream().code());
				output.writeByte(entry.operation().code());
				output.writeBoolean(entry.groupId() != null);
				if (entry.groupId() != null) {
					writeUuid(output, entry.groupId());
				}
				output.writeInt(entry.groupOrdinal());
				output.writeInt(entry.groupSize());
				output.writeLong(entry.height());
				output.write(entry.primaryHash().toArray());
				output.writeBoolean(entry.relatedHash() != null);
				if (entry.relatedHash() != null) {
					output.write(entry.relatedHash().toArray());
				}
				output.writeLong(entry.occurredAt().getEpochSecond());
				output.writeInt(entry.occurredAt().getNano());
				output.writeInt(entry.sourceCode());
				output.writeInt(entry.reasonCode());
				byte[] payload = entry.payload();
				output.writeInt(payload.length);
				output.write(payload);
			}
			return bytes.toByteArray();
		} catch (IOException failure) {
			throw new IllegalStateException("Cannot encode lifecycle journal entry", failure);
		}
	}

	public LifecycleJournalEntry decode(byte[] encoded) {
		if (encoded == null) {
			throw new IllegalArgumentException("Lifecycle journal bytes are required");
		}
		try (ByteArrayInputStream bytes = new ByteArrayInputStream(encoded);
				DataInputStream input = new DataInputStream(bytes)) {
			if (input.readInt() != MAGIC) {
				throw new IllegalArgumentException("Lifecycle journal magic is invalid");
			}
			int version = input.readInt();
			UUID epoch = readUuid(input);
			long sequence = input.readLong();
			UUID eventKey = readUuid(input);
			LifecycleJournalStream stream = LifecycleJournalStream.fromCode(input.readUnsignedByte());
			LifecycleJournalOperation operation = LifecycleJournalOperation.fromCode(input.readUnsignedByte());
			UUID groupId = input.readBoolean() ? readUuid(input) : null;
			int groupOrdinal = input.readInt();
			int groupSize = input.readInt();
			long height = input.readLong();
			Hash primaryHash = Hash.wrap(input.readNBytes(Hash.SIZE));
			Hash relatedHash = input.readBoolean() ? Hash.wrap(input.readNBytes(Hash.SIZE)) : null;
			Instant occurredAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
			int sourceCode = input.readInt();
			int reasonCode = input.readInt();
			int payloadLength = input.readInt();
			if (payloadLength < 0 || payloadLength > LifecycleJournalDraft.MAX_PAYLOAD_BYTES) {
				throw new IllegalArgumentException("Lifecycle journal payload length is invalid");
			}
			byte[] payload = input.readNBytes(payloadLength);
			if (payload.length != payloadLength || bytes.available() != 0) {
				throw new IllegalArgumentException("Lifecycle journal entry is truncated or has trailing bytes");
			}
			return new LifecycleJournalEntry(version, epoch, sequence, eventKey, stream, operation, groupId,
					groupOrdinal, groupSize, height, primaryHash, relatedHash, occurredAt,
					sourceCode, reasonCode, payload);
		} catch (EOFException failure) {
			throw new IllegalArgumentException("Lifecycle journal entry is truncated", failure);
		} catch (IOException | RuntimeException failure) {
			if (failure instanceof IllegalArgumentException invalid) {
				throw invalid;
			}
			throw new IllegalArgumentException("Cannot decode lifecycle journal entry", failure);
		}
	}

	private void writeUuid(DataOutputStream output, UUID value) throws IOException {
		output.writeLong(value.getMostSignificantBits());
		output.writeLong(value.getLeastSignificantBits());
	}

	private UUID readUuid(DataInputStream input) throws IOException {
		return new UUID(input.readLong(), input.readLong());
	}
}
