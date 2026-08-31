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
package global.goldenera.node.core.sync.snapshot.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import com.github.luben.zstd.ZstdOutputStream;

import global.goldenera.cryptoj.datatypes.Hash;

class CoreSnapshotCompressionTest {

	@Test
	void streamsDeterministicZstdRoundTripAndVerifiesBothCommitments() throws Exception {
		byte[] uncompressed = new byte[512 * 1024];
		for (int index = 0; index < uncompressed.length; index++) {
			uncompressed[index] = (byte) (index % 19);
		}
		byte[] compressed = compress(uncompressed);

		ByteArrayOutputStream restored = new ByteArrayOutputStream();
		try (CoreSnapshotCompression.VerifiedInputStream input = CoreSnapshotCompression.openVerifiedZstd(
				new ByteArrayInputStream(compressed), compressed.length, hash(compressed),
				uncompressed.length, hash(uncompressed))) {
			input.transferTo(restored);
			assertThat(input.isVerified()).isTrue();
		}

		assertThat(restored.toByteArray()).isEqualTo(uncompressed);
		assertThat(compress(uncompressed)).isEqualTo(compressed);
	}

	@Test
	void rejectsCompressedSizeAndHashMismatch() throws Exception {
		byte[] uncompressed = "canonical archive data".repeat(32).getBytes();
		byte[] compressed = compress(uncompressed);

		assertThatThrownBy(() -> finish(compressed, compressed.length - 1L, hash(compressed),
				uncompressed.length, hash(uncompressed)))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("declared byte limit");
		assertThatThrownBy(() -> finish(compressed, compressed.length, Hash.ZERO,
				uncompressed.length, hash(uncompressed)))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("compressed snapshot chunk size/hash mismatch");
	}

	@Test
	void rejectsUncompressedSizeAndHashMismatch() throws Exception {
		byte[] uncompressed = "canonical archive data".repeat(32).getBytes();
		byte[] compressed = compress(uncompressed);

		assertThatThrownBy(() -> finish(compressed, compressed.length, hash(compressed),
				uncompressed.length - 1L, hash(uncompressed)))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("declared byte limit");
		assertThatThrownBy(() -> finish(compressed, compressed.length, hash(compressed),
				uncompressed.length, Hash.ZERO))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("Uncompressed snapshot chunk size/hash mismatch");
	}

	@Test
	void stopsDecompressionBombAtDeclaredUncompressedBound() throws Exception {
		byte[] bomb = new byte[4 * 1024 * 1024];
		Arrays.fill(bomb, (byte) 7);
		byte[] compressed = compress(bomb);
		long declaredBytes = 64;

		try (CoreSnapshotCompression.VerifiedInputStream input = CoreSnapshotCompression.openVerifiedZstd(
				new ByteArrayInputStream(compressed), compressed.length, hash(compressed),
				declaredBytes, hash(Arrays.copyOf(bomb, (int) declaredBytes)))) {
			byte[] requested = new byte[1024 * 1024];
			assertThatThrownBy(() -> input.read(requested))
					.isInstanceOf(IOException.class)
					.hasMessageContaining("exceeds its declared byte limit");
		}
	}

	@Test
	void rejectsFrameWhoseWindowExceedsNativeMemoryLimit() throws Exception {
		byte[] uncompressed = "bounded-window".repeat(1024).getBytes();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZstdOutputStream zstd = new ZstdOutputStream(output).setWindowLog(
				CoreSnapshotCompression.MAX_ZSTD_WINDOW_LOG + 1)) {
			zstd.write(uncompressed);
		}
		byte[] compressed = output.toByteArray();

		assertThatThrownBy(() -> finish(
				compressed, compressed.length, hash(compressed), uncompressed.length, hash(uncompressed)))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("too much memory for decoding");
	}

	private void finish(
			byte[] compressed,
			long compressedBytes,
			Hash compressedHash,
			long uncompressedBytes,
			Hash uncompressedHash) throws IOException {
		try (CoreSnapshotCompression.VerifiedInputStream input = CoreSnapshotCompression.openVerifiedZstd(
				new ByteArrayInputStream(compressed), compressedBytes, compressedHash,
				uncompressedBytes, uncompressedHash)) {
			input.finish();
		}
	}

	private byte[] compress(byte[] uncompressed) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CoreSnapshotCompression.writeZstd(new ByteArrayInputStream(uncompressed), output);
		return output.toByteArray();
	}

	private Hash hash(byte[] bytes) {
		return Hash.hash(Bytes.wrap(bytes));
	}
}
