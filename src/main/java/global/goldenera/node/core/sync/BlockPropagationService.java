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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockPropagationService {

	PeerRegistry peerRegistry;
	Executor p2pSendExecutor;

	static int BROADCAST_PEER_COUNT = 16;

	public BlockPropagationService(
			PeerRegistry peerRegistry,
			@Qualifier(P2P_SEND_EXECUTOR) Executor p2pSendExecutor) {
		this.peerRegistry = peerRegistry;
		this.p2pSendExecutor = p2pSendExecutor;
	}

	@EventListener
	public void onBlockConnected(BlockConnectedEvent event) {
		if (event.getConnectedSource() == ConnectedSource.REORG || event.getConnectedSource() == ConnectedSource.SYNC) {
			return;
		}
		BlockHeader header = event.getBlock().getHeader();
		Address receivedFrom = event.getReceivedFrom();
		try {
			p2pSendExecutor.execute(() -> announce(header, receivedFrom));
		} catch (RejectedExecutionException exception) {
			log.warn("Dropping block header announcement because the P2P send queue is full");
		}
	}

	private void announce(BlockHeader header, Address receivedFrom) {
		List<RemotePeer> targetPeers = selectPeersForBroadcast(receivedFrom);
		if (targetPeers.isEmpty()) {
			return;
		}

		log.info("Announcing block {} (Header Only) to {} peers",
				header.getHeight(), targetPeers.size());

		for (RemotePeer peer : targetPeers) {
			try {
				peer.sendBlockHeaders(Collections.singletonList(header), 0);
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
