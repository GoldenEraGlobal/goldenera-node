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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "ge.general",
		name = { "explorer-enable", "postgresql-enable", "webhook-enable" },
		havingValue = "true",
		matchIfMissing = true)
public class BridgeCoreLifecycleListener {

	private final BridgeLifecycleCoordinator coordinator;
	private final BridgeReorgPendingGate reorgPendingGate;

	@EventListener
	public void handleMempoolAdd(MempoolTxAddEvent event) {
		if (event.getReason() == MempoolTxAddEvent.AddReason.REORG) {
			reorgPendingGate.coreReadded(event.getEntry());
		} else {
			coordinator.pending(event.getEntry(), event.getReason().name());
		}
	}

	@EventListener
	public void handleMempoolRemove(MempoolTxRemoveEvent event) {
		switch (event.getReason()) {
			case MINED -> {
				// Explorer confirmation is the only bridge authority for mined transactions.
			}
			case RBF -> coordinator.replaced(
					event.getEntry(), event.getReplacementTxHash(), event.getReason().name());
			case STALE_NONCE, EXPIRED, INVALID, EVICTED_FULL, INSUFFICIENT_FUNDS -> coordinator.dropped(
					event.getEntry(), event.getReason().name());
		}
	}
}
