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
package global.goldenera.node.core.sandbox.authoring;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable identity material produced by offline manifest authoring. */
public final class SandboxManifestAuthoringResult {

	private final Path output;
	private final String genesisHash;
	private final String manifestFingerprint;
	private final byte[] canonicalManifest;

	SandboxManifestAuthoringResult(
			Path output,
			String genesisHash,
			String manifestFingerprint,
			byte[] canonicalManifest) {
		this.output = Objects.requireNonNull(output, "output");
		this.genesisHash = Objects.requireNonNull(genesisHash, "genesisHash");
		this.manifestFingerprint = Objects.requireNonNull(manifestFingerprint, "manifestFingerprint");
		this.canonicalManifest = canonicalManifest.clone();
	}

	public Path output() {
		return output;
	}

	public String genesisHash() {
		return genesisHash;
	}

	public String manifestFingerprint() {
		return manifestFingerprint;
	}

	public byte[] canonicalManifest() {
		return canonicalManifest.clone();
	}
}
