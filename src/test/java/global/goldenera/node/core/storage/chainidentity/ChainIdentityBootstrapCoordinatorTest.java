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

import static global.goldenera.node.core.storage.chainidentity.ChainStorageGuardResult.INITIALIZED_FRESH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChainIdentityBootstrapCoordinatorTest {

	private static final StoredChainIdentity IDENTITY = new StoredChainIdentity(
			1, 1, "sandbox-" + "a".repeat(32), "0x" + "a".repeat(64), "b".repeat(64));
	private static final ChainIdentityExpectation EXPECTATION = new ChainIdentityExpectation(
			IDENTITY, ChainIdentityExecutionScope.SANDBOX);
	private static final ChainIdentityPreflightDecision DECISION = new ChainIdentityPreflightDecision(
			EXPECTATION,
			new ChainStoragePreflightObservation("RocksDB", true, Optional.empty(), false, Optional.empty()),
			LegacyProductionBackfillPolicy.DENY);

	@Test
	void refusesBindingUntilReadOnlyPhaseCompleted() {
		ChainStorageGuard guard = mock(ChainStorageGuard.class);
		ChainIdentityBootstrapCoordinator coordinator = new ChainIdentityBootstrapCoordinator(
				() -> EXPECTATION, mock(ChainIdentityPreflight.class), guard);

		assertThatThrownBy(() -> coordinator.bindAfterGenesisVerification(expectation -> {
		}))
				.hasMessageContaining("before the read-only path preflight");
		verifyNoInteractions(guard);
	}

	@Test
	void carriesTheExactDecisionIntoPostMigrationGuard() {
		ChainIdentityPreflight preflight = mock(ChainIdentityPreflight.class);
		ChainStorageGuard guard = mock(ChainStorageGuard.class);
		when(preflight.inspect(EXPECTATION)).thenReturn(DECISION);
		when(guard.verifyAndBind(DECISION.toGuardRequest())).thenReturn(INITIALIZED_FRESH);
		ChainIdentityBootstrapCoordinator coordinator = new ChainIdentityBootstrapCoordinator(
				() -> EXPECTATION, preflight, guard);

		assertThat(coordinator.preflightBeforeOpeningStorage()).isSameAs(DECISION);
		assertThat(coordinator.bindAfterGenesisVerification(expectation -> {
		})).isEqualTo(INITIALIZED_FRESH);
		verify(preflight, times(2)).inspect(EXPECTATION);
		verify(guard).verifyAndBind(DECISION.toGuardRequest());
	}

	@Test
	void localGenesisVerificationFailurePreventsEveryIdentityWrite() {
		ChainIdentityPreflight preflight = mock(ChainIdentityPreflight.class);
		ChainStorageGuard guard = mock(ChainStorageGuard.class);
		when(preflight.inspect(EXPECTATION)).thenReturn(DECISION);
		ChainIdentityBootstrapCoordinator coordinator = new ChainIdentityBootstrapCoordinator(
				() -> EXPECTATION,
				preflight,
				guard);
		coordinator.preflightBeforeOpeningStorage();

		assertThatThrownBy(() -> coordinator.bindAfterGenesisVerification(expectation -> {
			throw new ChainStorageGuardException("local genesis mismatch");
		}))
				.hasMessageContaining("local genesis mismatch");
		verifyNoInteractions(guard);
	}
}
