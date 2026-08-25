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
package global.goldenera.node.explorer.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.Constants;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.genesis.GenesisInitializer;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.state.IsolatedWorldStateStorage;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;

class IsolatedExplorerArchiveReplayEngineTest {

	private static final Hash GENESIS_HASH = Hash.fromHexString("0x" + "9".repeat(64));
	private static final Instant GENESIS_TIME = Instant.parse("2025-01-01T00:00:00Z");

	@Test
	void rebuildsGenesisFromConfigurationWithoutProductionTrieRoots() throws Exception {
		Hash root = genesisRoot();
		Fixture fixture = fixture(root, Optional.empty());

		fixture.engine().rebuildToCanonicalHead();

		verify(fixture.indexer()).handleBlockConnectedBatch(anyList());
		verify(fixture.indexer(), never()).handleBlockConnected(any());
	}

	@Test
	void computedStateRootMismatchFailsClosedBeforeExplorerCommit() {
		Fixture fixture = fixture(Hash.fromHexString("0x" + "8".repeat(64)), Optional.empty());

		assertThatThrownBy(fixture.engine()::rebuildToCanonicalHead)
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("state root mismatch at block 0");
		verify(fixture.indexer(), never()).handleBlockConnected(any());
		verify(fixture.indexer(), never()).handleBlockConnectedBatch(anyList());
	}

	@Test
	void canonicalExplorerStatusResumesIdempotentlyWithoutRecommittingGenesis() throws Exception {
		Hash root = genesisRoot();
		ExStatus resumed = new ExStatus(1, 0, GENESIS_HASH, GENESIS_TIME, "test");
		Fixture fixture = fixture(root, Optional.of(resumed));

		fixture.engine().rebuildToCanonicalHead();

		verify(fixture.indexer(), never()).handleBlockConnected(any());
		verify(fixture.indexer(), never()).handleBlockConnectedBatch(anyList());
	}

	@Test
	void catchesAHeadThatAdvancesDuringReplayWithoutMissingTheFinalExplorerBlock() throws Exception {
		Hash root = genesisRoot();
		BlockHeader genesisHeader = mock(BlockHeader.class);
		when(genesisHeader.getStateRootHash()).thenReturn(root);
		when(genesisHeader.getTimestamp()).thenReturn(GENESIS_TIME);
		Block genesisBlock = mock(Block.class);
		when(genesisBlock.getHeight()).thenReturn(0L);
		when(genesisBlock.getHeader()).thenReturn(genesisHeader);
		StoredBlock genesis = stored(genesisBlock, 0L, GENESIS_HASH);

		Hash blockOneHash = Hash.fromHexString("0x" + "7".repeat(64));
		BlockHeader blockOneHeader = mock(BlockHeader.class);
		when(blockOneHeader.getPreviousHash()).thenReturn(GENESIS_HASH);
		when(blockOneHeader.getStateRootHash()).thenReturn(root);
		Block blockOne = mock(Block.class);
		when(blockOne.getHeight()).thenReturn(1L);
		when(blockOne.getHeader()).thenReturn(blockOneHeader);
		when(blockOne.getTxs()).thenReturn(List.of());
		StoredBlock blockOneStored = stored(blockOne, 1L, blockOneHash);

		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestStoredBlockOrThrow())
				.thenReturn(genesis, blockOneStored, blockOneStored);
		when(chainQuery.getStoredBlockByHeight(0)).thenReturn(Optional.of(genesis));
		when(chainQuery.getStoredBlockByHeight(1)).thenReturn(Optional.of(blockOneStored));
		when(chainQuery.getBlockHashByHeight(0)).thenReturn(Optional.of(GENESIS_HASH));
		when(chainQuery.getBlockHashByHeight(1)).thenReturn(Optional.of(blockOneHash));

