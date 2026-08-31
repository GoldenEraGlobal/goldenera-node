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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.bridge.webhook.BridgeReorgPendingStore.ReadyReadd;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
			prefix = "ge.general",
			name = { "postgresql-enable", "webhook-enable" },
		havingValue = "true",
		matchIfMissing = true)
public class BridgeReorgPendingGate {

	private final BridgeLifecycleCoordinator coordinator;
	private final BridgeReorgPendingStore store;

	public void coreReadded(MempoolEntry entry) {
		coreReadded(entry, Long.MAX_VALUE);
	}

	public void coreReadded(MempoolEntry entry, long sourceSequence) {
		coreReadded(entry, BridgeSourcePosition.live(sourceSequence));
	}

	public void coreReadded(MempoolEntry entry, BridgeSourcePosition position) {
		emitReady(entry.getHash(), store.markReadded(entry, position));
	}

	public void canonicalRevertCommitted(Block orphanBlock) {
		canonicalRevertCommitted(orphanBlock, Long.MAX_VALUE);
	}

	public void canonicalRevertCommitted(Block orphanBlock, long revertSequence) {
		for (Tx tx : orphanBlock.getTxs()) {
			emitReady(tx.getHash(), store.markCanonicalReverted(tx.getHash(), revertSequence));
		}
	}

	public void discard(Hash txHash) {
		store.delete(txHash);
	}

	public boolean hasCanonicalRevert(Hash txHash) {
		return store.hasCanonicalRevert(txHash);
	}

	private void emitReady(Hash txHash, ReadyReadd ready) {
		if (ready == null) {
			return;
		}
		coordinator.pendingAfterReorg(ready.entry(), ready.position());
		store.delete(txHash);
	}
}
