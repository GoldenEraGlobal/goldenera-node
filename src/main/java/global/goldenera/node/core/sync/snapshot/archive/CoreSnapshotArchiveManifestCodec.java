/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
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
			"goldenera-core-snapshot-archive-manifest-v1".getBytes(StandardCharsets.US_ASCII));

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
				chunkOut.writeLongScalar(chunk.byteCount());
				chunkOut.writeBytes32(chunk.contentHash());
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
		input.leaveList();
		CoreSnapshotArchiveManifest manifest = new CoreSnapshotArchiveManifest(
				formatVersion, stateManifestHash, chunks);
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
				input.readLongScalar(), Hash.wrap(input.readBytes32()));
		input.leaveList();
		return chunk;
	}
}
