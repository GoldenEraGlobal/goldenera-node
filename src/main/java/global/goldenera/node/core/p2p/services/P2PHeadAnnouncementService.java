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
package global.goldenera.node.core.p2p.services;

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_SCHEDULER;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockConnectionBatchCompletedEvent;
import global.goldenera.node.core.p2p.events.P2PHandshakeCompletedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class P2PHeadAnnouncementService {

	static final Duration COALESCE_DELAY = Duration.ofMillis(75);

	private final ThreadPoolTaskScheduler coreScheduler;
	private final PeerRegistry peerRegistry;
	private final P2PStatusProvider statusProvider;
	private final AtomicBoolean announcementQueued = new AtomicBoolean();
	private final AtomicBoolean announcementRequested = new AtomicBoolean();

	public P2PHeadAnnouncementService(
			@Qualifier(CORE_SCHEDULER) ThreadPoolTaskScheduler coreScheduler,
			PeerRegistry peerRegistry,
			P2PStatusProvider statusProvider) {
		this.coreScheduler = coreScheduler;
		this.peerRegistry = peerRegistry;
		this.statusProvider = statusProvider;
	}

	@EventListener
	public void onBlockConnected(BlockConnectedEvent event) {
		if (event.isBatchMember()) {
			return;
		}
		requestAnnouncement();
	}

	@EventListener
	public void onBlockConnectionBatchCompleted(BlockConnectionBatchCompletedEvent event) {
		if (event.getConnectedSource() == ConnectedSource.SYNC) {
			requestAnnouncement();
		}
	}

	@EventListener
	public void onHandshakeCompleted(P2PHandshakeCompletedEvent event) {
		requestAnnouncement();
	}

	public boolean requestAnnouncement() {
		announcementRequested.set(true);
		if (!announcementQueued.compareAndSet(false, true)) {
			return false;
		}
		try {
			coreScheduler.schedule(this::drainAnnouncement, Instant.now().plus(COALESCE_DELAY));
			return true;
		} catch (RejectedExecutionException exception) {
			announcementQueued.set(false);
			log.debug("P2P head announcement scheduler rejected request", exception);
			return false;
		}
	}

	private void drainAnnouncement() {
		announcementRequested.set(false);
		try {
			P2PStatusDto status = statusProvider.currentStatus();
			peerRegistry.getAll().stream()
					.filter(peer -> peer.getIdentity() != null)
					.forEach(peer -> peer.sendPong(status));
		} catch (RuntimeException exception) {
			log.debug("Could not announce current P2P head", exception);
		} finally {
			announcementQueued.set(false);
			if (announcementRequested.get()) {
				requestAnnouncement();
			}
		}
	}
}
