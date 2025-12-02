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

import static global.goldenera.node.core.config.CoreAsyncConfig.P2P_SEND_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.util.Collections;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockPropagationService {

	PeerRegistry peerRegistry;
	PeerReputationService peerReputationService;

	static int BROADCAST_PEER_COUNT = 16;

	@EventListener
	@Async(P2P_SEND_EXECUTOR)
	public void onBlockConnected(BlockConnectedEvent event) {
		if (event.getConnectedSource() == ConnectedSource.REORG || event.getConnectedSource() == ConnectedSource.SYNC) {
			return;
		}

		List<RemotePeer> targetPeers = selectPeersForBroadcast(event.getReceivedFrom());
		if (targetPeers.isEmpty()) {
			return;
		}

		log.info("Announcing block {} (Header Only) to {} peers",
				event.getBlock().getHeight(), targetPeers.size());

		for (RemotePeer peer : targetPeers) {
			try {
				peer.sendBlockHeaders(Collections.singletonList(event.getBlock().getHeader()), 0);
				peerReputationService.recordSuccess(peer.getIdentity());
			} catch (Exception e) {
				log.trace("Failed to announce block to peer {}", peer.getIdentity(), e);
			}
		}
	}

	private List<RemotePeer> selectPeersForBroadcast(Address excludeSender) {
		return peerRegistry.getBestPeers(BROADCAST_PEER_COUNT,
				p -> excludeSender == null || !excludeSender.equals(p.getIdentity()));
	}
}