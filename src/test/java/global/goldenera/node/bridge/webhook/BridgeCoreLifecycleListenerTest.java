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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.mempool.domain.MempoolEntry;

class BridgeCoreLifecycleListenerTest {

	private final BridgeLifecycleCoordinator coordinator = mock(BridgeLifecycleCoordinator.class);
	private final BridgeReorgPendingGate reorgPendingGate = mock(BridgeReorgPendingGate.class);
	private final BridgeCoreLifecycleListener listener = new BridgeCoreLifecycleListener(coordinator, reorgPendingGate);

	@Test
	void allSuccessfulAdmissionsBecomePending() {
		MempoolEntry entry = mock(MempoolEntry.class);

		listener.handleMempoolAdd(new MempoolTxAddEvent(this, entry, MempoolTxAddEvent.AddReason.SYNC));

		verify(coordinator).pending(entry, "SYNC");
	}

	@Test
	void reorgAdmissionWaitsForExplorerCommit() {
		MempoolEntry entry = mock(MempoolEntry.class);

		listener.handleMempoolAdd(new MempoolTxAddEvent(this, entry, MempoolTxAddEvent.AddReason.REORG));

		verify(reorgPendingGate).coreReadded(entry);
		verifyNoInteractions(coordinator);
	}

	@Test
	void rbfCarriesReplacementHash() {
		MempoolEntry entry = mock(MempoolEntry.class);
		Hash replacementHash = mock(Hash.class);

		listener.handleMempoolRemove(new MempoolTxRemoveEvent(
				this, entry, MempoolTxRemoveEvent.RemoveReason.RBF, replacementHash));

		verify(coordinator).replaced(entry, replacementHash, "RBF");
	}

	@Test
	void minedRemovalDoesNotCompeteWithExplorerConfirmation() {
		listener.handleMempoolRemove(new MempoolTxRemoveEvent(
				this, mock(MempoolEntry.class), MempoolTxRemoveEvent.RemoveReason.MINED));

		verifyNoInteractions(coordinator);
	}
}
