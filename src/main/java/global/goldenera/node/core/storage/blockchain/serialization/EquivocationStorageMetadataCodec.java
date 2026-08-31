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
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Checksummed fixed-width codec for equivocation repository bookkeeping. */
public final class EquivocationStorageMetadataCodec {

	private static final int MAGIC = 0x47454551; // GEEQ
	private static final int VERSION = 1;
	private static final int CURSOR_BYTES = Long.BYTES + 20;
	private static final int PAYLOAD_BYTES = Integer.BYTES * 2 + Long.BYTES * 6 + 1 + CURSOR_BYTES;
	private static final int ENCODED_BYTES = PAYLOAD_BYTES + Integer.BYTES;

	public byte[] encode(EquivocationStorageMetadata metadata) {
		checkArgument(metadata != null, "Equivocation storage metadata is required");
		ByteBuffer output = ByteBuffer.allocate(ENCODED_BYTES);
		output.putInt(MAGIC);
		output.putInt(VERSION);
		output.putLong(metadata.conflictCount());
		output.putLong(metadata.singleCount());
		output.putLong(metadata.highWatermark());
		output.putLong(metadata.retentionBlocks());
		output.putLong(metadata.retentionGeneration());
		output.putLong(metadata.pruneCutoff());
		byte[] cursor = metadata.pruneCursor();
		output.put((byte) (cursor == null ? 0 : 1));
		if (cursor == null) {
			output.put(new byte[CURSOR_BYTES]);
		} else {
			checkArgument(cursor.length == CURSOR_BYTES, "Invalid equivocation prune cursor");
			output.put(cursor);
		}
		output.putInt(checksum(output.array(), PAYLOAD_BYTES));
		return output.array();
	}

	public EquivocationStorageMetadata decode(byte[] encoded) {
		checkArgument(encoded != null && encoded.length == ENCODED_BYTES,
				"Invalid equivocation storage metadata length");
		ByteBuffer input = ByteBuffer.wrap(encoded);
		checkArgument(input.getInt() == MAGIC, "Invalid equivocation storage metadata magic");
		int version = input.getInt();
		checkArgument(version == VERSION, "Unsupported equivocation storage metadata version: %s", version);
		long conflictCount = input.getLong();
		long singleCount = input.getLong();
		long highWatermark = input.getLong();
		long retentionBlocks = input.getLong();
		long retentionGeneration = input.getLong();
		long pruneCutoff = input.getLong();
		checkArgument(conflictCount >= 0 && singleCount >= 0 && highWatermark >= -1
				&& retentionBlocks >= 0 && retentionGeneration >= 0 && pruneCutoff >= -1,
				"Invalid equivocation storage metadata values");
		byte cursorMarker = input.get();
		checkArgument(cursorMarker == 0 || cursorMarker == 1, "Invalid equivocation prune cursor marker");
		byte[] cursorBytes = new byte[CURSOR_BYTES];
		input.get(cursorBytes);
		int expectedChecksum = input.getInt();
		checkArgument(expectedChecksum == checksum(encoded, PAYLOAD_BYTES),
				"Invalid equivocation storage metadata checksum");
		byte[] cursor = cursorMarker == 0 ? null : cursorBytes;
		return new EquivocationStorageMetadata(
				conflictCount, singleCount, highWatermark,
				retentionBlocks, retentionGeneration, pruneCutoff, cursor);
	}

	private int checksum(byte[] input, int length) {
		CRC32C crc = new CRC32C();
		crc.update(input, 0, length);
		return (int) crc.getValue();
	}

	public record EquivocationStorageMetadata(
			long conflictCount,
			long singleCount,
			long highWatermark,
			long retentionBlocks,
			long retentionGeneration,
			long pruneCutoff,
			byte[] pruneCursor) {

		public EquivocationStorageMetadata {
			if (conflictCount < 0 || singleCount < 0 || highWatermark < -1
					|| retentionBlocks < 0 || retentionGeneration < 0 || pruneCutoff < -1) {
				throw new IllegalArgumentException("Invalid equivocation storage metadata values");
			}
			if (pruneCursor != null && pruneCursor.length != CURSOR_BYTES) {
				throw new IllegalArgumentException("Invalid equivocation prune cursor");
			}
			long expectedCutoff = retentionBlocks == 0 || highWatermark < retentionBlocks
					? -1 : highWatermark - retentionBlocks + 1;
			if (pruneCutoff != expectedCutoff || pruneCutoff < 0 && pruneCursor != null) {
				throw new IllegalArgumentException("Inconsistent equivocation retention metadata");
			}
			pruneCursor = pruneCursor == null ? null : Arrays.copyOf(pruneCursor, pruneCursor.length);
		}

		@Override
		public byte[] pruneCursor() {
			return pruneCursor == null ? null : Arrays.copyOf(pruneCursor, pruneCursor.length);
		}
	}
}
