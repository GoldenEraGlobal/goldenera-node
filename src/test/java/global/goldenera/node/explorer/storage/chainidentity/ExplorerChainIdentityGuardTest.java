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
package global.goldenera.node.explorer.storage.chainidentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.storage.chainidentity.ChainStoragePreflightObservation;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

class ExplorerChainIdentityGuardTest {

	private static final StoredChainIdentity EXPECTED = new StoredChainIdentity(
			1, 0, "mainnet", "0x" + "a".repeat(64), null);
	private static final StoredChainIdentity OTHER = new StoredChainIdentity(
			1, 1, "testnet", "0x" + "b".repeat(64), null);

	@Test
	void bindsFreshMirrorAgainstAuthoritativeIdentity() {
		PostgresChainStoragePreflightProbe probe = mock(PostgresChainStoragePreflightProbe.class);
		PostgresChainIdentityRepository repository = mock(PostgresChainIdentityRepository.class);
		when(probe.inspect()).thenReturn(observation(Optional.empty(), false, Optional.empty()));
		when(repository.find()).thenReturn(Optional.of(EXPECTED));

		assertThat(new ExplorerChainIdentityGuard(probe, repository).verifyAndBind(EXPECTED))
				.isEqualTo(EXPECTED);
		verify(repository).bindIfAbsent(EXPECTED);
	}

	@Test
	void rejectsOccupiedExplorerWithoutCanonicalGenesisProof() {
		PostgresChainStoragePreflightProbe probe = mock(PostgresChainStoragePreflightProbe.class);
		PostgresChainIdentityRepository repository = mock(PostgresChainIdentityRepository.class);
		when(probe.inspect()).thenReturn(observation(Optional.empty(), true, Optional.empty()));

		assertThatThrownBy(() -> new ExplorerChainIdentityGuard(probe, repository)
				.verifyAndBind(EXPECTED))
				.isInstanceOf(ExplorerChainIdentityException.class)
				.extracting("state").isEqualTo(ExplorerReadinessState.STORAGE_CORRUPT);
		assertThatThrownBy(() -> new ExplorerChainIdentityGuard(probe, repository)
				.verifyAndBind(EXPECTED))
				.hasMessageContaining("cannot prove its genesis");
		verify(repository, never()).bindIfAbsent(EXPECTED);
	}

	@Test
	void rejectsExistingMirrorMismatchBeforeWriting() {
		PostgresChainStoragePreflightProbe probe = mock(PostgresChainStoragePreflightProbe.class);
		PostgresChainIdentityRepository repository = mock(PostgresChainIdentityRepository.class);
		when(probe.inspect()).thenReturn(observation(Optional.of(OTHER), false, Optional.empty()));

		assertThatThrownBy(() -> new ExplorerChainIdentityGuard(probe, repository)
				.verifyAndBind(EXPECTED))
				.isInstanceOf(ExplorerChainIdentityException.class)
				.extracting("state").isEqualTo(ExplorerReadinessState.IDENTITY_MISMATCH);
		assertThatThrownBy(() -> new ExplorerChainIdentityGuard(probe, repository)
				.verifyAndBind(EXPECTED))
				.hasMessageContaining("does not match authoritative core identity");
		verify(repository, never()).bindIfAbsent(EXPECTED);
	}

	private ChainStoragePreflightObservation observation(
			Optional<StoredChainIdentity> identity,
			boolean occupied,
			Optional<String> genesis) {
		return new ChainStoragePreflightObservation(
				"PostgreSQL", true, identity, occupied, genesis);
	}
}
