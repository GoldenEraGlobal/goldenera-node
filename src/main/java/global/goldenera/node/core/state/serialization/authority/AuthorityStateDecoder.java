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
package global.goldenera.node.core.state.serialization.authority;

import java.util.EnumMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.core.state.serialization.authority.impl.AuthorityStateV1DecodingStrategy;
import global.goldenera.node.shared.consensus.state.AuthorityState;
import global.goldenera.node.shared.enums.state.AuthorityStateVersion;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;

public class AuthorityStateDecoder {

	public static final AuthorityStateDecoder INSTANCE = new AuthorityStateDecoder();
	private final Map<AuthorityStateVersion, AuthorityStateDecodingStrategy> strategies = new EnumMap<>(
			AuthorityStateVersion.class);

	private AuthorityStateDecoder() {
		strategies.put(AuthorityStateVersion.V1, new AuthorityStateV1DecodingStrategy());
	}

	public AuthorityState decode(Bytes rlpBytes) {
		if (rlpBytes == null || rlpBytes.isEmpty()) {
			throw new GEFailedException("Cannot decode empty bytes");
		}
		RLP.validate(rlpBytes);
		RLPInput input = RLP.input(rlpBytes);
		input.enterList();

		// Read version
		int versionCode = input.readIntScalar();
		AuthorityStateVersion version = AuthorityStateVersion.fromCode(versionCode);
		if (version == null) {
			throw new GEFailedException("Unknown AuthorityState version: " + versionCode);
		}
		AuthorityStateDecodingStrategy strategy = strategies.get(version);
		if (strategy == null) {
			throw new GEFailedException("Unknown AuthorityState version: " + version);
		}

		AuthorityState state = strategy.decode(input);
		input.leaveList();
		return state;
	}
}