		ExecutionResult result = mock(ExecutionResult.class);
		when(result.getTotalFeesCollected()).thenReturn(Wei.ZERO);
		when(result.getMinerActualRewardPaid()).thenReturn(Wei.ZERO);
		when(result.getActualBurnAmounts()).thenReturn(Map.of());
		StateProcessor processor = mock(StateProcessor.class);
		when(processor.executeTransactions(any(), any(), any(), any())).thenReturn(result);
		BlockEventExtractor extractor = mock(BlockEventExtractor.class);
		when(extractor.extractEvents(any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of());
		ExIndexerService indexer = mock(ExIndexerService.class);
		ExIndexerStatusCoreService status = mock(ExIndexerStatusCoreService.class);
		when(status.getStatus()).thenReturn(Optional.empty());
		when(status.getStatusOrThrow()).thenReturn(
				new ExStatus(1, 1, blockOneHash, GENESIS_TIME, "test"));
		IsolatedExplorerArchiveReplayEngine engine = new IsolatedExplorerArchiveReplayEngine(
				chainQuery, processor, extractor, indexer, status);

		engine.rebuildToCanonicalHead();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BlockConnectedEvent>> batch = ArgumentCaptor.forClass(List.class);
		verify(indexer).handleBlockConnectedBatch(batch.capture());
		assertThat(batch.getValue()).hasSize(2);
		verify(indexer, never()).handleBlockConnected(any());
	}

	private Fixture fixture(Hash stateRoot, Optional<ExStatus> initialStatus) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getStateRootHash()).thenReturn(stateRoot);
		when(header.getTimestamp()).thenReturn(GENESIS_TIME);
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(0L);
		when(block.getHeader()).thenReturn(header);
		StoredBlock stored = mock(StoredBlock.class);
		when(stored.getHeight()).thenReturn(0L);
		when(stored.getHash()).thenReturn(GENESIS_HASH);
		when(stored.getBlock()).thenReturn(block);
		when(stored.getCumulativeDifficulty()).thenReturn(BigInteger.ONE);
		when(stored.getReceivedFrom()).thenReturn(Address.ZERO);
		when(stored.getReceivedAt()).thenReturn(GENESIS_TIME);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(stored);
		when(chainQuery.getStoredBlockByHeight(0)).thenReturn(Optional.of(stored));
		when(chainQuery.getBlockHashByHeight(0)).thenReturn(Optional.of(GENESIS_HASH));
		ExIndexerService indexer = mock(ExIndexerService.class);
		ExIndexerStatusCoreService status = mock(ExIndexerStatusCoreService.class);
		when(status.getStatus()).thenReturn(initialStatus);
		when(status.getStatusOrThrow()).thenReturn(new ExStatus(1, 0, GENESIS_HASH, GENESIS_TIME, "test"));
		IsolatedExplorerArchiveReplayEngine engine = new IsolatedExplorerArchiveReplayEngine(
				chainQuery, mock(StateProcessor.class), mock(BlockEventExtractor.class), indexer, status);
		return new Fixture(engine, indexer);
	}

	private StoredBlock stored(Block block, long height, Hash hash) {
		StoredBlock stored = mock(StoredBlock.class);
		when(stored.getHeight()).thenReturn(height);
		when(stored.getHash()).thenReturn(hash);
		when(stored.getBlock()).thenReturn(block);
		when(stored.getCumulativeDifficulty()).thenReturn(BigInteger.ONE);
		when(stored.getReceivedFrom()).thenReturn(Address.ZERO);
		when(stored.getReceivedAt()).thenReturn(GENESIS_TIME);
		return stored;
	}

	private Hash genesisRoot() throws Exception {
		try (IsolatedWorldStateStorage storage = IsolatedWorldStateStorage.temporary("explorer-genesis-test-")) {
			WorldState state = storage.worldStateFactory().createForValidation(MerkleTrie.EMPTY_TRIE_NODE_HASH);
			NetworkSettings settings = Constants.getSettings();
			GenesisInitializer.executeGenesisStateExplicitly(
					state, settings.genesisAuthorityAddresses(), GENESIS_TIME, settings);
			return state.calculateRootHash();
		}
	}

	private record Fixture(IsolatedExplorerArchiveReplayEngine engine, ExIndexerService indexer) {
	}
}
