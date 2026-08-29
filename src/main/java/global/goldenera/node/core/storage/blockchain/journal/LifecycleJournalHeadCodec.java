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

import java.nio.ByteBuffer;
import java.util.UUID;

import global.goldenera.cryptoj.datatypes.Hash;

final class LifecycleJournalHeadCodec {

	private static final int MAGIC = 0x47454831;
	private static final int VERSION = 1;
	private static final int ENCODED_SIZE = Integer.BYTES * 3 + Long.BYTES * 5 + Hash.SIZE;

	byte[] encode(LifecycleJournalHead head) {
		return ByteBuffer.allocate(ENCODED_SIZE)
				.putInt(MAGIC)
				.putInt(VERSION)
				.putInt(head.stream().code())
				.putLong(head.epoch().getMostSignificantBits())
				.putLong(head.epoch().getLeastSignificantBits())
				.putLong(head.sequence())
				.putLong(head.floorSequence())
				.putLong(head.anchorHeight())
				.put(head.anchorHash().toArray())
				.array();
	}

	LifecycleJournalHead decode(byte[] encoded) {
		if (encoded == null || encoded.length != ENCODED_SIZE) {
			throw new IllegalArgumentException("Lifecycle journal head has an invalid size");
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		if (input.getInt() != MAGIC || input.getInt() != VERSION) {
			throw new IllegalArgumentException("Lifecycle journal head format is invalid");
		}
		LifecycleJournalStream stream = LifecycleJournalStream.fromCode(input.getInt());
		UUID epoch = new UUID(input.getLong(), input.getLong());
		long sequence = input.getLong();
		long floorSequence = input.getLong();
		long anchorHeight = input.getLong();
		byte[] hash = new byte[Hash.SIZE];
		input.get(hash);
		return new LifecycleJournalHead(stream, epoch, sequence, floorSequence, anchorHeight, Hash.wrap(hash));
	}
}
