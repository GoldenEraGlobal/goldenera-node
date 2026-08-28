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

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.explorer.events.ExBlockConnectedEvent;
import global.goldenera.node.explorer.events.ExBlockReorgEvent;

class BridgeExplorerLifecycleListenerTest {

	private final BridgeLifecycleCoordinator coordinator = mock(BridgeLifecycleCoordinator.class);
	private final BridgeReorgPendingGate reorgPendingGate = mock(BridgeReorgPendingGate.class);
	private final BridgeExplorerLifecycleListener listener = new BridgeExplorerLifecycleListener(
			coordinator, reorgPendingGate);

	@Test
	void confirmedBlockUsesExplorerProducer() {
		Block block = mock(Block.class);
		List<BlockEvent> events = List.of();

		listener.handleBlockConnected(new ExBlockConnectedEvent(
				this, block, BigInteger.ONE, Wei.ZERO, Wei.ZERO, events, ConnectedSource.MINER));

		org.mockito.Mockito.verify(coordinator).confirmedBlock(block, events);
	}

	@Test
	void historicalSyncBlockDoesNotCreateBridgeDelivery() {
		Block block = mock(Block.class);
		List<BlockEvent> events = List.of();

		listener.handleBlockConnected(new ExBlockConnectedEvent(
				this, block, BigInteger.ONE, Wei.ZERO, Wei.ZERO, events, ConnectedSource.SYNC));

		verify(coordinator, never()).confirmedBlock(block, events);
	}

	@Test
	void reorgSummaryPrecedesRevertedTransactions() {
		Block orphan = mock(Block.class);
		Hash oldHash = mock(Hash.class);
		Hash newHash = mock(Hash.class);

		listener.handleBlockReorg(new ExBlockReorgEvent(
				this, 12L, oldHash, 11L, newHash, orphan));

		InOrder order = inOrder(coordinator);
		order.verify(coordinator).reorg(12L, oldHash, 11L, newHash);
		order.verify(coordinator).revertedBlock(orphan);
	}

	@Test
	void pendingGateIsReleasedOnlyAfterExplorerCommitCallback() {
		Block orphan = mock(Block.class);
		ExBlockReorgEvent event = new ExBlockReorgEvent(
				this, 12L, mock(Hash.class), 11L, mock(Hash.class), orphan);

		listener.releaseReorgPending(event);

		org.mockito.Mockito.verify(reorgPendingGate).explorerRevertCommitted(orphan);
	}
}
