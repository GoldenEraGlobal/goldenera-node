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
package global.goldenera.node.core.sync.snapshot.bootstrap;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import global.goldenera.cryptoj.datatypes.Hash;

/** Stable fixed-layout metadata encoding used by prepared snapshot databases. */
public final class CoreSnapshotCheckpointFloorCodec {

	public static final byte[] STORAGE_KEY = "SNAPSHOT_CHECKPOINT_FLOOR_V1".getBytes(StandardCharsets.US_ASCII);
	private static final int MAGIC = 0x4745464c; // GEFL
	private static final int VERSION = 1;
	private static final int MAX_DIFFICULTY_BYTES = 64;

	private CoreSnapshotCheckpointFloorCodec() {
	}

	public static byte[] encode(CoreSnapshotCheckpointFloor floor) {
		byte[] difficulty = floor.cumulativeDifficulty().toByteArray();
		if (difficulty.length == 0 || difficulty.length > MAX_DIFFICULTY_BYTES) {
			throw new IllegalArgumentException("Checkpoint cumulative difficulty encoding exceeds limits");
		}
		return ByteBuffer.allocate(Integer.BYTES * 3 + Long.BYTES + Hash.SIZE * 4 + difficulty.length)
				.putInt(MAGIC)
				.putInt(VERSION)
				.putLong(floor.height())
				.put(floor.blockHash().toArray())
				.put(floor.stateRoot().toArray())
				.putInt(difficulty.length)
				.put(difficulty)
				.put(floor.stateManifestSigningHash().toArray())
				.put(floor.archiveManifestSigningHash().toArray())
				.array();
	}

	public static CoreSnapshotCheckpointFloor decode(byte[] encoded) {
		if (encoded == null) {
			throw new IllegalArgumentException("Checkpoint floor metadata is missing");
		}
		ByteBuffer input = ByteBuffer.wrap(encoded);
		if (input.remaining() < Integer.BYTES * 3 + Long.BYTES + Hash.SIZE * 4
				|| input.getInt() != MAGIC || input.getInt() != VERSION) {
			throw new IllegalArgumentException("Checkpoint floor metadata header is invalid");
		}
		long height = input.getLong();
		Hash blockHash = readHash(input);
		Hash stateRoot = readHash(input);
		int difficultyLength = input.getInt();
		if (difficultyLength <= 0 || difficultyLength > MAX_DIFFICULTY_BYTES
				|| input.remaining() != difficultyLength + Hash.SIZE * 2) {
			throw new IllegalArgumentException("Checkpoint floor difficulty length is invalid");
		}
		byte[] difficulty = new byte[difficultyLength];
		input.get(difficulty);
		CoreSnapshotCheckpointFloor floor = new CoreSnapshotCheckpointFloor(
				height, blockHash, stateRoot, new BigInteger(difficulty), readHash(input), readHash(input));
		if (input.hasRemaining()) {
			throw new IllegalArgumentException("Checkpoint floor metadata contains trailing bytes");
		}
		if (!Arrays.equals(encode(floor), encoded)) {
			throw new IllegalArgumentException("Checkpoint floor metadata is not canonically encoded");
		}
		return floor;
	}

	private static Hash readHash(ByteBuffer input) {
		byte[] value = new byte[Hash.SIZE];
		input.get(value);
		return Hash.wrap(value);
	}
}
