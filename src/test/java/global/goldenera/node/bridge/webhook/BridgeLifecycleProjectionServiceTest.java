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
package global.goldenera.node.bridge.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.repositories.BridgeSubscriptionRepository;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.shared.properties.GeneralProperties;

class BridgeLifecycleProjectionServiceTest {
	private static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000200");

	private final ChainQuery chainQuery = mock(ChainQuery.class);
	private final BridgeLifecycleCoordinator coordinator = mock(BridgeLifecycleCoordinator.class);
	private final BridgeReorgPendingGate reorgPendingGate = mock(BridgeReorgPendingGate.class);
	private final BridgeLifecycleProjectionCursorStore cursorStore = mock(BridgeLifecycleProjectionCursorStore.class);
	private final BridgeSubscriptionRepository subscriptionRepository = mock(BridgeSubscriptionRepository.class);
	private final GeneralProperties generalProperties = new GeneralProperties();
	private final BridgeLifecycleProjectionService service = new BridgeLifecycleProjectionService(
			chainQuery, coordinator, reorgPendingGate, cursorStore, subscriptionRepository, generalProperties);

	@BeforeEach
	void setUp() {
		generalProperties.setNetwork(Network.TESTNET);
		when(subscriptionRepository.existsEnabledForSource(
				eq(Network.TESTNET), anyInt(), eq(EPOCH), anyLong(), nullable(Long.class))).thenReturn(true);
	}

	@Test
	void synchronizedConnectAfterSubscriptionCursorIsProjectedAsConfirmed() {
		Hash blockHash = hash(1);
		Block block = mock(Block.class);
		StoredBlock storedBlock = stored(blockHash, 10L, block);
		when(chainQuery.getStoredBlockByHash(blockHash)).thenReturn(Optional.of(storedBlock));
		LifecycleJournalEntry connect = entry(
				101L, null, 0, 1, LifecycleJournalOperation.CONNECT, 10L, blockHash, hash(2));

		service.applyCanonicalGroup(List.of(connect));

		verify(coordinator).confirmedBlock(
				block, List.of(), new BridgeSourcePosition(EPOCH, 101L, connect.eventKey()));
		verify(cursorStore).advance(LifecycleJournalStream.CANONICAL, EPOCH, 101L);
	}

	@Test
	void reorgGroupIsProjectedAsSummaryThenRevertsThenNewCanonicalBlocks() {
		UUID groupId = UUID.randomUUID();
		Hash oldHash = hash(3);
		Hash newHash = hash(4);
		Block orphan = mock(Block.class);
		Block connected = mock(Block.class);
		StoredBlock oldStored = stored(oldHash, 12L, orphan);
		StoredBlock newStored = stored(newHash, 12L, connected);
		when(chainQuery.getStoredBlockByHash(oldHash)).thenReturn(Optional.of(oldStored));
		when(chainQuery.getStoredBlockByHash(newHash)).thenReturn(Optional.of(newStored));
		LifecycleJournalEntry disconnect = entry(
				201L, groupId, 0, 3, LifecycleJournalOperation.DISCONNECT, 12L, oldHash, hash(2));
		LifecycleJournalEntry connect = entry(
				202L, groupId, 1, 3, LifecycleJournalOperation.CONNECT, 12L, newHash, hash(2));
		LifecycleJournalEntry commit = entry(
				203L, groupId, 2, 3, LifecycleJournalOperation.REORG_COMMIT, 12L, newHash, oldHash);

		service.applyCanonicalGroup(List.of(disconnect, connect, commit));

		InOrder order = inOrder(coordinator, reorgPendingGate, cursorStore);
		order.verify(coordinator).reorg(
				12L, oldHash, 12L, newHash, new BridgeSourcePosition(EPOCH, 203L, commit.eventKey()));
		order.verify(coordinator).revertedBlock(
				orphan, new BridgeSourcePosition(EPOCH, 201L, disconnect.eventKey()));
		order.verify(reorgPendingGate).canonicalRevertCommitted(orphan, 201L);
		order.verify(coordinator).confirmedBlock(
				connected, List.of(), new BridgeSourcePosition(EPOCH, 202L, connect.eventKey()));
		order.verify(cursorStore).advance(LifecycleJournalStream.CANONICAL, EPOCH, 203L);
	}

	@Test
	void historicalSyncWithoutEligibleSubscriptionsAdvancesCursorWithoutBlockOrTransactionRouting() {
		when(subscriptionRepository.existsEnabledForSource(
				Network.TESTNET, 0, EPOCH, 301L, 500_000L)).thenReturn(false);
		LifecycleJournalEntry connect = entry(
				301L, null, 0, 1, LifecycleJournalOperation.CONNECT, 500_000L, hash(8), hash(7));

		service.applyCanonicalGroup(List.of(connect));

		verify(cursorStore).advance(LifecycleJournalStream.CANONICAL, EPOCH, 301L);
		verifyNoInteractions(chainQuery, coordinator);
	}

