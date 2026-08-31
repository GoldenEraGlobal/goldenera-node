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

import java.util.Optional;

import global.goldenera.node.core.storage.chainidentity.ChainStoragePreflightObservation;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

/**
 * Verifies and binds the PostgreSQL explorer mirror against an identity already
 * selected by core. It never authorizes consensus storage or core startup.
 */
public final class ExplorerChainIdentityGuard {

	private final PostgresChainStoragePreflightProbe probe;
	private final PostgresChainIdentityRepository repository;

	public ExplorerChainIdentityGuard(
			PostgresChainStoragePreflightProbe probe,
			PostgresChainIdentityRepository repository) {
		this.probe = probe;
		this.repository = repository;
	}

	public synchronized StoredChainIdentity verifyAndBind(StoredChainIdentity expected) {
		ChainStoragePreflightObservation observation = probe.inspect();
		assertExpectedIfPresent(observation.identity(), expected);
		if (observation.hasChainData()) {
			String observedGenesis = observation.observedGenesisHash().orElseThrow(() ->
					new ExplorerChainIdentityException(
							ExplorerReadinessState.STORAGE_CORRUPT,
							"Explorer PostgreSQL contains data but cannot prove its genesis"));
			if (!expected.genesisHash().equals(observedGenesis)) {
				throw new ExplorerChainIdentityException(
						ExplorerReadinessState.IDENTITY_MISMATCH,
						"Explorer PostgreSQL genesis does not match the authoritative core identity");
			}
		}
		if (observation.identity().isEmpty()) {
			repository.bindIfAbsent(expected);
		}
		StoredChainIdentity persisted = repository.find().orElseThrow(() ->
				new ExplorerChainIdentityException(
						ExplorerReadinessState.STORAGE_CORRUPT,
						"Explorer PostgreSQL failed to persist its chain identity mirror"));
		assertExpectedIfPresent(Optional.of(persisted), expected);
		return persisted;
	}

	private void assertExpectedIfPresent(
			Optional<StoredChainIdentity> actual, StoredChainIdentity expected) {
		actual.ifPresent(identity -> {
			if (!expected.equals(identity)) {
				throw new ExplorerChainIdentityException(
						ExplorerReadinessState.IDENTITY_MISMATCH,
						"Explorer PostgreSQL chain identity mirror does not match authoritative core identity");
			}
		});
	}
}
