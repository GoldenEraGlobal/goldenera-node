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

import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.KNOWN_PRODUCTION;
import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.SANDBOX;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChainIdentityExpectationTest {

	private static final String MAINNET_GENESIS =
			"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f";

	@Test
	void sandboxRequiresTestnetCarrierHighEntropyIdFingerprintAndNonProductionGenesis() {
		StoredChainIdentity valid = sandbox(1, "sandbox-" + "a".repeat(32),
				"0x" + "c".repeat(64), "b".repeat(64));
		assertThatCode(() -> new ChainIdentityExpectation(valid, SANDBOX)).doesNotThrowAnyException();

		assertThatThrownBy(() -> new ChainIdentityExpectation(
				sandbox(0, valid.chainId(), valid.genesisHash(), valid.manifestFingerprint()), SANDBOX))
				.hasMessageContaining("TESTNET carrier");
		assertThatThrownBy(() -> new ChainIdentityExpectation(
				sandbox(1, "sandbox-short", valid.genesisHash(), valid.manifestFingerprint()), SANDBOX))
				.hasMessageContaining("high-entropy");
		assertThatThrownBy(() -> new ChainIdentityExpectation(
				sandbox(1, valid.chainId(), valid.genesisHash(), null), SANDBOX))
				.hasMessageContaining("manifest fingerprint");
		assertThatThrownBy(() -> new ChainIdentityExpectation(
				sandbox(1, valid.chainId(), MAINNET_GENESIS, valid.manifestFingerprint()), SANDBOX))
				.hasMessageContaining("known production genesis");
	}

	@Test
	void productionMustExactlyMatchCompileTimeRegistry() {
		assertThatCode(() -> new ChainIdentityExpectation(
				new StoredChainIdentity(1, 0, "mainnet", MAINNET_GENESIS, null), KNOWN_PRODUCTION))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> new ChainIdentityExpectation(
				new StoredChainIdentity(1, 0, "mainnet-copy", MAINNET_GENESIS, null), KNOWN_PRODUCTION))
				.hasMessageContaining("compile-time registry");
	}

	@Test
	void developmentAllowsFreshCustomGenesisButNeverManifestFingerprint() {
		assertThatCode(() -> new ChainIdentityExpectation(
				new StoredChainIdentity(1, 1, "local-dev", "0x" + "d".repeat(64), null), DEVELOPMENT))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> new ChainIdentityExpectation(
				new StoredChainIdentity(1, 1, "local-dev", "0x" + "d".repeat(64), "e".repeat(64)),
				DEVELOPMENT)).hasMessageContaining("sandbox fingerprint");
	}

	private StoredChainIdentity sandbox(
			int carrier, String chainId, String genesisHash, String fingerprint) {
		return new StoredChainIdentity(1, carrier, chainId, genesisHash, fingerprint);
	}
}