	@Test
	void reorgReaddAlreadyConfirmedOnNewCanonicalBranchIsDiscardedInsteadOfEndingPending() {
		Hash txHash = hash(9);
		Hash newBlockHash = hash(10);
		Block connected = mock(Block.class);
		StoredBlock newStored = stored(newBlockHash, 20L, connected);
		when(chainQuery.getStoredBlockByHash(newBlockHash)).thenReturn(Optional.of(newStored));
		LifecycleJournalEntry connect = entry(
				401L, null, 0, 1, LifecycleJournalOperation.CONNECT, 20L, newBlockHash, hash(8));
		LifecycleJournalEntry readd = new LifecycleJournalEntry(
				LifecycleJournalEntry.CURRENT_VERSION,
				EPOCH,
				402L,
				UUID.randomUUID(),
				LifecycleJournalStream.MEMPOOL,
				LifecycleJournalOperation.REORG_READD,
				null,
				0,
				1,
				20L,
				txHash,
				null,
				Instant.parse("2026-08-29T00:00:00Z"),
				-1,
				1,
				new byte[0]);
		when(chainQuery.getTransactionBlock(txHash)).thenReturn(Optional.of(newStored));

		service.applyCanonicalGroup(List.of(connect));
		service.applyMempool(readd);

		verify(coordinator).confirmedBlock(
				connected, List.of(), new BridgeSourcePosition(EPOCH, 401L, connect.eventKey()));
		verify(reorgPendingGate).discard(txHash);
		verify(reorgPendingGate, never()).coreReadded(any(), any(BridgeSourcePosition.class));
		verify(cursorStore).advance(LifecycleJournalStream.MEMPOOL, EPOCH, 402L);
	}

	@Test
	void instantMinedPendingIsSuppressedAfterCanonicalConfirmation() {
		Hash txHash = hash(11);
		StoredBlock canonical = stored(hash(12), 21L, mock(Block.class));
		when(chainQuery.getTransactionBlock(txHash)).thenReturn(Optional.of(canonical));
		LifecycleJournalEntry pending = new LifecycleJournalEntry(
				LifecycleJournalEntry.CURRENT_VERSION,
				EPOCH,
				501L,
				UUID.randomUUID(),
				LifecycleJournalStream.MEMPOOL,
				LifecycleJournalOperation.PENDING,
				null,
				0,
				1,
				20L,
				txHash,
				null,
				Instant.parse("2026-08-29T00:00:00Z"),
				-1,
				0,
				new byte[0]);

		service.applyMempool(pending);

		verify(coordinator, never()).pending(any(), any(), any(BridgeSourcePosition.class));
		verify(cursorStore).advance(LifecycleJournalStream.MEMPOOL, EPOCH, 501L);
	}

	@Test
	void delayedOriginalPendingIsSuppressedAfterCanonicalRevertWasAlreadyProjected() {
		Hash txHash = hash(13);
		when(reorgPendingGate.hasCanonicalRevert(txHash)).thenReturn(true);
		LifecycleJournalEntry pending = new LifecycleJournalEntry(
				LifecycleJournalEntry.CURRENT_VERSION,
				EPOCH,
				502L,
				UUID.randomUUID(),
				LifecycleJournalStream.MEMPOOL,
				LifecycleJournalOperation.PENDING,
				null,
				0,
				1,
				20L,
				txHash,
				null,
				Instant.parse("2026-08-29T00:00:00Z"),
				-1,
				0,
				new byte[0]);

		service.applyMempool(pending);

		verify(coordinator, never()).pending(any(), any(), any(BridgeSourcePosition.class));
		verify(cursorStore).advance(LifecycleJournalStream.MEMPOOL, EPOCH, 502L);
	}

	private LifecycleJournalEntry entry(
			long sequence,
			UUID groupId,
			int ordinal,
			int size,
			LifecycleJournalOperation operation,
			long height,
			Hash primary,
			Hash related) {
		return new LifecycleJournalEntry(
				LifecycleJournalEntry.CURRENT_VERSION,
				EPOCH,
				sequence,
				UUID.randomUUID(),
				LifecycleJournalStream.CANONICAL,
				operation,
				groupId,
				ordinal,
				size,
				height,
				primary,
				related,
				Instant.parse("2026-08-29T00:00:00Z"),
				ConnectedSource.SYNC.getCode(),
				-1,
				new byte[0]);
	}

	private StoredBlock stored(Hash hash, long height, Block block) {
		StoredBlock stored = mock(StoredBlock.class);
		when(stored.getHash()).thenReturn(hash);
		when(stored.getHeight()).thenReturn(height);
		when(stored.getBlock()).thenReturn(block);
		when(stored.getEvents()).thenReturn(List.of());
		return stored;
	}

	private Hash hash(int value) {
		return Hash.fromHexString("0x" + "%064x".formatted(value));
	}
}
