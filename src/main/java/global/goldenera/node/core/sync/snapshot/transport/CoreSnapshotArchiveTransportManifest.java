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
