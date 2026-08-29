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
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloor;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;

class ChainSwitchServiceCheckpointFloorTest {

	@Test
	void rejectsCanonicalAncestorBelowFloorBeforeDatabaseMutation() {
		Fixture fixture = fixture(policyAt(10, hash(100)));
		StoredBlock ancestor = storedBlock(9, 100, hash(9), hash(8), 1);
		StoredBlock currentTip = storedBlock(10, 101, hash(10), hash(9), 1);
		StoredBlock candidate = storedBlock(10, 102, hash(11), hash(9), 2);
		fixture.canonical(ancestor);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(currentTip);

		assertThatThrownBy(() -> fixture.service.executeAtomicSyncSwap(ancestor, List.of(candidate)))
				.hasMessageContaining("below snapshot checkpoint floor");
		verify(fixture.chainQuery, never()).findChainFrom(any(), any());
		verify(fixture.blockRepository, never()).executeAtomicBatch(any());
	}

	@Test
	void allowsCanonicalAncestorExactlyAtFloor() throws Exception {
		Fixture fixture = fixture(policyAt(10, hash(20)));
		StoredBlock ancestor = storedBlock(10, 100, hash(20), hash(19), 1);
		StoredBlock candidate = storedBlock(11, 101, hash(21), hash(20), 1);
		fixture.canonical(ancestor);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(ancestor);
		when(fixture.chainQuery.findChainFrom(hash(20), hash(20))).thenReturn(new ArrayList<>());

		fixture.service.executeAtomicSyncSwap(ancestor, List.of(candidate));

		verify(fixture.blockRepository).executeAtomicBatch(any());
	}

	@Test
	void rejectsDifferentCanonicalHashAtFloorHeight() {
		Fixture fixture = fixture(policyAt(10, hash(99)));
		StoredBlock ancestor = storedBlock(10, 100, hash(20), hash(19), 1);
		StoredBlock candidate = storedBlock(11, 101, hash(21), hash(20), 1);
		fixture.canonical(ancestor);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(ancestor);

		assertThatThrownBy(() -> fixture.service.executeAtomicSyncSwap(ancestor, List.of(candidate)))
				.hasMessageContaining("does not match snapshot checkpoint floor hash");
		verify(fixture.blockRepository, never()).executeAtomicBatch(any());
	}

	@Test
	void allowsNormalPostFloorReorg() throws Exception {
		Fixture fixture = fixture(policyAt(10, hash(100)));
		StoredBlock ancestor = storedBlock(11, 110, hash(30), hash(29), 1);
		StoredBlock currentTip = storedBlock(12, 111, hash(31), hash(30), 1);
		StoredBlock candidate = storedBlock(12, 112, hash(32), hash(30), 2);
		fixture.canonical(ancestor);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(currentTip);
		when(fixture.chainQuery.findChainFrom(hash(30), hash(31)))
				.thenReturn(new ArrayList<>(List.of(currentTip)));

		fixture.service.executeAtomicSyncSwap(ancestor, List.of(candidate));

		verify(fixture.blockRepository).executeAtomicBatch(any());
	}

	@Test
	void preservesOldDatabaseBehaviorWithoutFloor() throws Exception {
		Fixture fixture = fixture(CoreSnapshotCheckpointFloorPolicy.withoutFloor());
		StoredBlock ancestor = storedBlock(1, 10, hash(40), hash(39), 1);
		StoredBlock candidate = storedBlock(2, 11, hash(41), hash(40), 1);
		fixture.canonical(ancestor);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(ancestor);
		when(fixture.chainQuery.findChainFrom(hash(40), hash(40))).thenReturn(new ArrayList<>());

		fixture.service.executeAtomicSyncSwap(ancestor, List.of(candidate));

		verify(fixture.blockRepository).executeAtomicBatch(any());
	}

	private Fixture fixture(CoreSnapshotCheckpointFloorPolicy policy) {
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
				policy,
				mock(LifecycleJournalAppender.class));
		return new Fixture(service, chainQuery, blockRepository);
	}

	private CoreSnapshotCheckpointFloorPolicy policyAt(long height, Hash blockHash) {
		return CoreSnapshotCheckpointFloorPolicy.enforcing(new CoreSnapshotCheckpointFloor(
				height,
				blockHash,
				hash(101),
				BigInteger.valueOf(1_000L),
				hash(102),
				hash(103)));
	}

	private StoredBlock storedBlock(
			long height, long cumulativeDifficulty, Hash hash, Hash previousHash, long difficulty) {
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

	private static Hash hash(int value) {
		return Hash.hash(Bytes.ofUnsignedInt(value));
	}

	private record Fixture(
			ChainSwitchService service,
			ChainQuery chainQuery,
			BlockRepository blockRepository) {
		void canonical(StoredBlock ancestor) {
			when(chainQuery.getCanonicalStoredBlockByHash(ancestor.getHash())).thenReturn(Optional.of(ancestor));
		}
	}
}
