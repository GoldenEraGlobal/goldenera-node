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

import static global.goldenera.node.core.storage.chainidentity.ChainStorageGuardResult.BACKFILLED_VERIFIED_DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.ChainStorageGuardResult.BACKFILLED_VERIFIED_PRODUCTION;
import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.ALLOW_VERIFIED_DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.ALLOW_VERIFIED_PRODUCTION;

import java.util.Optional;

/**
 * Owns the single authoritative chain identity in the consensus RocksDB. A
 * PostgreSQL/explorer identity is only a downstream mirror and is intentionally
 * absent from this dependency boundary.
 */
public class ChainStorageGuard {

	private final ChainIdentityStore rocksStore;

	public ChainStorageGuard(ChainIdentityStore rocksStore) {
		this.rocksStore = rocksStore;
	}

	public synchronized ChainStorageGuardResult verifyAndBind(ChainStorageGuardRequest request) {
		StoredChainIdentity expected = request.expectedIdentity();
		Optional<StoredChainIdentity> actual = rocksStore.find();
		if (actual.isPresent()) {
			assertExpected(actual.orElseThrow(), expected);
			return ChainStorageGuardResult.VERIFIED_EXISTING;
		}

		if (request.rocksHasChainData() && !productionBackfillAllowed(request)) {
			String domain = request.sandbox() ? "Sandbox" : "Non-production";
			throw new ChainStorageGuardException(domain
					+ " RocksDB contains consensus data without an identity guard; "
					+ "verified known-production backfill is required");
		}

		rocksStore.bindIfAbsent(expected);
		Optional<StoredChainIdentity> persisted = rocksStore.find();
		if (persisted.isEmpty()) {
			throw new ChainStorageGuardException("RocksDB failed to persist the authoritative chain identity");
		}
		assertExpected(persisted.orElseThrow(), expected);
		return request.rocksHasChainData()
				? backfillResult(request.legacyProductionBackfillPolicy())
				: ChainStorageGuardResult.INITIALIZED_FRESH;
	}

	private boolean productionBackfillAllowed(ChainStorageGuardRequest request) {
		return !request.sandbox()
				&& request.legacyProductionBackfillPolicy() != LegacyProductionBackfillPolicy.DENY;
	}

	private ChainStorageGuardResult backfillResult(LegacyProductionBackfillPolicy policy) {
		if (policy == ALLOW_VERIFIED_PRODUCTION) {
			return BACKFILLED_VERIFIED_PRODUCTION;
		}
		if (policy == ALLOW_VERIFIED_DEVELOPMENT) {
			return BACKFILLED_VERIFIED_DEVELOPMENT;
		}
		throw new IllegalStateException("Unguarded storage cannot be backfilled without a verified policy");
	}

	private void assertExpected(StoredChainIdentity actual, StoredChainIdentity expected) {
		if (!expected.equals(actual)) {
			throw new ChainStorageGuardException("RocksDB chain identity mismatch: expected "
					+ describe(expected) + " but found " + describe(actual));
		}
	}

	private String describe(StoredChainIdentity identity) {
		return "format=" + identity.formatVersion()
				+ ", carrier=" + identity.carrierNetworkCode()
				+ ", chainId=" + identity.chainId()
				+ ", genesisHash=" + identity.genesisHash()
				+ ", manifestFingerprint=" + identity.manifestFingerprint();
	}
}
