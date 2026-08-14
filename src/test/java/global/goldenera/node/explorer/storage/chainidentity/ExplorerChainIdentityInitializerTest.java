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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.ChainStorageGuardException;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

class ExplorerChainIdentityInitializerTest {

	private static final StoredChainIdentity EXPECTED = new StoredChainIdentity(
			1, 0, "mainnet", "0x" + "a".repeat(64), null);

	@Test
	void unavailableDatabaseMarksExplorerNotReadyWithoutThrowing() {
		AuthoritativeChainIdentityProvider identityProvider = identityProvider();
		ExplorerSchemaMigrator migrator = mock(ExplorerSchemaMigrator.class);
		ExplorerChainIdentityGuard guard = mock(ExplorerChainIdentityGuard.class);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		doThrow(new ChainStorageGuardException("connection failed", new SQLException("refused")))
				.when(migrator).migrate();

		new ExplorerChainIdentityInitializer(identityProvider, migrator, guard, readiness)
				.afterPropertiesSet();

		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.DATABASE_UNAVAILABLE);
		assertThat(readiness.isReady()).isFalse();
		verify(guard, never()).verifyAndBind(EXPECTED);
	}

	@Test
	void mirrorMismatchMarksExplorerNotReadyWithoutFailingCoreBoundary() {
		AuthoritativeChainIdentityProvider identityProvider = identityProvider();
		ExplorerSchemaMigrator migrator = mock(ExplorerSchemaMigrator.class);
		ExplorerChainIdentityGuard guard = mock(ExplorerChainIdentityGuard.class);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		doThrow(new ExplorerChainIdentityException(
				ExplorerReadinessState.IDENTITY_MISMATCH, "wrong chain"))
				.when(guard).verifyAndBind(EXPECTED);

		new ExplorerChainIdentityInitializer(identityProvider, migrator, guard, readiness)
				.afterPropertiesSet();

		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.IDENTITY_MISMATCH);
		assertThat(readiness.status().detail()).isEqualTo("wrong chain");
	}

	@Test
	void successfulMigrationAndMirrorVerificationMarksExplorerReady() {
		AuthoritativeChainIdentityProvider identityProvider = identityProvider();
		ExplorerSchemaMigrator migrator = mock(ExplorerSchemaMigrator.class);
		ExplorerChainIdentityGuard guard = mock(ExplorerChainIdentityGuard.class);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();

		new ExplorerChainIdentityInitializer(identityProvider, migrator, guard, readiness)
				.afterPropertiesSet();

		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.READY);
		verify(migrator).migrate();
		verify(guard).verifyAndBind(EXPECTED);
	}

	private AuthoritativeChainIdentityProvider identityProvider() {
		AuthoritativeChainIdentityProvider provider = mock(AuthoritativeChainIdentityProvider.class);
		when(provider.identity()).thenReturn(EXPECTED);
		return provider;
	}
}
