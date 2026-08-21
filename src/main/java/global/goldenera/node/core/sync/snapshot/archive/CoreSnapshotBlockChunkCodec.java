/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.FORMAT_VERSION;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_BLOCKS_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_CHUNK_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_ENCODED_BLOCK_BYTES;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncoder;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;

/**
 * Deterministic, length-delimited stream format for canonical V1
 * {@link StoredBlock} encodings. The fixed header is followed by exactly the
 * declared number of {@code int length + RLP StoredBlock} entries.
 */
public final class CoreSnapshotBlockChunkCodec {

	private static final int MAGIC = 0x47454341; // GECA
	static final int HEADER_BYTES = Integer.BYTES * 4;
	static final int BLOCK_COUNT_OFFSET = Integer.BYTES * 3;

	private CoreSnapshotBlockChunkCodec() {
	}

	/** Publisher/test helper. Verification itself uses {@link Reader} and is streaming. */
	public static Bytes encodeChunk(int chunkIndex, List<StoredBlock> blocks) {
		Objects.requireNonNull(blocks, "blocks");
		if (chunkIndex < 0 || blocks.isEmpty() || blocks.size() > MAX_BLOCKS_PER_CHUNK) {
			throw new IllegalArgumentException("Invalid archive block chunk index/count");
		}
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);
			writeHeader(output, chunkIndex, blocks.size());
			for (StoredBlock block : blocks) {
				Bytes encoded = encodeStoredBlock(block);
				if ((long) bytes.size() + Integer.BYTES + encoded.size() > MAX_CHUNK_BYTES) {
					throw new IllegalArgumentException("Encoded archive chunk exceeds byte limit");
				}
				output.writeInt(encoded.size());
				output.write(encoded.toArrayUnsafe());
			}
			output.flush();
			return Bytes.wrap(bytes.toByteArray());
		} catch (IOException e) {
			throw new IllegalStateException("Cannot encode in-memory archive chunk", e);
		}
	}

	static void writeHeader(DataOutput output, int chunkIndex, int blockCount) throws IOException {
		output.writeInt(MAGIC);
		output.writeInt(FORMAT_VERSION);
		output.writeInt(chunkIndex);
		output.writeInt(blockCount);
	}

	static Bytes encodeStoredBlock(StoredBlock block) {
		Bytes encoded = StoredBlockEncoder.INSTANCE.encode(
				Objects.requireNonNull(block, "block"), StoredBlockVersion.V1);
		if (encoded.isEmpty() || encoded.size() > MAX_ENCODED_BLOCK_BYTES) {
			throw new IllegalArgumentException("Encoded StoredBlock exceeds archive limits");
		}
		return encoded;
	}

	public static Reader open(InputStream source, CoreSnapshotBlockChunkDescriptor descriptor) {
		return new Reader(source, descriptor);
	}

	public static final class Reader implements AutoCloseable {

		private final DataInputStream input;
		private final CoreSnapshotBlockChunkDescriptor descriptor;
		private int readBlocks;
		private boolean finished;

		private Reader(InputStream source, CoreSnapshotBlockChunkDescriptor descriptor) {
			this.input = new DataInputStream(Objects.requireNonNull(source, "source"));
			this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
			try {
				int magic = input.readInt();
				int version = input.readInt();
				int index = input.readInt();
				int count = input.readInt();
				if (magic != MAGIC || version != FORMAT_VERSION || index != descriptor.index()
						|| count != descriptor.blockCount()) {
					throw failure("Archive block chunk header does not match descriptor " + descriptor.index());
				}
			} catch (EOFException e) {
				throw failure("Truncated archive block chunk header " + descriptor.index(), e);
			} catch (IOException e) {
				throw failure("Cannot read archive block chunk header " + descriptor.index(), e);
			}
		}

		public boolean hasNext() {
			return readBlocks < descriptor.blockCount();
		}

		public StoredBlock next() {
			if (!hasNext()) {
				throw new IllegalStateException("No more declared blocks in archive chunk");
			}
			try {
				int encodedLength = input.readInt();
				if (encodedLength <= 0 || encodedLength > MAX_ENCODED_BLOCK_BYTES) {
					throw failure("Invalid encoded StoredBlock length in chunk " + descriptor.index());
				}
				byte[] encodedBytes = input.readNBytes(encodedLength);
				if (encodedBytes.length != encodedLength) {
					throw failure("Truncated StoredBlock in archive chunk " + descriptor.index());
				}
				Bytes encoded = Bytes.wrap(encodedBytes);
				StoredBlock decoded;
				try {
					decoded = StoredBlockDecoder.INSTANCE.decode(encoded);
				} catch (RuntimeException e) {
					throw failure("Invalid StoredBlock encoding in archive chunk " + descriptor.index(), e);
				}
				Bytes canonical;
				try {
					canonical = StoredBlockEncoder.INSTANCE.encode(decoded, StoredBlockVersion.V1);
				} catch (RuntimeException e) {
					throw failure("StoredBlock cannot be canonically re-encoded", e);
				}
				if (!canonical.equals(encoded)) {
					throw failure("Non-canonical StoredBlock encoding in archive chunk " + descriptor.index());
				}
				readBlocks++;
				return decoded;
			} catch (EOFException e) {
				throw failure("Truncated archive block chunk " + descriptor.index(), e);
			} catch (IOException e) {
				throw failure("Cannot read archive block chunk " + descriptor.index(), e);
			}
		}

		/** Rejects both an early end and any bytes after the declared block sequence. */
		public void finish() {
			if (finished) {
				return;
			}
			if (hasNext()) {
				throw failure("Archive block chunk ended before all declared blocks were read");
			}
			try {
				if (input.read() != -1) {
					throw failure("Archive block chunk contains trailing bytes: " + descriptor.index());
				}
				finished = true;
			} catch (IOException e) {
				throw failure("Cannot finish archive block chunk " + descriptor.index(), e);
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
