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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class StoredChainIdentityCodecTest {

	private static final String GENESIS = "0x" + "1".repeat(64);
	private static final String FINGERPRINT = "2".repeat(64);

	@Test
	void roundTripsSandboxAndProductionIdentities() {
		StoredChainIdentity sandbox = new StoredChainIdentity(1, 1, "sandbox-žluťoučký", GENESIS, FINGERPRINT);
		StoredChainIdentity production = new StoredChainIdentity(1, 0, "mainnet", GENESIS, null);

		assertThat(StoredChainIdentityCodec.decode(StoredChainIdentityCodec.encode(sandbox))).isEqualTo(sandbox);
		assertThat(StoredChainIdentityCodec.decode(StoredChainIdentityCodec.encode(production))).isEqualTo(production);
	}

	@Test
	void rejectsTruncatedTrailingAndWrongMagicEncodings() {
		byte[] valid = StoredChainIdentityCodec.encode(
				new StoredChainIdentity(1, 1, "sandbox", GENESIS, FINGERPRINT));
		byte[] wrongMagic = valid.clone();
		wrongMagic[0] ^= 1;

		assertThatIllegalArgumentException().isThrownBy(
				() -> StoredChainIdentityCodec.decode(Arrays.copyOf(valid, valid.length - 1)));
		assertThatIllegalArgumentException().isThrownBy(
				() -> StoredChainIdentityCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
		assertThatIllegalArgumentException().isThrownBy(() -> StoredChainIdentityCodec.decode(wrongMagic));
	}

	@Test
	void identityValidatesAllPersistenceBoundaries() {
		assertThatIllegalArgumentException().isThrownBy(
				() -> new StoredChainIdentity(2, 1, "sandbox", GENESIS, FINGERPRINT));
		assertThatIllegalArgumentException().isThrownBy(
				() -> new StoredChainIdentity(1, 256, "sandbox", GENESIS, FINGERPRINT));
		assertThatIllegalArgumentException().isThrownBy(
				() -> new StoredChainIdentity(1, 1, " ", GENESIS, FINGERPRINT));
		assertThatIllegalArgumentException().isThrownBy(
				() -> new StoredChainIdentity(1, 1, "sandbox", "abcd", FINGERPRINT));
		assertThatIllegalArgumentException().isThrownBy(
				() -> new StoredChainIdentity(1, 1, "sandbox", GENESIS, "abcd"));
	}
}
