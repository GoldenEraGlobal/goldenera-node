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
package global.goldenera.node.shared.services.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolAdmissionReason;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolTransaction;
import global.goldenera.node.shared.enums.WebhookType;
import global.goldenera.node.shared.services.webhook.DurableUniversalWebhookStore.JournalCursor;

class CoreLifecycleJournalWebhookProjectorTest {
	@Test
	void projectsSyncConnectAfterThePersistedActivationCursor() {
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		UUID epoch = UUID.randomUUID();
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.CANONICAL.code()))
				.thenReturn(new JournalCursor(epoch, 41L));
		when(store.hasEligibleRules(
				WebhookType.BLOCKCHAIN, epoch, LifecycleJournalStream.CANONICAL.code(), 42L)).thenReturn(true);
		when(store.advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.CANONICAL.code()),
				eq(epoch), eq(41L), eq(42L), any())).thenReturn(true);
		WebhookDispatchService sink = mock(WebhookDispatchService.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock stored = mock(StoredBlock.class);
		Block block = mock(Block.class);
		when(block.getTxs()).thenReturn(List.of());
		when(stored.getBlock()).thenReturn(block);
		when(stored.getEvents()).thenReturn(List.of());
		when(chainQuery.getStoredBlockByHashOrThrow(Hash.ZERO)).thenReturn(stored);
		CoreLifecycleJournalWebhookProjector projector =
				new CoreLifecycleJournalWebhookProjector(store, sink, chainQuery);
		LifecycleJournalEntry entry = new LifecycleJournalEntry(
				1, epoch, 42L, UUID.randomUUID(), LifecycleJournalStream.CANONICAL,
				LifecycleJournalOperation.CONNECT, UUID.randomUUID(), 0, 1, 100L,
				Hash.ZERO, Hash.ZERO, Instant.now(), ConnectedSource.SYNC.getCode(), -1, new byte[0]);

		projector.project(entry);

		verify(sink).processNewBlockEvent(
				block, List.of(), WebhookType.BLOCKCHAIN, entry.eventKey(), epoch,
				LifecycleJournalStream.CANONICAL.code(), 42L, null);
		verify(store).advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.CANONICAL.code()),
				eq(epoch), eq(41L), eq(42L), any());
	}

	@Test
	void suppressesReorgReaddWhenTransactionIsAlreadyCanonical() {
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		UUID epoch = UUID.randomUUID();
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.MEMPOOL.code()))
				.thenReturn(new JournalCursor(epoch, 0L));
		when(store.hasEligibleRules(
				WebhookType.BLOCKCHAIN, epoch, LifecycleJournalStream.MEMPOOL.code(), 1L)).thenReturn(true);
		when(store.advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.MEMPOOL.code()),
				eq(epoch), eq(0L), eq(1L), any())).thenReturn(true);
		WebhookDispatchService sink = mock(WebhookDispatchService.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getTransactionBlockHeight(Hash.ZERO)).thenReturn(Optional.of(10L));
		CoreLifecycleJournalWebhookProjector projector =
				new CoreLifecycleJournalWebhookProjector(store, sink, chainQuery);
		LifecycleJournalEntry entry = new LifecycleJournalEntry(
				1, epoch, 1L, UUID.randomUUID(), LifecycleJournalStream.MEMPOOL,
				LifecycleJournalOperation.REORG_READD, null, 0, 1, 10L,
				Hash.ZERO, null, Instant.now(), -1, 1, new byte[0]);

		projector.project(entry);

		verifyNoInteractions(sink);
		verify(store).advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.MEMPOOL.code()),
				eq(epoch), eq(0L), eq(1L), any());
	}

	@Test
	void suppressesInstantMinedPendingWhenTransactionIsAlreadyCanonical() {
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		UUID epoch = UUID.randomUUID();
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.MEMPOOL.code()))
				.thenReturn(new JournalCursor(epoch, 0L));
		when(store.hasEligibleRules(
				WebhookType.BLOCKCHAIN, epoch, LifecycleJournalStream.MEMPOOL.code(), 1L)).thenReturn(true);
		when(store.advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.MEMPOOL.code()),
				eq(epoch), eq(0L), eq(1L), any())).thenReturn(true);
		WebhookDispatchService sink = mock(WebhookDispatchService.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getTransactionBlockHeight(Hash.ZERO)).thenReturn(Optional.of(10L));
		CoreLifecycleJournalWebhookProjector projector =
				new CoreLifecycleJournalWebhookProjector(store, sink, chainQuery);
		LifecycleJournalEntry entry = new LifecycleJournalEntry(
				1, epoch, 1L, UUID.randomUUID(), LifecycleJournalStream.MEMPOOL,
				LifecycleJournalOperation.PENDING, null, 0, 1, 10L,
				Hash.ZERO, null, Instant.now(), -1, 0, new byte[0]);

		projector.project(entry);

		verifyNoInteractions(sink);
		verify(store).advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.MEMPOOL.code()),
				eq(epoch), eq(0L), eq(1L), any());
	}

	@Test
	void suppressesDelayedOriginalPendingAfterCoreHasReaddedTransactionForReorg() {
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		UUID epoch = UUID.randomUUID();
		when(store.journalCursor(WebhookType.BLOCKCHAIN, LifecycleJournalStream.MEMPOOL.code()))
				.thenReturn(new JournalCursor(epoch, 0L));
		when(store.hasEligibleRules(
				WebhookType.BLOCKCHAIN, epoch, LifecycleJournalStream.MEMPOOL.code(), 1L)).thenReturn(true);
		when(store.advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.MEMPOOL.code()),
				eq(epoch), eq(0L), eq(1L), any())).thenReturn(true);
		PersistentMempoolStore persistentMempool = mock(PersistentMempoolStore.class);
		StoredMempoolTransaction active = mock(StoredMempoolTransaction.class);
		when(active.admissionReason()).thenReturn(MempoolAdmissionReason.REORG);
		when(persistentMempool.findActive(Hash.ZERO)).thenReturn(Optional.of(active));
		WebhookDispatchService sink = mock(WebhookDispatchService.class);
		CoreLifecycleJournalWebhookProjector projector = new CoreLifecycleJournalWebhookProjector(
				store, sink, mock(ChainQuery.class), persistentMempool);
		LifecycleJournalEntry entry = new LifecycleJournalEntry(
				1, epoch, 1L, UUID.randomUUID(), LifecycleJournalStream.MEMPOOL,
				LifecycleJournalOperation.PENDING, null, 0, 1, 10L,
				Hash.ZERO, null, Instant.now(), -1, 0, new byte[0]);

		projector.project(entry);

		verifyNoInteractions(sink);
		verify(store).advanceJournalCursor(
				eq(WebhookType.BLOCKCHAIN), eq(LifecycleJournalStream.MEMPOOL.code()),
				eq(epoch), eq(0L), eq(1L), any());
	}
}
