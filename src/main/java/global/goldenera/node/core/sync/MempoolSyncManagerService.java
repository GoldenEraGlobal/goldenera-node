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

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_TASK_EXECUTOR;
import static global.goldenera.node.core.config.CoreAsyncConfig.P2P_SEND_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.p2p.events.P2PHandshakeCompletedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolHashesReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolHashesRequestedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolTxReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolTxsReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolTxsRequestedEvent;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MempoolSyncManagerService {

    private static final int MAX_HASHES_TO_ADVERTISE = 5000;
    private static final int MAX_TXS_TO_REQUEST = 1000;
    private static final int TX_BATCH_SIZE = 500;

    MempoolManager mempoolManager;
    MempoolStore mempoolStore;

    /**
     * Triggered when a new peer connects.
     * Initiates the sync by asking for their mempool hashes.
     */
    @EventListener
    @Async(P2P_SEND_EXECUTOR)
    public void onPeerConnected(P2PHandshakeCompletedEvent event) {
        if (mempoolManager.isFull()) {
            log.debug("Mempool is full, skipping outgoing sync initiation with {}", event.getPeer().getIdentity());
            return;
        }
        event.getPeer().sendGetMempoolHashes();
    }

    /**
     * RESPONDER: Peer asked for our mempool hashes.
     */
    @EventListener
    @Async(P2P_SEND_EXECUTOR)
    public void onMempoolHashesRequested(P2PMempoolHashesRequestedEvent event) {
        List<Hash> allHashes = mempoolStore.getAllTxHashes();
        List<Hash> limitedHashes = allHashes.stream()
                .limit(MAX_HASHES_TO_ADVERTISE)
                .collect(Collectors.toList());
        event.getPeer().sendMempoolHashes(limitedHashes, event.getRequestId());
    }

    /**
     * REQUESTER: Peer sent us their mempool hashes (response to our request).
     * We filter what we are missing and request the actual Tx objects.
     */
    @EventListener
    public void onMempoolHashesReceived(P2PMempoolHashesReceivedEvent event) {
        List<Hash> remoteHashes = event.getHashes();
        List<Hash> missingHashes = new ArrayList<>();

        for (Hash h : remoteHashes) {
            if (!mempoolStore.getTxByHash(h).isPresent()) {
                missingHashes.add(h);
            }
        }

        if (missingHashes.isEmpty()) {
            log.debug("Mempool sync with {}: We are in sync.", event.getPeer().getIdentity());
            return;
        }

        if (missingHashes.size() > MAX_TXS_TO_REQUEST) {
            log.debug("Peer {} offered {} missing txs, capping request to {}.",
                    event.getPeer().getIdentity(), missingHashes.size(), MAX_TXS_TO_REQUEST);
            missingHashes = missingHashes.subList(0, MAX_TXS_TO_REQUEST);
        }

        log.debug("Requesting {} missing tx(s) from {}", missingHashes.size(), event.getPeer().getIdentity());

        for (int i = 0; i < missingHashes.size(); i += TX_BATCH_SIZE) {
            List<Hash> batch = missingHashes.subList(i, Math.min(i + TX_BATCH_SIZE, missingHashes.size()));
            event.getPeer().sendGetMempoolTxs(batch);
        }
    }

    /**
     * RESPONDER: Peer is asking for full transaction data for specific hashes.
     */
    @EventListener
    @Async(P2P_SEND_EXECUTOR)
    public void onMempoolTxsRequested(P2PMempoolTxsRequestedEvent event) {
        List<Hash> requestedHashes = event.getHashes();
        log.debug("Peer {} requested {} txs from our mempool", event.getPeer().getIdentity(), requestedHashes.size());
        if (requestedHashes.size() > TX_BATCH_SIZE * 2) {
            requestedHashes = requestedHashes.subList(0, TX_BATCH_SIZE * 2);
        }
        List<Tx> foundTxs = new ArrayList<>();
        for (Hash h : requestedHashes) {
            mempoolStore.getTxByHash(h).ifPresent(mempoolTx -> {
                foundTxs.add(mempoolTx.getTx());
            });
        }
        log.debug("Responding with {} txs to peer {} (requested {})", foundTxs.size(), event.getPeer().getIdentity(),
                requestedHashes.size());
        event.getPeer().sendMempoolTxs(foundTxs, event.getRequestId());
    }

    /**
     * REQUESTER: Received the actual transactions we requested.
     */
    @EventListener
    @Async(CORE_TASK_EXECUTOR)
    public void onMempoolTxsReceived(P2PMempoolTxsReceivedEvent event) {
        List<Tx> txs = event.getTxs();
        log.debug("Received {} txs from peer {}", txs.size(), event.getPeer().getIdentity());
        if (txs.isEmpty())
            return;

        for (Tx tx : txs) {
            log.debug("Received tx {} type={} from peer {}", tx.getHash().toShortLogString(), tx.getType(),
                    event.getPeer().getIdentity());
        }
        mempoolManager.addTxs(txs, event.getPeer().getIdentity(), MempoolTxAddEvent.AddReason.SYNC, true);
    }

    @EventListener
    public void onMempoolTxReceived(P2PMempoolTxReceivedEvent event) {
        mempoolManager.addTx(event.getTx(), event.getPeer().getIdentity(), MempoolTxAddEvent.AddReason.NEW, true);
    }
}