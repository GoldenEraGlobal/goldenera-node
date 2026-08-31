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
package global.goldenera.node.core.sandbox.manifest;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable manifest plus its canonical identity material. */
public final class SandboxManifestContext {

	private final SandboxManifest manifest;
	private final byte[] canonicalJson;
	private final String fingerprint;

	SandboxManifestContext(SandboxManifest manifest, byte[] canonicalJson, String fingerprint) {
		this.manifest = Objects.requireNonNull(manifest, "manifest");
		this.canonicalJson = canonicalJson.clone();
		this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
	}

	public SandboxManifest manifest() {
		return manifest;
	}

	/** Returns a defensive copy of the bytes used to compute the fingerprint. */
	public byte[] canonicalJson() {
		return canonicalJson.clone();
	}

	public String canonicalJsonUtf8() {
		return new String(canonicalJson, StandardCharsets.UTF_8);
	}

	/** Lowercase hexadecimal SHA-256 of {@link #canonicalJson()}. */
	public String fingerprint() {
		return fingerprint;
	}
}
