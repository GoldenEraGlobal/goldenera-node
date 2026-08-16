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

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "ge.general",
		name = { "explorer-enable", "postgresql-enable", "webhook-enable" },
		havingValue = "true",
		matchIfMissing = true)
public class BridgeReorgPendingGate {

	private static final Duration REORG_CORRELATION_WINDOW = Duration.ofMinutes(30);

	private final BridgeLifecycleCoordinator coordinator;
	private final Object monitor = new Object();
	private final Cache<Hash, MempoolEntry> coreReadds = Caffeine.newBuilder()
			.expireAfterWrite(REORG_CORRELATION_WINDOW)
			.maximumSize(100_000)
			.build();
	private final Cache<Hash, Boolean> committedExplorerReverts = Caffeine.newBuilder()
			.expireAfterWrite(REORG_CORRELATION_WINDOW)
			.maximumSize(100_000)
			.build();

	public void coreReadded(MempoolEntry entry) {
		MempoolEntry ready = null;
		synchronized (monitor) {
			Hash hash = entry.getHash();
			if (committedExplorerReverts.getIfPresent(hash) != null) {
				committedExplorerReverts.invalidate(hash);
				ready = entry;
			} else {
				coreReadds.put(hash, entry);
			}
		}
		if (ready != null) {
			coordinator.pendingAfterReorg(ready);
		}
	}

	public void explorerRevertCommitted(Block orphanBlock) {
		for (Tx tx : orphanBlock.getTxs()) {
			MempoolEntry ready;
			synchronized (monitor) {
				Hash hash = tx.getHash();
				ready = coreReadds.getIfPresent(hash);
				if (ready == null) {
					committedExplorerReverts.put(hash, Boolean.TRUE);
				} else {
					coreReadds.invalidate(hash);
				}
			}
			if (ready != null) {
				coordinator.pendingAfterReorg(ready);
			}
		}
	}
}
