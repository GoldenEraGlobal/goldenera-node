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
package global.goldenera.node.core.sync.snapshot.archive;

import java.util.Map;
import java.util.Objects;

import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

/** Adapter used by an offline publisher whose canonical head is the exported checkpoint. */
public final class RepositoryCoreSnapshotEntityIndexSource implements CoreSnapshotEntityIndexSource {

	private final EntityIndexRepository repository;
	private final ChainQuery chainQuery;

	public RepositoryCoreSnapshotEntityIndexSource(
			EntityIndexRepository repository, ChainQuery chainQuery) {
		this.repository = Objects.requireNonNull(repository, "repository");
		this.chainQuery = Objects.requireNonNull(chainQuery, "chainQuery");
	}

	@Override
	public void assertCheckpoint(long checkpointHeight, Hash checkpointHash) {
		StoredBlock canonicalHead = chainQuery.getLatestStoredBlockOrThrow();
		if (canonicalHead.getHeight() != checkpointHeight || !canonicalHead.getHash().equals(checkpointHash)) {
			throw new IllegalStateException(
					"Repository entity indexes require the canonical head to equal the snapshot checkpoint");
		}
	}

	@Override
	public Map<Address, TokenState> tokens() {
		return Map.copyOf(repository.getAllTokensWithAddresses());
	}

	@Override
	public Map<Address, AuthorityState> authorities() {
		return Map.copyOf(repository.getAllAuthoritiesWithAddresses());
	}

	@Override
	public Map<Address, ValidatorState> validators() {
		return Map.copyOf(repository.getAllValidatorsWithAddresses());
	}
}
