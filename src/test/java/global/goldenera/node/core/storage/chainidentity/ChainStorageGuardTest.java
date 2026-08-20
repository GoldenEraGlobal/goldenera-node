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

import static global.goldenera.node.core.storage.chainidentity.ChainStorageGuardResult.BACKFILLED_VERIFIED_PRODUCTION;
import static global.goldenera.node.core.storage.chainidentity.ChainStorageGuardResult.BACKFILLED_VERIFIED_DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.ChainStorageGuardResult.INITIALIZED_FRESH;
import static global.goldenera.node.core.storage.chainidentity.ChainStorageGuardResult.VERIFIED_EXISTING;
import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.ALLOW_VERIFIED_PRODUCTION;
import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.ALLOW_VERIFIED_DEVELOPMENT;
import static global.goldenera.node.core.storage.chainidentity.LegacyProductionBackfillPolicy.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChainStorageGuardTest {

	private static final String GENESIS = "0x" + "a".repeat(64);
	private static final StoredChainIdentity SANDBOX = new StoredChainIdentity(
			1, 1, "sandbox-123", GENESIS, "b".repeat(64));
	private static final StoredChainIdentity OTHER = new StoredChainIdentity(
			1, 1, "sandbox-other", GENESIS, "c".repeat(64));
	private static final StoredChainIdentity PRODUCTION = new StoredChainIdentity(
			1, 0, "mainnet", GENESIS, null);
	private static final StoredChainIdentity DEVELOPMENT = new StoredChainIdentity(
			1, 1, "development-testnet", GENESIS, null);

	@Test
	void bindsOnlyTheAuthoritativeRocksIdentityAndIsIdempotent() {
		MemoryStore rocks = new MemoryStore();
		ChainStorageGuard guard = new ChainStorageGuard(rocks);
		ChainStorageGuardRequest request = sandboxRequest(false);

		assertThat(guard.verifyAndBind(request)).isEqualTo(INITIALIZED_FRESH);
		assertThat(guard.verifyAndBind(request)).isEqualTo(VERIFIED_EXISTING);
		assertThat(rocks.identity).contains(SANDBOX);
		assertThat(rocks.bindAttempts).isOne();
	}

	@Test
	void rejectsPersistedMismatchWithoutWriting() {
		MemoryStore rocks = new MemoryStore(OTHER);

		assertThatThrownBy(() -> new ChainStorageGuard(rocks).verifyAndBind(sandboxRequest(true)))
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("RocksDB chain identity mismatch");
		assertThat(rocks.bindAttempts).isZero();
	}

	@Test
	void rejectsUnguardedSandboxConsensusData() {
		MemoryStore rocks = new MemoryStore();

		assertThatThrownBy(() -> new ChainStorageGuard(rocks).verifyAndBind(sandboxRequest(true)))
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("without an identity guard");
		assertThat(rocks.bindAttempts).isZero();
	}

	@Test
	void knownProductionLegacyBackfillRequiresExplicitVerifiedPolicy() {
		MemoryStore denied = new MemoryStore();
		assertThatThrownBy(() -> new ChainStorageGuard(denied).verifyAndBind(
				new ChainStorageGuardRequest(PRODUCTION, false, true, DENY)))
				.isInstanceOf(ChainStorageGuardException.class);
		assertThat(denied.bindAttempts).isZero();

		MemoryStore allowed = new MemoryStore();
		assertThat(new ChainStorageGuard(allowed).verifyAndBind(
				new ChainStorageGuardRequest(PRODUCTION, false, true, ALLOW_VERIFIED_PRODUCTION)))
				.isEqualTo(BACKFILLED_VERIFIED_PRODUCTION);
		assertThat(allowed.identity).contains(PRODUCTION);
	}

	@Test
	void verifiedDevelopmentLegacyBackfillIsDistinctFromProductionAuthorization() {
		MemoryStore allowed = new MemoryStore();
		assertThat(new ChainStorageGuard(allowed).verifyAndBind(
				new ChainStorageGuardRequest(DEVELOPMENT, false, true, ALLOW_VERIFIED_DEVELOPMENT)))
				.isEqualTo(BACKFILLED_VERIFIED_DEVELOPMENT);
		assertThat(allowed.identity).contains(DEVELOPMENT);
	}

	@Test
	void rechecksRocksAfterBindingAndRejectsConcurrentWrongIdentity() {
		MemoryStore rocks = new MemoryStore();
		rocks.identityOnBind = OTHER;

		assertThatThrownBy(() -> new ChainStorageGuard(rocks).verifyAndBind(sandboxRequest(false)))
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("RocksDB chain identity mismatch");
	}

	@Test
	void sandboxRequestRequiresFingerprintAndRejectsProductionBackfillPolicy() {
		assertThatIllegalArgumentException().isThrownBy(() ->
				new ChainStorageGuardRequest(PRODUCTION, true, false, DENY));
		assertThatIllegalArgumentException().isThrownBy(() ->
				new ChainStorageGuardRequest(SANDBOX, true, false, ALLOW_VERIFIED_PRODUCTION));
		assertThatIllegalArgumentException().isThrownBy(() ->
				new ChainStorageGuardRequest(SANDBOX, true, false, ALLOW_VERIFIED_DEVELOPMENT));
	}

	private ChainStorageGuardRequest sandboxRequest(boolean rocksHasData) {
		return new ChainStorageGuardRequest(SANDBOX, true, rocksHasData, DENY);
	}

	private static final class MemoryStore implements ChainIdentityStore {
		private Optional<StoredChainIdentity> identity;
		private StoredChainIdentity identityOnBind;
		private int bindAttempts;

		private MemoryStore() {
			identity = Optional.empty();
		}

		private MemoryStore(StoredChainIdentity identity) {
			this.identity = Optional.of(identity);
		}

		@Override
		public String name() {
			return "RocksDB";
		}

		@Override
		public Optional<StoredChainIdentity> find() {
			return identity;
		}

		@Override
		public void bindIfAbsent(StoredChainIdentity requestedIdentity) {
			bindAttempts++;
			if (identity.isEmpty()) {
				identity = Optional.ofNullable(identityOnBind == null ? requestedIdentity : identityOnBind);
			}
		}
	}
}
