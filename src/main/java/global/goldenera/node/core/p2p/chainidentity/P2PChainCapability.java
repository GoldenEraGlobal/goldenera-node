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
package global.goldenera.node.core.p2p.chainidentity;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

public record P2PChainCapability(
		int carrierNetworkCode,
		String chainId,
		String genesisHash,
		String manifestFingerprint) {

	private static final Pattern HASH = Pattern.compile("^0x[0-9a-f]{64}$");
	private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

	public P2PChainCapability {
		if (carrierNetworkCode < 0 || carrierNetworkCode > 255) {
			throw new IllegalArgumentException("Carrier network code must be in range 0..255");
		}
		Objects.requireNonNull(chainId, "chainId");
		int chainIdBytes = chainId.getBytes(StandardCharsets.UTF_8).length;
		if (chainId.isBlank() || chainIdBytes > StoredChainIdentity.MAX_CHAIN_ID_BYTES
				|| chainId.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Chain ID is invalid");
		}
		Objects.requireNonNull(genesisHash, "genesisHash");
		if (!HASH.matcher(genesisHash).matches()) {
			throw new IllegalArgumentException("Genesis hash is invalid");
		}
		if (manifestFingerprint != null && !FINGERPRINT.matcher(manifestFingerprint).matches()) {
			throw new IllegalArgumentException("Manifest fingerprint is invalid");
		}
	}

	public static P2PChainCapability from(StoredChainIdentity identity) {
		Objects.requireNonNull(identity, "identity");
		return new P2PChainCapability(
				identity.carrierNetworkCode(),
				identity.chainId(),
				identity.genesisHash(),
				identity.manifestFingerprint());
	}
}
