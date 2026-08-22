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
package global.goldenera.node.core.sync.snapshot.archive;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.serialization.state.authority.AuthorityStateDecoder;
import global.goldenera.cryptoj.serialization.state.authority.AuthorityStateEncoder;
import global.goldenera.cryptoj.serialization.state.token.TokenStateDecoder;
import global.goldenera.cryptoj.serialization.state.token.TokenStateEncoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateDecoder;
import global.goldenera.cryptoj.serialization.state.validator.ValidatorStateEncoder;

/** Strict canonical state encoding shared by export, verification and import. */
public final class CoreSnapshotEntityStateCodec {

	private CoreSnapshotEntityStateCodec() {
	}

	public static Bytes encodeToken(TokenState state) {
		return TokenStateEncoder.INSTANCE.encode(state);
	}

	public static Bytes encodeAuthority(AuthorityState state) {
		return AuthorityStateEncoder.INSTANCE.encode(state);
	}

	public static Bytes encodeValidator(ValidatorState state) {
		return ValidatorStateEncoder.INSTANCE.encode(state);
	}

	public static Object decodeCanonical(CoreSnapshotEntityType type, Bytes encoded) {
		Object decoded = switch (type) {
			case TOKEN -> TokenStateDecoder.INSTANCE.decode(encoded);
			case AUTHORITY -> AuthorityStateDecoder.INSTANCE.decode(encoded);
			case VALIDATOR -> ValidatorStateDecoder.INSTANCE.decode(encoded);
		};
		Bytes canonical = encode(type, decoded);
		if (!canonical.equals(encoded)) {
			throw new IllegalArgumentException("Entity state is not canonically encoded");
		}
		boolean exists = switch (type) {
			case TOKEN -> ((TokenState) decoded).exists();
			case AUTHORITY -> ((AuthorityState) decoded).exists();
			case VALIDATOR -> ((ValidatorState) decoded).exists();
		};
		if (!exists) {
			throw new IllegalArgumentException("Entity sidecar cannot contain a zero/non-existent state");
		}
		return decoded;
	}

	public static Bytes encode(CoreSnapshotEntityType type, Object state) {
		return switch (type) {
			case TOKEN -> encodeToken((TokenState) state);
			case AUTHORITY -> encodeAuthority((AuthorityState) state);
			case VALIDATOR -> encodeValidator((ValidatorState) state);
		};
	}
}
