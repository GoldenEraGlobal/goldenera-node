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
package global.goldenera.node.core.sync;

import static lombok.AccessLevel.PRIVATE;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MempoolPropagationService {

	MeterRegistry registry;
	PeerRegistry peerRegistry;

	BlockingQueue<Hash> hashQueue = new LinkedBlockingQueue<>();

	static int BATCH_SIZE = 500;
	static int BROADCAST_PEER_COUNT = 8;

	@PostConstruct
	public void init() {
		Thread propagator = new Thread(this::propagationLoop, "Mempool-Propagator");
		propagator.setDaemon(true);
		propagator.start();
	}

	@EventListener
	public void onMempoolTxAdded(MempoolTxAddEvent event) {
		if (event.getReason() == MempoolTxAddEvent.AddReason.NEW) {
			hashQueue.offer(event.getEntry().getTx().getHash());
		}
	}

	private void propagationLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				// Wait for the first item (blocking)
				Hash first = hashQueue.take();

				// If the queue is not full enough for a full batch, wait a bit to accumulate
				// more.
				// This avoids sending tiny batches during high-throughput bursts.
				if (hashQueue.size() < BATCH_SIZE) {
					Thread.sleep(50);
				}

				List<Hash> batch = new ArrayList<>(BATCH_SIZE);
				batch.add(first);
				hashQueue.drainTo(batch, BATCH_SIZE - 1);

				broadcastHashes(batch);

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				log.error("Error in mempool propagation loop", e);
			}
		}
	}

	private void broadcastHashes(List<Hash> hashes) {
		List<RemotePeer> peers = peerRegistry.getBestPeers(BROADCAST_PEER_COUNT, null);
		if (peers.isEmpty())
			return;

		log.debug("Broadcasting {} tx hashes to {} peers", hashes.size(), peers.size());

		for (RemotePeer peer : peers) {
			try {
				peer.sendMempoolHashes(hashes, 0);
			} catch (Exception e) {
				log.trace("Failed broadcast to {}", peer.getIdentity());
			}
		}
		registry.summary("p2p.mempool.broadcast_batch_size").record(hashes.size());
	}
}