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

import java.util.Map;
import java.util.Optional;

/**
 * Compile-time registry of production chain identities. Runtime properties must
 * never be able to extend this registry or authorize a legacy storage backfill.
 */
public final class KnownProductionChainIdentityRegistry {

	private static final Map<ProductionChain, String> GENESIS_HASHES = Map.of(
			new ProductionChain(0, "mainnet"),
			"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f",
			new ProductionChain(1, "testnet"),
			"0xf403f287a52b794eba7645d193c53c2dfa084a52db11ad94d70d0c79107c05cc");

	private KnownProductionChainIdentityRegistry() {
	}

	public static Optional<String> expectedGenesisHash(int carrierNetworkCode, String chainId) {
		return Optional.ofNullable(GENESIS_HASHES.get(new ProductionChain(carrierNetworkCode, chainId)));
	}

	public static boolean isKnownProductionIdentity(StoredChainIdentity identity) {
		return identity.manifestFingerprint() == null
				&& expectedGenesisHash(identity.carrierNetworkCode(), identity.chainId())
						.filter(identity.genesisHash()::equals)
						.isPresent();
	}

	public static boolean containsGenesisHash(String genesisHash) {
		return GENESIS_HASHES.containsValue(genesisHash);
	}

	private record ProductionChain(int carrierNetworkCode, String chainId) {
	}
}
