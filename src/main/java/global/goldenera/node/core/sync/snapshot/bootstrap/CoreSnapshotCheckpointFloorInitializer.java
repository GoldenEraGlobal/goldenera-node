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
package global.goldenera.node.core.sync.snapshot.bootstrap;

import java.util.Optional;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.CoreSnapshotCheckpointFloorStore;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.ExpectedChainIdentityProvider;
import global.goldenera.node.core.sync.snapshot.SnapshotAnchorPolicy;
import global.goldenera.node.core.sync.snapshot.TrustedHttpSnapshotAnchorPolicy;

/** Fail-closed startup validation for a checkpoint floor installed by a snapshot import. */
@Component
public final class CoreSnapshotCheckpointFloorInitializer implements InitializingBean {

	private final CoreSnapshotCheckpointFloorStore store;
	private final ExpectedChainIdentityProvider expectedChainIdentityProvider;
	private final ChainQuery chainQuery;
	private final CoreSnapshotCheckpointFloorPolicy policy;
	private final SnapshotAnchorPolicy anchorPolicy;

	public CoreSnapshotCheckpointFloorInitializer(
			CoreSnapshotCheckpointFloorStore store,
			ExpectedChainIdentityProvider expectedChainIdentityProvider,
			ChainQuery chainQuery,
			CoreSnapshotCheckpointFloorPolicy policy) {
		this.store = store;
		this.expectedChainIdentityProvider = expectedChainIdentityProvider;
		this.chainQuery = chainQuery;
		this.policy = policy;
		this.anchorPolicy = new TrustedHttpSnapshotAnchorPolicy();
	}

	@Override
	public void afterPropertiesSet() {
		Optional<CoreSnapshotCheckpointFloor> storedFloor = store.load();
		if (storedFloor.isEmpty()) {
			policy.initialize(Optional.empty());
			return;
		}

		CoreSnapshotCheckpointFloor floor = storedFloor.orElseThrow();
		validateTrustedAnchor(floor);
		validateCanonicalCheckpoint(floor);
		validateTrieRoot(floor);
		validateCurrentHead(floor);
		policy.initialize(Optional.of(floor));
	}

	private void validateTrustedAnchor(CoreSnapshotCheckpointFloor floor) {
		anchorPolicy.verify(
				floor.height(), floor.blockHash(), expectedChainIdentityProvider.expectedIdentity().identity());
	}

	private void validateCanonicalCheckpoint(CoreSnapshotCheckpointFloor floor) {
		Hash indexedHash = chainQuery.getBlockHashByHeight(floor.height())
				.orElseThrow(() -> new IllegalStateException(
						"Snapshot checkpoint floor is missing from the canonical height index"));
		if (!indexedHash.equals(floor.blockHash())) {
			throw new IllegalStateException("Snapshot checkpoint floor canonical hash does not match metadata");
		}
		StoredBlock checkpoint = chainQuery.getStoredBlockByHash(floor.blockHash())
				.orElseThrow(() -> new IllegalStateException("Snapshot checkpoint floor block is missing"));
		if (checkpoint.getHeight() != floor.height()
				|| !checkpoint.getHash().equals(floor.blockHash())
				|| !checkpoint.getBlock().getHeader().getStateRootHash().equals(floor.stateRoot())
				|| !checkpoint.getCumulativeDifficulty().equals(floor.cumulativeDifficulty())) {
			throw new IllegalStateException("Snapshot checkpoint floor block metadata is inconsistent");
		}
	}

	private void validateTrieRoot(CoreSnapshotCheckpointFloor floor) {
		if (!floor.stateRoot().equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)
				&& !store.containsStateTrieNode(floor.stateRoot())) {
			throw new IllegalStateException("Snapshot checkpoint floor trie root is missing");
		}
	}

	private void validateCurrentHead(CoreSnapshotCheckpointFloor floor) {
		StoredBlock currentHead = chainQuery.getLatestStoredBlockOrThrow();
		if (currentHead.getHeight() < floor.height()) {
			throw new IllegalStateException("Current canonical head is below snapshot checkpoint floor");
		}
	}
}
