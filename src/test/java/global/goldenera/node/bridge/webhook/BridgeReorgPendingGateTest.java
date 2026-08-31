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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.bridge.webhook.BridgeReorgPendingStore.ReadyReadd;

class BridgeReorgPendingGateTest {

	private final BridgeLifecycleCoordinator coordinator = mock(BridgeLifecycleCoordinator.class);
	private final BridgeReorgPendingStore store = mock(BridgeReorgPendingStore.class);
	private final BridgeReorgPendingGate gate = new BridgeReorgPendingGate(coordinator, store);
	private final BridgeSourcePosition live = BridgeSourcePosition.live(Long.MAX_VALUE);

	@Test
	void coreFirstWaitsUntilExplorerCommit() {
		Hash hash = hash(1);
		MempoolEntry entry = entry(hash);
		Block orphan = orphan(hash);
		when(store.markCanonicalReverted(hash, Long.MAX_VALUE)).thenReturn(new ReadyReadd(entry, live));

		gate.coreReadded(entry);
		verifyNoInteractions(coordinator);
		gate.canonicalRevertCommitted(orphan);

		verify(coordinator).pendingAfterReorg(entry, live);
	}

	@Test
	void explorerFirstAllowsLaterCoreReadd() {
		Hash hash = hash(2);
		MempoolEntry entry = entry(hash);
		Block orphan = orphan(hash);
		when(store.markReadded(entry, live)).thenReturn(new ReadyReadd(entry, live));

		gate.canonicalRevertCommitted(orphan);
		verifyNoInteractions(coordinator);
		gate.coreReadded(entry);

		verify(coordinator).pendingAfterReorg(entry, live);
	}

	@Test
	void eachCorrelationEmitsPendingOnlyOnce() {
		Hash hash = hash(3);
		MempoolEntry entry = entry(hash);
		Block orphan = orphan(hash);
		when(store.markCanonicalReverted(hash, Long.MAX_VALUE)).thenReturn(new ReadyReadd(entry, live));

		gate.coreReadded(entry);
		gate.canonicalRevertCommitted(orphan);
		gate.coreReadded(entry);

		verify(coordinator, times(1)).pendingAfterReorg(entry, live);
	}

	private MempoolEntry entry(Hash hash) {
		MempoolEntry entry = mock(MempoolEntry.class);
		when(entry.getHash()).thenReturn(hash);
		return entry;
	}

	private Block orphan(Hash hash) {
		Tx tx = mock(Tx.class);
		when(tx.getHash()).thenReturn(hash);
		Block block = mock(Block.class);
		when(block.getTxs()).thenReturn(List.of(tx));
		return block;
	}

	private Hash hash(int value) {
		return Hash.fromHexString("0x" + String.format("%064x", value));
	}
}
