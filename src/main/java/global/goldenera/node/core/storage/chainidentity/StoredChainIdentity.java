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
package global.goldenera.node.core.storage.chainidentity;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable chain identity persisted independently in both node data stores.
 * The manifest fingerprint is mandatory for sandbox identities and may be
 * absent for production identities created before manifests existed.
 */
public record StoredChainIdentity(
		int formatVersion,
		int carrierNetworkCode,
		String chainId,
		String genesisHash,
		String manifestFingerprint) {

	public static final int CURRENT_FORMAT_VERSION = 1;
	public static final int MAX_CHAIN_ID_BYTES = 128;

	private static final Pattern HASH = Pattern.compile("^0x[0-9a-f]{64}$");
	private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

	public StoredChainIdentity {
		if (formatVersion != CURRENT_FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported chain identity format version: " + formatVersion);
		}
		if (carrierNetworkCode < 0 || carrierNetworkCode > 255) {
			throw new IllegalArgumentException("Carrier network code must be in range 0..255");
		}
		Objects.requireNonNull(chainId, "chainId");
		if (chainId.isBlank() || chainId.getBytes(StandardCharsets.UTF_8).length > MAX_CHAIN_ID_BYTES
				|| chainId.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Chain ID must be non-blank, control-free and at most 128 UTF-8 bytes");
		}
		Objects.requireNonNull(genesisHash, "genesisHash");
		if (!HASH.matcher(genesisHash).matches()) {
			throw new IllegalArgumentException("Genesis hash must be a lowercase 0x-prefixed 32-byte hex value");
		}
		if (manifestFingerprint != null && !FINGERPRINT.matcher(manifestFingerprint).matches()) {
			throw new IllegalArgumentException("Manifest fingerprint must be a lowercase SHA-256 hex value");
		}
	}
}
