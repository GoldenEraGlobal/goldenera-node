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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;

/** Bounded streaming compression and integrity verification for snapshot chunks. */
public final class CoreSnapshotCompression {

	private static final int ZSTD_COMPRESSION_LEVEL = 3;
	static final int MAX_ZSTD_WINDOW_LOG = 27;

	private CoreSnapshotCompression() {
	}

	/**
	 * Compresses {@code source} into one checksummed Zstd frame. Closing the returned
	 * Zstd stream also closes {@code target}; this method does not close {@code source}.
	 */
	public static void writeZstd(InputStream source, OutputStream target) throws IOException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(target, "target");
		try (ZstdOutputStream compressed = new ZstdOutputStream(target, ZSTD_COMPRESSION_LEVEL)
				.setChecksum(true)) {
			source.transferTo(compressed);
		}
	}

	/**
	 * Opens an exact-size/hash verified Zstd stream. Callers must consume EOF (or call
	 * {@link VerifiedInputStream#finish()}) before treating the input as verified.
	 */
	public static VerifiedInputStream openVerifiedZstd(
			InputStream compressedSource,
			long expectedCompressedBytes,
			Hash expectedCompressedHash,
			long expectedUncompressedBytes,
			Hash expectedUncompressedHash) {
		if (expectedCompressedBytes <= 0 || expectedUncompressedBytes <= 0) {
			throw new IllegalArgumentException("Snapshot compression byte counts must be positive");
		}
		Objects.requireNonNull(expectedCompressedHash, "expectedCompressedHash");
		Objects.requireNonNull(expectedUncompressedHash, "expectedUncompressedHash");
		try {
			ExactDigestInputStream compressed = new ExactDigestInputStream(
					Objects.requireNonNull(compressedSource, "compressedSource"),
					expectedCompressedBytes, expectedCompressedHash, "compressed");
			ZstdInputStream decompressor = new ZstdInputStream(compressed)
					.setLongMax(MAX_ZSTD_WINDOW_LOG)
					.setContinuous(false);
			return new VerifiedInputStream(
					decompressor, compressed, expectedUncompressedBytes, expectedUncompressedHash);
		} catch (IOException e) {
			throw failure("Cannot open Zstd snapshot chunk", e);
		}
	}

	public static final class VerifiedInputStream extends FilterInputStream {

		private final ExactDigestInputStream compressed;
		private final Digest256 uncompressedDigest = new Digest256();
		private final long expectedUncompressedBytes;
		private final Hash expectedUncompressedHash;
		private long uncompressedBytes;
		private boolean verified;

		private VerifiedInputStream(
				InputStream decompressor,
				ExactDigestInputStream compressed,
				long expectedUncompressedBytes,
				Hash expectedUncompressedHash) {
			super(decompressor);
			this.compressed = compressed;
			this.expectedUncompressedBytes = expectedUncompressedBytes;
			this.expectedUncompressedHash = expectedUncompressedHash;
		}

		@Override
		public int read() throws IOException {
			int value = super.read();
			if (value < 0) {
				verifyAtEof();
			} else {
				byte[] single = { (byte) value };
				accept(single, 0, 1);
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			Objects.checkFromIndexSize(offset, length, bytes.length);
			if (length == 0) {
				return 0;
			}
			long remaining = expectedUncompressedBytes - uncompressedBytes;
			int boundedLength = remaining >= length ? length : (int) Math.max(1, remaining + 1);
			int read = super.read(bytes, offset, boundedLength);
			if (read < 0) {
				verifyAtEof();
			} else if (read > 0) {
				accept(bytes, offset, read);
			}
			return read;
		}

		private void accept(byte[] bytes, int offset, int length) throws IOException {
			try {
				uncompressedBytes = Math.addExact(uncompressedBytes, length);
			} catch (ArithmeticException e) {
				throw new IOException("Uncompressed snapshot chunk byte count overflow", e);
			}
			if (uncompressedBytes > expectedUncompressedBytes) {
				throw new IOException("Uncompressed snapshot chunk exceeds its declared byte limit");
			}
			uncompressedDigest.update(bytes, offset, length);
		}

		private void verifyAtEof() throws IOException {
			if (verified) {
				return;
			}
			if (uncompressedBytes != expectedUncompressedBytes
					|| !Hash.wrap(uncompressedDigest.digest()).equals(expectedUncompressedHash)) {
				throw new IOException("Uncompressed snapshot chunk size/hash mismatch");
			}
			compressed.verifyAtDecodedEof();
			verified = true;
		}

		/** Drains the bounded stream and verifies both compressed and uncompressed commitments. */
		public void finish() throws IOException {
			byte[] buffer = new byte[64 * 1024];
			while (read(buffer) >= 0) {
				// Reading to EOF performs the exact size/hash checks.
			}
		}

		public boolean isVerified() {
			return verified;
		}
	}

	private static final class ExactDigestInputStream extends FilterInputStream {

		private final Digest256 digest = new Digest256();
		private final long expectedBytes;
		private final Hash expectedHash;
		private final String label;
		private long count;

		private ExactDigestInputStream(
				InputStream input, long expectedBytes, Hash expectedHash, String label) {
			super(input);
			this.expectedBytes = expectedBytes;
			this.expectedHash = expectedHash;
			this.label = label;
		}

		@Override
		public int read() throws IOException {
			int value = in.read();
			if (value >= 0) {
				byte[] single = { (byte) value };
				accept(single, 0, 1);
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			Objects.checkFromIndexSize(offset, length, bytes.length);
			if (length == 0) {
				return 0;
			}
			long remaining = expectedBytes - count;
			int boundedLength = remaining >= length ? length : (int) Math.max(1, remaining + 1);
			int read = in.read(bytes, offset, boundedLength);
			if (read > 0) {
				accept(bytes, offset, read);
			}
			return read;
		}

		private void accept(byte[] bytes, int offset, int length) throws IOException {
			try {
				count = Math.addExact(count, length);
			} catch (ArithmeticException e) {
				throw new IOException("Compressed snapshot chunk byte count overflow", e);
			}
			if (count > expectedBytes) {
				throw new IOException("Compressed snapshot chunk exceeds its declared byte limit");
			}
			digest.update(bytes, offset, length);
		}

		private void verifyAtDecodedEof() throws IOException {
			if (in.read() >= 0) {
				throw new IOException("Compressed snapshot chunk contains trailing bytes");
			}
			if (count != expectedBytes || !Hash.wrap(digest.digest()).equals(expectedHash)) {
				throw new IOException(label + " snapshot chunk size/hash mismatch");
			}
		}
	}

	private static SnapshotVerificationException failure(String message, Throwable cause) {
		return new SnapshotVerificationException(message, cause);
	}
}
