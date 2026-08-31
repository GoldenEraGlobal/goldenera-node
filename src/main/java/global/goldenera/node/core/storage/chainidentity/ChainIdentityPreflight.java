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

import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.ALLOW_VERIFIED_PRODUCTION;
import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.ALLOW_VERIFIED_DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.DENY;

import java.util.List;
import java.util.Optional;

/** Performs the mutation-free first phase before writable RocksDB may open. */
public final class ChainIdentityPreflight {

	private final ChainStoragePreflightProbe rocksProbe;
	private final KnownProductionLegacyStorageVerifier productionVerifier;

	public ChainIdentityPreflight(
			ChainStoragePreflightProbe rocksProbe,
			KnownProductionLegacyStorageVerifier productionVerifier) {
		this.rocksProbe = rocksProbe;
		this.productionVerifier = productionVerifier;
	}

	public ChainIdentityPreflightDecision inspect(ChainIdentityExpectation expectation) {
		ChainStoragePreflightObservation rocks = rocksProbe.inspect();

		assertExpectedIfPresent(rocks, expectation.identity());
		List<ChainStoragePreflightObservation> unguardedOccupied = rocks.hasChainData()
				&& rocks.identity().isEmpty() ? List.of(rocks) : List.of();

		LegacyProductionBackfillPolicy policy = DENY;
		if (!unguardedOccupied.isEmpty()) {
			if (expectation.scope() == ChainIdentityExecutionScope.DEVELOPMENT) {
				if (rocks.observedGenesisHash().isEmpty()) {
					throw rejected("Development storage does not prove the configured genesis");
				}
				policy = ALLOW_VERIFIED_DEVELOPMENT;
			} else if (!expectation.knownProduction()) {
				throw rejected(expectation.scope()
						+ " storage contains chain data without an identity guard");
			} else {
				if (!productionVerifier.verifies(expectation.identity(), unguardedOccupied)) {
					throw rejected("Legacy production storage does not prove the compile-time-known genesis");
				}
				policy = ALLOW_VERIFIED_PRODUCTION;
			}
		}
		return new ChainIdentityPreflightDecision(expectation, rocks, policy);
	}

	private void assertExpectedIfPresent(
			ChainStoragePreflightObservation observation, StoredChainIdentity expected) {
		Optional<StoredChainIdentity> actual = observation.identity();
		if (actual.isPresent() && !expected.equals(actual.orElseThrow())) {
			throw rejected(observation.storeName() + " chain identity mismatch before writable storage open");
		}
		if (observation.hasChainData() && actual.isPresent()
				&& observation.observedGenesisHash().isEmpty()) {
			throw rejected(observation.storeName()
					+ " has guarded chain data but cannot prove its stored genesis");
		}
		if (observation.observedGenesisHash().isPresent()
				&& !expected.genesisHash().equals(observation.observedGenesisHash().orElseThrow())) {
			throw rejected(observation.storeName() + " stored genesis mismatch before writable storage open");
		}
	}

	private ChainStorageGuardException rejected(String message) {
		return new ChainStorageGuardException("Chain identity preflight rejected startup: " + message);
	}
}
