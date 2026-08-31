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
package global.goldenera.node.core.sync.snapshot;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.blockheader.BlockHeaderDecoder;
import global.goldenera.cryptoj.serialization.blockheader.BlockHeaderEncoder;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;

/** Canonical, order-preserving encoding used as the snapshot manifest signing payload. */
public final class CheckpointSnapshotManifestCodec {

	private static final Bytes SIGNING_DOMAIN = Bytes.wrap(
			"goldenera-checkpoint-snapshot-manifest-v1".getBytes(StandardCharsets.US_ASCII));

	private CheckpointSnapshotManifestCodec() {
	}

	public static Bytes canonicalBytes(CheckpointSnapshotManifest manifest) {
		StoredChainIdentity identity = manifest.chainIdentity();
		SnapshotHeaderSegment segment = manifest.headerSegment();
		return RLP.encode(out -> {
			out.startList();
			out.writeIntScalar(manifest.formatVersion());
			out.writeIntScalar(manifest.networkCode());
			out.startList();
			out.writeIntScalar(identity.formatVersion());
			out.writeIntScalar(identity.carrierNetworkCode());
			out.writeString(identity.chainId());
			out.writeString(identity.genesisHash());
			out.writeString(identity.manifestFingerprint() == null ? "" : identity.manifestFingerprint());
			out.endList();
			out.writeLongScalar(manifest.checkpointHeight());
			out.writeBytes32(manifest.checkpointHash());
			out.writeBytes32(manifest.checkpointStateRoot());
			out.writeBigIntegerScalar(manifest.checkpointCumulativeDifficulty());
			out.startList();
			out.writeBytes32(segment.parentHash());
			out.writeBigIntegerScalar(segment.parentCumulativeDifficulty());
			out.writeList(segment.headers(), (snapshotHeader, headerOut) -> {
				headerOut.startList();
				headerOut.writeBytes32(snapshotHeader.declaredHash());
				headerOut.writeBigIntegerScalar(snapshotHeader.cumulativeDifficulty());
				headerOut.writeRaw(BlockHeaderEncoder.INSTANCE.encode(snapshotHeader.header(), true));
				headerOut.endList();
			});
			out.endList();
			out.writeList(manifest.chunks(), (chunk, chunkOut) -> {
				chunkOut.startList();
				chunkOut.writeIntScalar(chunk.index());
				chunkOut.writeString(chunk.id());
				chunkOut.writeString(chunk.url());
				chunkOut.writeIntScalar(chunk.nodeCount());
				chunkOut.writeLongScalar(chunk.byteCount());
				chunkOut.writeBytes32(chunk.contentHash());
				chunkOut.endList();
			});
			out.endList();
		});
	}

	public static Hash signingHash(CheckpointSnapshotManifest manifest) {
		return Hash.hash(Bytes.concatenate(SIGNING_DOMAIN, canonicalBytes(manifest)));
	}

	/** Strict inverse of {@link #canonicalBytes(CheckpointSnapshotManifest)}. */
	public static CheckpointSnapshotManifest decodeCanonicalBytes(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		int formatVersion = input.readIntScalar();
		int networkCode = input.readIntScalar();
		StoredChainIdentity identity = decodeIdentity(input.readRaw());
		long checkpointHeight = input.readLongScalar();
		Hash checkpointHash = Hash.wrap(input.readBytes32());
		Hash checkpointStateRoot = Hash.wrap(input.readBytes32());
		var cumulativeDifficulty = input.readBigIntegerScalar();
		SnapshotHeaderSegment headerSegment = decodeHeaderSegment(input.readRaw());
		List<SnapshotChunkDescriptor> chunks = input.readList(item -> decodeChunk(item.readRaw()));
		input.leaveList();
		CheckpointSnapshotManifest manifest = new CheckpointSnapshotManifest(
				formatVersion, networkCode, identity, checkpointHeight, checkpointHash, checkpointStateRoot,
				cumulativeDifficulty, headerSegment, chunks);
		if (!canonicalBytes(manifest).equals(bytes)) {
			throw new IllegalArgumentException("Snapshot manifest is not canonically encoded");
		}
		return manifest;
	}

	private static StoredChainIdentity decodeIdentity(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		int formatVersion = input.readIntScalar();
		int networkCode = input.readIntScalar();
		String chainId = input.readString();
		String genesisHash = input.readString();
		String fingerprint = input.readString();
		input.leaveList();
		return new StoredChainIdentity(
				formatVersion, networkCode, chainId, genesisHash, fingerprint.isEmpty() ? null : fingerprint);
	}

	private static SnapshotHeaderSegment decodeHeaderSegment(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		Hash parentHash = Hash.wrap(input.readBytes32());
		var parentDifficulty = input.readBigIntegerScalar();
		List<SnapshotHeader> headers = input.readList(item -> decodeHeader(item.readRaw()));
		input.leaveList();
		return new SnapshotHeaderSegment(parentHash, parentDifficulty, headers);
	}

	private static SnapshotHeader decodeHeader(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		Hash declaredHash = Hash.wrap(input.readBytes32());
		var cumulativeDifficulty = input.readBigIntegerScalar();
		var header = BlockHeaderDecoder.INSTANCE.decode(input.readRaw());
		input.leaveList();
		return new SnapshotHeader(declaredHash, header, cumulativeDifficulty);
	}

	private static SnapshotChunkDescriptor decodeChunk(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		SnapshotChunkDescriptor descriptor = new SnapshotChunkDescriptor(
				input.readIntScalar(), input.readString(), input.readString(), input.readIntScalar(),
				input.readLongScalar(), Hash.wrap(input.readBytes32()));
		input.leaveList();
		return descriptor;
	}
}
