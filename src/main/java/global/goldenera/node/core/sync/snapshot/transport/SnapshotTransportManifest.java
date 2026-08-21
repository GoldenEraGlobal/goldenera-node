/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.transport;

import java.util.Base64;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;

/** HTTP envelope carrying the complete canonical domain manifest. */
public record SnapshotTransportManifest(
		String canonicalManifest,
		String manifestSigningHash,
		String signature) {

	public static SnapshotTransportManifest from(CheckpointSnapshotManifest manifest) {
		Bytes canonical = CheckpointSnapshotManifestCodec.canonicalBytes(manifest);
		return new SnapshotTransportManifest(
				Base64.getEncoder().encodeToString(canonical.toArrayUnsafe()),
				CheckpointSnapshotManifestCodec.signingHash(manifest).toHexString(),
				null);
	}

	public CheckpointSnapshotManifest decodeAndVerify() {
		if (canonicalManifest == null || manifestSigningHash == null) {
			throw new IllegalArgumentException("Snapshot transport manifest is incomplete");
		}
		byte[] decoded = Base64.getDecoder().decode(canonicalManifest);
		if (!Base64.getEncoder().encodeToString(decoded).equals(canonicalManifest)) {
			throw new IllegalArgumentException("Snapshot manifest Base64 is not canonical");
		}
		CheckpointSnapshotManifest manifest = CheckpointSnapshotManifestCodec.decodeCanonicalBytes(Bytes.wrap(decoded));
		Hash declaredHash = Hash.fromHexString(manifestSigningHash);
		if (!CheckpointSnapshotManifestCodec.signingHash(manifest).equals(declaredHash)) {
			throw new IllegalArgumentException("Snapshot manifest signing hash mismatch");
		}
		return manifest;
	}
}
