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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.CoreSnapshotCheckpointFloorStore;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityExecutionScope;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityExpectation;
import global.goldenera.node.core.storage.chainidentity.ExpectedChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

class CoreSnapshotCheckpointFloorInitializerTest {

	@Test
	void preservesLegacyBehaviorWhenMetadataIsAbsent() {
		Fixture fixture = fixture();
		when(fixture.store.load()).thenReturn(Optional.empty());

		fixture.initializer.afterPropertiesSet();

		assertThat(fixture.policy.floor()).isEmpty();
		verify(fixture.chainQuery, never()).getLatestStoredBlockOrThrow();
	}

	@Test
	void activatesFloorAfterCompleteStartupValidation() {
		Fixture fixture = validFixture();

		fixture.initializer.afterPropertiesSet();

		assertThat(fixture.policy.floor()).contains(fixture.floor);
	}

	@Test
	void acceptsDynamicTrustedFloorWithoutHardcodedCheckpoint() {
		Fixture fixture = validFixture();

		fixture.initializer.afterPropertiesSet();

		assertThat(fixture.policy.floor()).contains(fixture.floor);
	}

	@Test
	void rejectsDynamicFloorOutsideKnownProductionIdentity() {
		Fixture fixture = validFixture();
		StoredChainIdentity development = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 0, "development-mainnet",
				"0x" + "02".repeat(32), null);
		when(fixture.expectedIdentityProvider.expectedIdentity()).thenReturn(
				new ChainIdentityExpectation(development, ChainIdentityExecutionScope.DEVELOPMENT));

		assertThatThrownBy(fixture.initializer::afterPropertiesSet)
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("known production chain head");
	}

	@Test
	void rejectsFloorWhoseCanonicalIndexPointsElsewhere() {
		Fixture fixture = validFixture();
		when(fixture.chainQuery.getBlockHashByHeight(fixture.floor.height())).thenReturn(Optional.of(hash(90)));

		assertThatThrownBy(fixture.initializer::afterPropertiesSet)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("canonical hash");
	}

	@Test
	void rejectsFloorWhoseStoredStateRootDiffers() {
		Fixture fixture = validFixture();
		when(fixture.checkpoint.getBlock().getHeader().getStateRootHash()).thenReturn(hash(91));

		assertThatThrownBy(fixture.initializer::afterPropertiesSet)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("block metadata is inconsistent");
	}

	@Test
	void rejectsFloorWhoseStoredCumulativeDifficultyDiffers() {
		Fixture fixture = validFixture();
		when(fixture.checkpoint.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(999L));

		assertThatThrownBy(fixture.initializer::afterPropertiesSet)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("block metadata is inconsistent");
	}

	@Test
	void rejectsFloorWithMissingTrieRoot() {
		Fixture fixture = validFixture();
		when(fixture.store.containsStateTrieNode(fixture.floor.stateRoot())).thenReturn(false);

		assertThatThrownBy(fixture.initializer::afterPropertiesSet)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("trie root is missing");
	}

	@Test
	void rejectsCurrentHeadBelowFloor() {
		Fixture fixture = validFixture();
		when(fixture.currentHead.getHeight()).thenReturn(fixture.floor.height() - 1);

		assertThatThrownBy(fixture.initializer::afterPropertiesSet)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("head is below");
	}

	private Fixture validFixture() {
		Fixture fixture = fixture();
		when(fixture.store.load()).thenReturn(Optional.of(fixture.floor));
		when(fixture.chainQuery.getBlockHashByHeight(fixture.floor.height()))
				.thenReturn(Optional.of(fixture.floor.blockHash()));
		when(fixture.chainQuery.getStoredBlockByHash(fixture.floor.blockHash()))
				.thenReturn(Optional.of(fixture.checkpoint));
		when(fixture.checkpoint.getHeight()).thenReturn(fixture.floor.height());
		when(fixture.checkpoint.getHash()).thenReturn(fixture.floor.blockHash());
		BlockHeader header = mock(BlockHeader.class);
		when(header.getStateRootHash()).thenReturn(fixture.floor.stateRoot());
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		when(fixture.checkpoint.getBlock()).thenReturn(block);
		when(fixture.checkpoint.getCumulativeDifficulty()).thenReturn(fixture.floor.cumulativeDifficulty());
		when(fixture.store.containsStateTrieNode(fixture.floor.stateRoot())).thenReturn(true);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(fixture.currentHead);
		when(fixture.currentHead.getHeight()).thenReturn(fixture.floor.height());
		return fixture;
	}

	private Fixture fixture() {
		CoreSnapshotCheckpointFloorStore store = mock(CoreSnapshotCheckpointFloorStore.class);
		ExpectedChainIdentityProvider expectedIdentityProvider = mock(ExpectedChainIdentityProvider.class);
		StoredChainIdentity identity = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 0, "mainnet",
				"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f", null);
		when(expectedIdentityProvider.expectedIdentity()).thenReturn(
				new ChainIdentityExpectation(identity, ChainIdentityExecutionScope.KNOWN_PRODUCTION));
		ChainQuery chainQuery = mock(ChainQuery.class);
		CoreSnapshotCheckpointFloorPolicy policy = new CoreSnapshotCheckpointFloorPolicy();
		CoreSnapshotCheckpointFloor floor = new CoreSnapshotCheckpointFloor(
				100L, hash(1), hash(2), BigInteger.valueOf(1_000L), hash(3), hash(4));
		StoredBlock checkpoint = mock(StoredBlock.class);
		StoredBlock currentHead = mock(StoredBlock.class);
		CoreSnapshotCheckpointFloorInitializer initializer = new CoreSnapshotCheckpointFloorInitializer(
				store, expectedIdentityProvider, chainQuery, policy);
		return new Fixture(
				initializer, store, expectedIdentityProvider, chainQuery, policy, floor, checkpoint, currentHead);
	}

	private static Hash hash(int value) {
		return Hash.hash(Bytes.ofUnsignedInt(value));
	}

	private record Fixture(
			CoreSnapshotCheckpointFloorInitializer initializer,
			CoreSnapshotCheckpointFloorStore store,
			ExpectedChainIdentityProvider expectedIdentityProvider,
			ChainQuery chainQuery,
			CoreSnapshotCheckpointFloorPolicy policy,
			CoreSnapshotCheckpointFloor floor,
			StoredBlock checkpoint,
			StoredBlock currentHead) {
	}
}
