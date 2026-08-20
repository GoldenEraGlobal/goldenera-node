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
import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.KNOWN_PRODUCTION;
import static global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope.SANDBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChainIdentityPreflightTest {

	private static final String MAINNET_GENESIS =
			"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f";
	private static final StoredChainIdentity MAINNET =
			new StoredChainIdentity(1, 0, "mainnet", MAINNET_GENESIS, null);
	private static final StoredChainIdentity SANDBOX_IDENTITY = new StoredChainIdentity(
			1, 1, "sandbox-" + "a".repeat(32), "0x" + "a".repeat(64), "b".repeat(64));

	@Test
	void acceptsFreshStoresWithoutLegacyAuthorization() {
		ChainIdentityPreflightDecision decision = preflight(
				observation("RocksDB", true, Optional.empty(), false, Optional.empty()),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()))
				.inspect(new ChainIdentityExpectation(SANDBOX_IDENTITY, SANDBOX));

		assertThat(decision.legacyProductionBackfillPolicy()).isEqualTo(DENY);
		assertThat(decision.toGuardRequest().rocksHasChainData()).isFalse();
	}

	@Test
	void rejectsExistingMismatchBeforeMigration() {
		StoredChainIdentity other = new StoredChainIdentity(
				1, 1, "sandbox-" + "c".repeat(32), "0x" + "c".repeat(64), "d".repeat(64));
		ChainIdentityPreflight preflight = preflight(
				observation("RocksDB", true, Optional.of(other), false, Optional.empty()),
				observation("PostgreSQL", true, Optional.empty(), false, Optional.empty()));

		assertThatThrownBy(() -> preflight.inspect(new ChainIdentityExpectation(SANDBOX_IDENTITY, SANDBOX)))
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("before writable storage open");
	}

	@Test
	void rejectsMatchingIdentityWhenOccupiedRocksCannotProveGenesis() {
		ChainIdentityPreflight preflight = preflight(
				observation("RocksDB", true, Optional.of(SANDBOX_IDENTITY), true, Optional.empty()),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()));

		assertThatThrownBy(() -> preflight.inspect(
				new ChainIdentityExpectation(SANDBOX_IDENTITY, SANDBOX)))
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("cannot prove its stored genesis");
	}

	@Test
	void sandboxCanNeverBackfillUnguardedData() {
		ChainIdentityPreflight preflight = preflight(
				observation("RocksDB", true, Optional.empty(), true, Optional.of(SANDBOX_IDENTITY.genesisHash())),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()));

		assertThatThrownBy(() -> preflight.inspect(new ChainIdentityExpectation(SANDBOX_IDENTITY, SANDBOX)))
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("SANDBOX storage");
	}

	@Test
	void productionLegacyBackfillRequiresDecodedRocksGenesisProof() {
		ChainIdentityPreflight valid = preflight(
				observation("RocksDB", true, Optional.empty(), true, Optional.of(MAINNET_GENESIS)),
				observation("PostgreSQL", false, Optional.empty(), true, Optional.of(MAINNET_GENESIS)));

		assertThat(valid.inspect(new ChainIdentityExpectation(MAINNET, KNOWN_PRODUCTION))
				.legacyProductionBackfillPolicy())
				.isEqualTo(ALLOW_VERIFIED_PRODUCTION);

		ChainIdentityPreflight missingProof = preflight(
				observation("RocksDB", true, Optional.empty(), true, Optional.empty()),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()));
		assertThatThrownBy(() -> missingProof.inspect(new ChainIdentityExpectation(MAINNET, KNOWN_PRODUCTION)))
				.hasMessageContaining("does not prove");
	}

	@Test
	void knownProductionScopeRejectsInventedIdentityBeforeInspection() {
		StoredChainIdentity invented = new StoredChainIdentity(1, 0, "mainnet-clone", MAINNET_GENESIS, null);

		assertThatThrownBy(() -> new ChainIdentityExpectation(invented, KNOWN_PRODUCTION))
				.hasMessageContaining("exact compile-time registry");
	}

	@Test
	void developmentBackfillRequiresTheExactLocallyReconstructedGenesis() {
		StoredChainIdentity development = new StoredChainIdentity(
				1, 1, "local-dev", "0x" + "e".repeat(64), null);
		ChainIdentityPreflight fresh = preflight(
				observation("RocksDB", true, Optional.empty(), false, Optional.empty()),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()));
		assertThat(fresh.inspect(new ChainIdentityExpectation(development, DEVELOPMENT))
				.legacyProductionBackfillPolicy()).isEqualTo(DENY);

		ChainIdentityPreflight occupied = preflight(
				observation("RocksDB", true, Optional.empty(), true, Optional.of(development.genesisHash())),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()));
		assertThat(occupied.inspect(new ChainIdentityExpectation(development, DEVELOPMENT))
				.legacyProductionBackfillPolicy()).isEqualTo(ALLOW_VERIFIED_DEVELOPMENT);

		ChainIdentityPreflight missingProof = preflight(
				observation("RocksDB", true, Optional.empty(), true, Optional.empty()),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()));
		assertThatThrownBy(() -> missingProof.inspect(new ChainIdentityExpectation(development, DEVELOPMENT)))
				.hasMessageContaining("does not prove");

		ChainIdentityPreflight wrongGenesis = preflight(
				observation("RocksDB", true, Optional.empty(), true, Optional.of("0x" + "f".repeat(64))),
				observation("PostgreSQL", false, Optional.empty(), false, Optional.empty()));
		assertThatThrownBy(() -> wrongGenesis.inspect(new ChainIdentityExpectation(development, DEVELOPMENT)))
				.hasMessageContaining("stored genesis mismatch");
	}

	private ChainIdentityPreflight preflight(
			ChainStoragePreflightObservation rocks, ChainStoragePreflightObservation postgres) {
		return new ChainIdentityPreflight(() -> rocks,
				new KnownProductionLegacyStorageVerifier());
	}

	private ChainStoragePreflightObservation observation(
			String name,
			boolean identityStoreExists,
			Optional<StoredChainIdentity> identity,
			boolean hasData,
			Optional<String> genesis) {
		return new ChainStoragePreflightObservation(
				name, identityStoreExists, identity, hasData, genesis);
	}
}
