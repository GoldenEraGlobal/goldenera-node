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

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;

/** Canonical signing codec for FULL CORE archive manifests. */
public final class CoreSnapshotArchiveManifestCodec {

	private static final Bytes SIGNING_DOMAIN = Bytes.wrap(
			"goldenera-core-snapshot-archive-manifest-v2".getBytes(StandardCharsets.US_ASCII));

	private CoreSnapshotArchiveManifestCodec() {
	}

	public static Bytes canonicalBytes(CoreSnapshotArchiveManifest manifest) {
		return RLP.encode(out -> {
			out.startList();
			out.writeIntScalar(manifest.formatVersion());
			out.writeBytes32(manifest.stateManifestSigningHash());
			out.writeList(manifest.blockChunks(), (chunk, chunkOut) -> {
				chunkOut.startList();
				chunkOut.writeIntScalar(chunk.index());
				chunkOut.writeLongScalar(chunk.firstHeight());
				chunkOut.writeLongScalar(chunk.lastHeight());
				chunkOut.writeIntScalar(chunk.blockCount());
				chunkOut.writeIntScalar(chunk.compression().code());
				chunkOut.writeLongScalar(chunk.compressedByteCount());
				chunkOut.writeBytes32(chunk.compressedContentHash());
				chunkOut.writeLongScalar(chunk.uncompressedByteCount());
				chunkOut.writeBytes32(chunk.uncompressedContentHash());
				chunkOut.endList();
			});
			out.writeList(manifest.entityChunks(), (chunk, chunkOut) -> {
				chunkOut.startList();
				chunkOut.writeIntScalar(chunk.index());
				chunkOut.writeIntScalar(chunk.entityType().code());
				chunkOut.writeIntScalar(chunk.entryCount());
				chunkOut.writeLongScalar(chunk.compressedByteCount());
				chunkOut.writeBytes32(chunk.compressedContentHash());
				chunkOut.writeLongScalar(chunk.uncompressedByteCount());
				chunkOut.writeBytes32(chunk.uncompressedContentHash());
				chunkOut.endList();
			});
			out.endList();
		});
	}

	public static Hash signingHash(CoreSnapshotArchiveManifest manifest) {
		return Hash.hash(Bytes.concatenate(SIGNING_DOMAIN, canonicalBytes(manifest)));
	}

	public static CoreSnapshotArchiveManifest decodeCanonicalBytes(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		int formatVersion = input.readIntScalar();
		Hash stateManifestHash = Hash.wrap(input.readBytes32());
		List<CoreSnapshotBlockChunkDescriptor> chunks = input.readList(item -> decodeChunk(item.readRaw()));
		List<CoreSnapshotEntityChunkDescriptor> entityChunks =
				input.readList(item -> decodeEntityChunk(item.readRaw()));
		input.leaveList();
		CoreSnapshotArchiveManifest manifest = new CoreSnapshotArchiveManifest(
				formatVersion, stateManifestHash, chunks, entityChunks);
		if (!canonicalBytes(manifest).equals(bytes)) {
			throw new IllegalArgumentException("Core snapshot archive manifest is not canonically encoded");
		}
		return manifest;
	}

	private static CoreSnapshotBlockChunkDescriptor decodeChunk(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		CoreSnapshotBlockChunkDescriptor chunk = new CoreSnapshotBlockChunkDescriptor(
				input.readIntScalar(), input.readLongScalar(), input.readLongScalar(), input.readIntScalar(),
				CoreSnapshotChunkCompression.fromCode(input.readIntScalar()),
				input.readLongScalar(), Hash.wrap(input.readBytes32()),
				input.readLongScalar(), Hash.wrap(input.readBytes32()));
		input.leaveList();
		return chunk;
	}

	private static CoreSnapshotEntityChunkDescriptor decodeEntityChunk(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		CoreSnapshotEntityChunkDescriptor chunk = new CoreSnapshotEntityChunkDescriptor(
				input.readIntScalar(), CoreSnapshotEntityType.fromCode(input.readIntScalar()),
				input.readIntScalar(), input.readLongScalar(), Hash.wrap(input.readBytes32()),
				input.readLongScalar(), Hash.wrap(input.readBytes32()));
		input.leaveList();
		return chunk;
	}
}
