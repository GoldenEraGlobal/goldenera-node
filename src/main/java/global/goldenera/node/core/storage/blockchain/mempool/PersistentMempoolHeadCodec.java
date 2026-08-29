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

import java.nio.ByteBuffer;
import java.util.UUID;

final class PersistentMempoolHeadCodec {

	private static final int MAGIC = 0x47454D48;
	private static final int ENCODED_SIZE = Integer.BYTES * 2 + Long.BYTES * 5 + Byte.BYTES;

	byte[] encode(PersistentMempoolHead head) {
		ByteBuffer output = ByteBuffer.allocate(ENCODED_SIZE)
				.putInt(MAGIC)
				.putInt(head.version())
				.putLong(head.epoch().getMostSignificantBits())
				.putLong(head.epoch().getLeastSignificantBits())
				.putLong(head.batchSequence())
				.put((byte) (head.lastBatchId() == null ? 0 : 1));
		if (head.lastBatchId() == null) {
			output.putLong(0L).putLong(0L);
		} else {
			output.putLong(head.lastBatchId().getMostSignificantBits())
					.putLong(head.lastBatchId().getLeastSignificantBits());
		}
		return output.array();
	}

	PersistentMempoolHead decode(byte[] encoded) {
		if (encoded == null || encoded.length != ENCODED_SIZE) {
			throw new IllegalArgumentException("Persistent mempool head size is invalid");
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		if (input.getInt() != MAGIC) {
			throw new IllegalArgumentException("Persistent mempool head magic is invalid");
		}
		int version = input.getInt();
		UUID epoch = new UUID(input.getLong(), input.getLong());
		long sequence = input.getLong();
		byte hasBatchId = input.get();
		long most = input.getLong();
		long least = input.getLong();
		if (hasBatchId != 0 && hasBatchId != 1) {
			throw new IllegalArgumentException("Persistent mempool head batch marker is invalid");
		}
		UUID batchId = hasBatchId == 0 ? null : new UUID(most, least);
		return new PersistentMempoolHead(version, epoch, sequence, batchId);
	}
}
