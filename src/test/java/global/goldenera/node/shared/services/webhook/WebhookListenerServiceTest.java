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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.explorer.events.ExBlockConnectedEvent;
import global.goldenera.node.explorer.services.webhook.ExplorerWebhookListenerService;
import global.goldenera.node.shared.enums.WebhookType;

class WebhookListenerServiceTest {
	@Test
	void coreSpringEventsOnlyWakeTheDurableJournalConsumer() {
		CoreLifecycleJournalWebhookConsumer consumer = mock(CoreLifecycleJournalWebhookConsumer.class);
		WebhookListenerService listener = new WebhookListenerService(consumer);

		listener.handleMempoolTxAdd(mock(MempoolTxAddEvent.class));

		verify(consumer).wake();
	}

	@Test
	void explorerSyncBlocksArePersistedBeforeCommitInsteadOfBeingSkipped() {
		UniversalWebhookEventSink sink = mock(UniversalWebhookEventSink.class);
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(43L);
		when(store.hasEligibleExplorerRules(43L)).thenReturn(true);
		ExplorerWebhookListenerService listener = new ExplorerWebhookListenerService(sink, store);
		when(block.getTxs()).thenReturn(List.of());
		ExBlockConnectedEvent event = new ExBlockConnectedEvent(
				this, block, BigInteger.ONE, Wei.ZERO, Wei.ZERO, List.of(), ConnectedSource.SYNC);

		listener.handleExBlockConnected(event);

		verify(sink).processNewBlockEvent(block, List.of(), WebhookType.EXPLORER, true);
	}

	@Test
	void explorerSyncBlockWithoutSubscribersSkipsPayloadMaterialization() {
		UniversalWebhookEventSink sink = mock(UniversalWebhookEventSink.class);
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		ExplorerWebhookListenerService listener = new ExplorerWebhookListenerService(sink, store);
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(42L);
		ExBlockConnectedEvent event = new ExBlockConnectedEvent(
				this, block, BigInteger.ONE, Wei.ZERO, Wei.ZERO, List.of(), ConnectedSource.SYNC);

		listener.handleExBlockConnected(event);

		verifyNoInteractions(sink);
	}

	@Test
	void liveReorgBlockAtActivationHeightBypassesHistoricalHeightGate() {
		UniversalWebhookEventSink sink = mock(UniversalWebhookEventSink.class);
		DurableUniversalWebhookStore store = mock(DurableUniversalWebhookStore.class);
		when(store.hasEligibleRules(WebhookType.EXPLORER, null, null, null)).thenReturn(true);
		ExplorerWebhookListenerService listener = new ExplorerWebhookListenerService(sink, store);
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(42L);
		when(block.getTxs()).thenReturn(List.of());
		ExBlockConnectedEvent event = new ExBlockConnectedEvent(
				this, block, BigInteger.ONE, Wei.ZERO, Wei.ZERO, List.of(), ConnectedSource.REORG);

		listener.handleExBlockConnected(event);

		verify(sink).processNewBlockEvent(block, List.of(), WebhookType.EXPLORER, false);
	}
}
