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
package global.goldenera.node.core.blockchain.state;

import static lombok.AccessLevel.PRIVATE;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE)
public class ChainHeadStateCache {

	final WorldStateFactory worldStateFactory;
	final ChainQuery chainQueryService;
	final AtomicReference<WorldState> activeState = new AtomicReference<>();

	public ChainHeadStateCache(WorldStateFactory worldStateFactory, ChainQuery chainQueryService) {
		this.worldStateFactory = worldStateFactory;
		this.chainQueryService = chainQueryService;
	}

	public void init() {
		StoredBlock latestStored = chainQueryService.getLatestStoredBlockOrThrow();
		log.info("Initializing ChainHeadState at height {}", latestStored.getHeight());
		refreshState(latestStored.getBlock().getHeader().getStateRootHash());
	}

	public WorldState getHeadState() {
		WorldState current = activeState.get();
		Hash expectedRootHash = chainQueryService.getLatestStoredBlockOrThrow().getBlock().getHeader()
				.getStateRootHash();
		if (current != null && Objects.equals(current.getFinalStateRoot(), expectedRootHash)) {
			return current;
		}
		return refreshState(expectedRootHash);
	}

	@EventListener
	@Order(Ordered.HIGHEST_PRECEDENCE)
	public void onBlockConnected(BlockConnectedEvent event) {
		Hash newRoot = event.getBlock().getHeader().getStateRootHash();
		refreshState(newRoot);
	}

	private synchronized WorldState refreshState(Hash rootHash) {
		WorldState current = activeState.get();
		if (current != null && Objects.equals(current.getFinalStateRoot(), rootHash)) {
			return current;
		}

		int maxRetries = 5;
		int attempt = 0;
		Exception lastException = null;

		while (attempt < maxRetries) {
			try {
				WorldState newState = worldStateFactory.createForValidation(rootHash);
				activeState.set(newState);

				if (attempt > 0) {
					log.info("WorldState recovered successfully at attempt {}", attempt + 1);
				}
				return newState;

			} catch (Exception e) {
				lastException = e;
				attempt++;
				log.warn("Failed to load WorldState {} (Attempt {}/{}): {}",
						rootHash.toShortLogString(), attempt, maxRetries, e.getMessage());

				if (attempt < maxRetries) {
					try {
						Thread.sleep(50L * attempt);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Interrupted during state load retry", ie);
					}
				}
			}
		}

		log.error("CRITICAL: Failed to load WorldState for root {} after {} attempts.", rootHash, maxRetries,
				lastException);
		throw new RuntimeException("Could not load WorldState for " + rootHash, lastException);
	}
}