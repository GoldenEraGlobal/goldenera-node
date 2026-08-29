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
package global.goldenera.node.core.blockchain.reorg;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalAppender;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;

class ChainSwitchServiceCumulativeDifficultyTest {

	@Test
	void rejectsEqualWorkForkBeforeOpeningDatabaseBatch() {
		Fixture fixture = fixture();
		StoredBlock ancestor = storedBlock(1, 10, hash(1), null, 0);
		StoredBlock currentTip = storedBlock(2, 11, hash(3), hash(1), 1);
		StoredBlock candidate = storedBlock(2, 11, hash(2), hash(1), 1);
		fixture.canonical(ancestor);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(currentTip);

		assertThatThrownBy(() -> fixture.service.executeAtomicSyncSwap(ancestor, List.of(candidate)))
				.hasMessageContaining("does not have more cumulative difficulty");
		verify(fixture.blockRepository, never()).executeAtomicBatch(any());
	}

	@Test
	void rejectsForgedCumulativeDifficultyBeforeOpeningDatabaseBatch() {
		Fixture fixture = fixture();
		StoredBlock ancestor = storedBlock(10, 100, hash(10), null, 0);
		StoredBlock candidate = storedBlock(11, 1_000, hash(11), hash(10), 1);
		fixture.canonical(ancestor);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(ancestor);

		assertThatThrownBy(() -> fixture.service.executeAtomicSyncSwap(ancestor, List.of(candidate)))
				.hasMessageContaining("cumulative difficulty mismatch");
		verify(fixture.blockRepository, never()).executeAtomicBatch(any());
	}

	@Test
	void acceptsStrictlyHigherWorkForExtensionAndReorg() throws Exception {
		Fixture extension = fixture();
		StoredBlock extensionAncestor = storedBlock(10, 100, hash(20), null, 0);
		StoredBlock extensionTip = storedBlock(11, 101, hash(21), hash(20), 1);
		extension.canonical(extensionAncestor);
		when(extension.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(extensionAncestor);
		when(extension.chainQuery.findChainFrom(hash(20), hash(20))).thenReturn(new ArrayList<>());

		extension.service.executeAtomicSyncSwap(extensionAncestor, List.of(extensionTip));
		verify(extension.blockRepository).executeAtomicBatch(any());

		Fixture reorg = fixture();
		StoredBlock reorgAncestor = storedBlock(10, 100, hash(30), null, 0);
		StoredBlock currentTip = storedBlock(11, 105, hash(31), hash(30), 5);
		StoredBlock winningTip = storedBlock(11, 106, hash(32), hash(30), 6);
		reorg.canonical(reorgAncestor);
		when(reorg.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(currentTip);
		when(reorg.chainQuery.findChainFrom(hash(30), hash(31)))
				.thenReturn(new ArrayList<>(List.of(currentTip)));

		reorg.service.executeAtomicSyncSwap(reorgAncestor, List.of(winningTip));
		verify(reorg.blockRepository).executeAtomicBatch(any());
	}

	@Test
	void rejectsAncestorThatStoppedBeingCanonicalBeforeSwapLockWasAcquired() {
		Fixture fixture = fixture();
		StoredBlock staleAncestor = storedBlock(10, 100, hash(40), null, 0);
		StoredBlock currentTip = storedBlock(11, 105, hash(41), hash(42), 5);
		StoredBlock candidate = storedBlock(11, 106, hash(43), hash(40), 6);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(currentTip);
		when(fixture.chainQuery.getCanonicalStoredBlockByHash(hash(40))).thenReturn(Optional.empty());

		assertThatThrownBy(() -> fixture.service.executeAtomicSyncSwap(staleAncestor, List.of(candidate)))
				.hasMessageContaining("ancestor is no longer canonical");
		verify(fixture.blockRepository, never()).executeAtomicBatch(any());
	}

	private Fixture fixture() {
		ChainQuery chainQuery = mock(ChainQuery.class);
		BlockRepository blockRepository = mock(BlockRepository.class);
		ChainSwitchService service = new ChainSwitchService(
				chainQuery,
				blockRepository,
				mock(WorldStateFactory.class),
				mock(StateProcessor.class),
				mock(BlockValidator.class),
				mock(ApplicationEventPublisher.class),
				new ReentrantLock(),
				mock(EntityIndexRepository.class),
				mock(BlockEventExtractor.class),
				CoreSnapshotCheckpointFloorPolicy.withoutFloor(),
				mock(LifecycleJournalAppender.class));
		return new Fixture(service, chainQuery, blockRepository);
	}

	private StoredBlock storedBlock(long height, long cumulativeDifficulty, Hash hash,
			Hash previousHash, long difficulty) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getPreviousHash()).thenReturn(previousHash);
		when(header.getDifficulty()).thenReturn(BigInteger.valueOf(difficulty));
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		when(block.getHeight()).thenReturn(height);
		StoredBlock storedBlock = mock(StoredBlock.class);
		when(storedBlock.getBlock()).thenReturn(block);
		when(storedBlock.getHeight()).thenReturn(height);
		when(storedBlock.getHash()).thenReturn(hash);
		when(storedBlock.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(cumulativeDifficulty));
		return storedBlock;
	}

	private Hash hash(int value) {
		return Hash.hash(Bytes.ofUnsignedInt(value));
	}

	private record Fixture(ChainSwitchService service, ChainQuery chainQuery,
			BlockRepository blockRepository) {
		void canonical(StoredBlock ancestor) {
			when(chainQuery.getCanonicalStoredBlockByHash(ancestor.getHash())).thenReturn(Optional.of(ancestor));
		}
	}
}
