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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class P2PChainCapabilityCodec {

	public static final String PREFIX = "ge.chain.v1:";
	public static final int MAX_CAPABILITIES = 32;
	public static final int MAX_CAPABILITY_BYTES = 512;
	private static final int HASH_BYTES = 32;
	private static final int MAX_BINARY_BYTES = 1 + 1 + 128 + HASH_BYTES + 1 + HASH_BYTES;
	private static final Pattern SAFE_CAPABILITY = Pattern.compile("[\\x21-\\x7e]+");
	private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

	public String encode(P2PChainCapability capability) {
		byte[] chainId = capability.chainId().getBytes(StandardCharsets.UTF_8);
		byte[] genesis = HexFormat.of().parseHex(capability.genesisHash().substring(2));
		byte[] fingerprint = capability.manifestFingerprint() == null
				? null
				: HexFormat.of().parseHex(capability.manifestFingerprint());
		ByteBuffer binary = ByteBuffer.allocate(1 + 1 + chainId.length + HASH_BYTES + 1
				+ (fingerprint == null ? 0 : HASH_BYTES));
		binary.put((byte) capability.carrierNetworkCode());
		binary.put((byte) chainId.length);
		binary.put(chainId);
		binary.put(genesis);
		binary.put((byte) (fingerprint == null ? 0 : 1));
		if (fingerprint != null) {
			binary.put(fingerprint);
		}
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(binary.array());
	}

	public P2PChainCapability decode(String token) {
		if (token == null || token.getBytes(StandardCharsets.US_ASCII).length > MAX_CAPABILITY_BYTES
				|| !token.startsWith(PREFIX)) {
			throw new IllegalArgumentException("Invalid GoldenEra chain capability prefix");
		}
		String encoded = token.substring(PREFIX.length());
		if (!BASE64URL.matcher(encoded).matches()) {
			throw new IllegalArgumentException("Invalid GoldenEra chain capability encoding");
		}
		byte[] binary;
		try {
			binary = Base64.getUrlDecoder().decode(encoded);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid GoldenEra chain capability encoding", e);
		}
		if (binary.length < 1 + 1 + 1 + HASH_BYTES + 1 || binary.length > MAX_BINARY_BYTES) {
			throw new IllegalArgumentException("Invalid GoldenEra chain capability length");
		}
		ByteBuffer input = ByteBuffer.wrap(binary);
		int carrier = Byte.toUnsignedInt(input.get());
		int chainIdLength = Byte.toUnsignedInt(input.get());
		if (chainIdLength < 1 || chainIdLength > 128 || input.remaining() < chainIdLength + HASH_BYTES + 1) {
			throw new IllegalArgumentException("Invalid GoldenEra chain ID length");
		}
		byte[] chainIdBytes = new byte[chainIdLength];
		input.get(chainIdBytes);
		String chainId = strictUtf8(chainIdBytes);
		byte[] genesis = new byte[HASH_BYTES];
		input.get(genesis);
		int fingerprintPresent = Byte.toUnsignedInt(input.get());
		String fingerprint;
		if (fingerprintPresent == 0 && !input.hasRemaining()) {
			fingerprint = null;
		} else if (fingerprintPresent == 1 && input.remaining() == HASH_BYTES) {
			byte[] fingerprintBytes = new byte[HASH_BYTES];
			input.get(fingerprintBytes);
			fingerprint = HexFormat.of().formatHex(fingerprintBytes);
		} else {
			throw new IllegalArgumentException("Invalid GoldenEra manifest fingerprint marker or length");
		}
		P2PChainCapability capability = new P2PChainCapability(
				carrier,
				chainId,
				"0x" + HexFormat.of().formatHex(genesis),
				fingerprint);
		if (!encode(capability).equals(token)) {
			throw new IllegalArgumentException("Non-canonical GoldenEra chain capability");
		}
		return capability;
	}

	public Optional<P2PChainCapability> find(List<String> capabilities) {
		if (capabilities == null || capabilities.size() > MAX_CAPABILITIES) {
			throw new IllegalArgumentException("Invalid P2P capability count");
		}
		Set<String> unique = new HashSet<>();
		P2PChainCapability chainCapability = null;
		for (String value : capabilities) {
			if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_CAPABILITY_BYTES
					|| !SAFE_CAPABILITY.matcher(value).matches()) {
				throw new IllegalArgumentException("Invalid P2P capability value");
			}
			if (!unique.add(value)) {
				throw new IllegalArgumentException("Duplicate P2P capability");
			}
			if (value.startsWith(PREFIX)) {
				if (chainCapability != null) {
					throw new IllegalArgumentException("Multiple GoldenEra chain capabilities");
				}
				chainCapability = decode(value);
			} else if (value.startsWith("ge.chain")) {
				throw new IllegalArgumentException("Malformed or unsupported GoldenEra chain capability");
			}
		}
		return Optional.ofNullable(chainCapability);
	}

	private String strictUtf8(byte[] input) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(input))
					.toString();
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("GoldenEra chain ID is not canonical UTF-8", e);
		}
	}
}
