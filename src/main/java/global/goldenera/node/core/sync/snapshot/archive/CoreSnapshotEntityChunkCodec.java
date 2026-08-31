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

import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_ENTRIES_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_STATE_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits.MAX_UNCOMPRESSED_CHUNK_BYTES;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;
import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;

/** Deterministic uncompressed entity stream carried inside a bounded compressed chunk. */
public final class CoreSnapshotEntityChunkCodec {

	private static final int MAGIC = 0x47454549; // GEEI
	private static final int FORMAT_VERSION = SnapshotFormatCompatibility.CURRENT_ENTITY_FORMAT;
	public static final int HEADER_BYTES = Integer.BYTES * 4;

	private CoreSnapshotEntityChunkCodec() {
	}

	public static void writeHeader(
			DataOutput output, int chunkIndex, CoreSnapshotEntityType entityType, int entryCount)
			throws IOException {
		if (chunkIndex < 0 || entryCount < 0 || entryCount > MAX_ENTRIES_PER_CHUNK) {
			throw new IllegalArgumentException("Invalid entity chunk index/count");
		}
		output.writeInt(MAGIC);
		output.writeInt(FORMAT_VERSION);
		output.writeInt(chunkIndex);
		output.writeInt((entityType.code() << 24) | entryCount);
	}

	public static void writeEntry(DataOutput output, CoreSnapshotEntityEntry entry) throws IOException {
		if (entry.canonicalState().size() > MAX_STATE_BYTES) {
			throw new IllegalArgumentException("Entity state exceeds snapshot limits");
		}
		output.write(entry.address().toArray());
		output.writeInt(entry.canonicalState().size());
		output.write(entry.canonicalState().toArrayUnsafe());
	}

	public static Reader open(InputStream input, CoreSnapshotEntityChunkDescriptor descriptor) {
		return open(input, descriptor, CoreSnapshotArchiveLimits.FORMAT_VERSION);
	}

	public static Reader open(
			InputStream input, CoreSnapshotEntityChunkDescriptor descriptor, int archiveFormatVersion) {
		return new Reader(input, descriptor, archiveFormatVersion);
	}

	public static final class Reader implements AutoCloseable {

		private final DataInputStream input;
		private final CoreSnapshotEntityChunkDescriptor descriptor;
		private int entriesRead;
		private long bytesRead = HEADER_BYTES;
		private boolean finished;

		private Reader(
				InputStream source, CoreSnapshotEntityChunkDescriptor descriptor, int archiveFormatVersion) {
			this.input = new DataInputStream(Objects.requireNonNull(source, "source"));
			this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
			try {
				int magic = input.readInt();
				int version = input.readInt();
				int index = input.readInt();
				int typeAndCount = input.readInt();
				CoreSnapshotEntityType type = CoreSnapshotEntityType.fromCode(typeAndCount >>> 24);
				int count = typeAndCount & 0x00ff_ffff;
				if (magic != MAGIC
						|| !SnapshotFormatCompatibility.supportsEntityChunkForArchive(
								archiveFormatVersion, version)
						|| index != descriptor.index()
						|| type != descriptor.entityType() || count != descriptor.entryCount()) {
					throw failure("Entity chunk header does not match descriptor " + descriptor.index());
				}
			} catch (EOFException e) {
				throw failure("Truncated entity chunk header " + descriptor.index(), e);
			} catch (IOException | IllegalArgumentException e) {
				throw failure("Cannot read entity chunk header " + descriptor.index(), e);
			}
		}

		public boolean hasNext() {
			return entriesRead < descriptor.entryCount();
		}

		public CoreSnapshotEntityEntry next() {
			if (!hasNext()) {
				throw new IllegalStateException("No more declared entity entries");
			}
			try {
				byte[] address = input.readNBytes(Address.SIZE);
				if (address.length != Address.SIZE) {
					throw failure("Truncated entity address in chunk " + descriptor.index());
				}
				int stateLength = input.readInt();
				if (stateLength <= 0 || stateLength > MAX_STATE_BYTES) {
					throw failure("Invalid entity state length in chunk " + descriptor.index());
				}
				bytesRead = Math.addExact(bytesRead, Address.SIZE + Integer.BYTES + (long) stateLength);
				if (bytesRead > descriptor.uncompressedByteCount()
						|| bytesRead > MAX_UNCOMPRESSED_CHUNK_BYTES) {
					throw failure("Entity chunk exceeds its uncompressed byte limit");
				}
				byte[] state = input.readNBytes(stateLength);
				if (state.length != stateLength) {
					throw failure("Truncated entity state in chunk " + descriptor.index());
				}
				Bytes canonicalState = Bytes.wrap(state);
				CoreSnapshotEntityStateCodec.decodeCanonical(descriptor.entityType(), canonicalState);
				entriesRead++;
				return new CoreSnapshotEntityEntry(Address.wrap(address), canonicalState);
			} catch (SnapshotVerificationException e) {
				throw e;
			} catch (Exception e) {
				throw failure("Cannot decode entity entry in chunk " + descriptor.index(), e);
			}
		}

		public void finish() {
			if (finished) {
				return;
			}
			if (hasNext() || bytesRead != descriptor.uncompressedByteCount()) {
				throw failure("Entity chunk ended before its declared contents");
			}
			try {
				if (input.read() != -1) {
					throw failure("Entity chunk contains trailing bytes: " + descriptor.index());
				}
				finished = true;
			} catch (IOException e) {
				throw failure("Cannot finish entity chunk " + descriptor.index(), e);
			}
		}

		@Override
		public void close() throws IOException {
			input.close();
		}
	}

	private static SnapshotVerificationException failure(String message) {
		return new SnapshotVerificationException(message);
	}

	private static SnapshotVerificationException failure(String message, Throwable cause) {
		return new SnapshotVerificationException(message, cause);
	}
}
