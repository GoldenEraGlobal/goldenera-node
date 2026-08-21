/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.transport;

import java.util.Base64;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifestCodec;

/** HTTP envelope which preserves and binds the complete canonical archive manifest. */
public record CoreSnapshotArchiveTransportManifest(
		String canonicalManifest,
		String manifestSigningHash,
		String signature) {

	public static CoreSnapshotArchiveTransportManifest from(CoreSnapshotArchiveManifest manifest) {
		Bytes canonical = CoreSnapshotArchiveManifestCodec.canonicalBytes(manifest);
		return new CoreSnapshotArchiveTransportManifest(
				Base64.getEncoder().encodeToString(canonical.toArrayUnsafe()),
				CoreSnapshotArchiveManifestCodec.signingHash(manifest).toHexString(), null);
	}

	public CoreSnapshotArchiveManifest decodeAndVerify() {
		if (canonicalManifest == null || manifestSigningHash == null) {
			throw new IllegalArgumentException("Archive manifest envelope is incomplete");
		}
		byte[] decoded = Base64.getDecoder().decode(canonicalManifest);
		if (!Base64.getEncoder().encodeToString(decoded).equals(canonicalManifest)) {
			throw new IllegalArgumentException("Archive manifest Base64 is not canonical");
		}
		CoreSnapshotArchiveManifest manifest =
				CoreSnapshotArchiveManifestCodec.decodeCanonicalBytes(Bytes.wrap(decoded));
		Hash declaredHash = Hash.fromHexString(manifestSigningHash);
		if (!CoreSnapshotArchiveManifestCodec.signingHash(manifest).equals(declaredHash)) {
			throw new IllegalArgumentException("Archive manifest signing hash mismatch");
		}
		return manifest;
	}
}
