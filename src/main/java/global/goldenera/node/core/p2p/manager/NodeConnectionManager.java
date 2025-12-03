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
package global.goldenera.node.core.p2p.manager;

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_SCHEDULER;
import static lombok.AccessLevel.PRIVATE;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.netty.client.NettyClientService;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.p2p.services.DirectoryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class NodeConnectionManager {

	private static final int HANDSHAKE_TIMEOUT_SECONDS = 15;
	private static final int TARGET_PEER_COUNT = 20;
	private static final int PONG_TIMEOUT_SECONDS = 60;
	private static final int LOW_REPUTATION_THRESHOLD = 950;

	ThreadPoolTaskScheduler coreScheduler;

	DirectoryService directoryService;
	PeerRegistry peerRegistry;
	PeerReputationService reputationService;
	NettyClientService nettyClient;

	AtomicInteger connectedPeersGauge = new AtomicInteger(0);
	AtomicInteger bannedPeersGauge = new AtomicInteger(0);

	IdentityService identityService;

	public NodeConnectionManager(@Qualifier(CORE_SCHEDULER) ThreadPoolTaskScheduler coreScheduler,
			MeterRegistry registry,
			DirectoryService directoryService,
			PeerRegistry peerRegistry, PeerReputationService reputationService, NettyClientService nettyClient,
			IdentityService identityService) {
		this.coreScheduler = coreScheduler;
		this.directoryService = directoryService;
		this.peerRegistry = peerRegistry;
		this.reputationService = reputationService;
		this.nettyClient = nettyClient;
		this.identityService = identityService;
		registry.gauge("p2p.peers.count", Tags.of("state", "connected"), connectedPeersGauge);
		registry.gauge("p2p.peers.count", Tags.of("state", "banned"), bannedPeersGauge);
	}

	@PostConstruct
	public void init() {
		// Schedule heartbeat loop every 10s
		coreScheduler.scheduleAtFixedRate(this::heartbeatLoop, Duration.ofMillis(10000));
		// Schedule maintenance loop every 30s
		coreScheduler.scheduleAtFixedRate(this::maintenanceLoop, Duration.ofMillis(30000));
		log.info("NodeConnectionManager: Scheduled heartbeatLoop (10s) and maintenanceLoop (30s) on coreTaskScheduler");
		// Run maintenance immediately on startup
		maintenanceLoop();
	}

	/**
	 * Sends PING to all connected peers and disconnects timed out peers.
	 * Scheduled via coreTaskScheduler in init().
	 */
	public void heartbeatLoop() {
		Instant now = Instant.now();
		for (RemotePeer peer : peerRegistry.getAll()) {
			if (Duration.between(peer.getLastPongReceived(), now).getSeconds() > PONG_TIMEOUT_SECONDS) {
				log.warn("Peer {} timed out (No PONG). Disconnecting.", peer.getChannel().remoteAddress());
				peer.disconnect("Timeout");
				continue;
			}
			peer.sendPing();
		}
	}

	/**
	 * Maintains peer connections - disconnects bad peers and connects to new ones.
	 * Scheduled via coreTaskScheduler in init().
	 */
	public void maintenanceLoop() {
		Instant now = Instant.now();
		Set<Address> connectedIdentities = new HashSet<>();
		Set<String> connectedIps = new HashSet<>();
		Address myIdentity = identityService.getNodeIdentityAddress();

		// First pass: Collect info and disconnect self/duplicates
		for (RemotePeer p : peerRegistry.getAll()) {
			String rawAddr = p.getChannel().remoteAddress().toString();
			connectedIps.add(rawAddr.replace("/", ""));

			if (p.getIdentity() != null) {
				// 1. Check for Self-Connection
				if (p.getIdentity().equals(myIdentity)) {
					log.warn("Detected self-connection to {}. Disconnecting.", rawAddr);
					p.disconnect("Self-connection detected");
					continue;
				}

				// 2. Check for Duplicates
				if (connectedIdentities.contains(p.getIdentity())) {
					log.warn("Duplicate connection to {}. Disconnecting.", p.getIdentity());
					p.disconnect("Duplicate connection");
					continue;
				}

				connectedIdentities.add(p.getIdentity());
			}
		}

		int disconnectedCount = 0;

		for (RemotePeer activePeer : peerRegistry.getAll()) {
			// Skip if already disconnected in the previous pass
			if (!activePeer.getChannel().isActive()) {
				continue;
			}

			boolean shouldDisconnect = false;
			String reason = "";

			if (activePeer.getIdentity() == null) {
				long secondsConnected = Duration.between(activePeer.getConnectedAt(), now).getSeconds();
				if (secondsConnected > HANDSHAKE_TIMEOUT_SECONDS) {
					shouldDisconnect = true;
					reason = "Handshake timeout (" + secondsConnected + "s)";
				}
			} else if (reputationService.isBanned(activePeer.getIdentity())) {
				shouldDisconnect = true;
				reason = "Locally Banned (Incompatible/Bad Actor)";
			}

			if (shouldDisconnect) {
				log.info("Disconnecting peer {}: {}",
						(activePeer.getIdentity() != null ? activePeer.getIdentity()
								: activePeer.getChannel().remoteAddress()),
						reason);
				activePeer.disconnect(reason);
				if (activePeer.getIdentity() != null) {
					connectedIdentities.remove(activePeer.getIdentity());
				}
				disconnectedCount++;
			}
		}

		int effectiveCount = peerRegistry.count() - disconnectedCount;

		int totalPeers = peerRegistry.count();
		int bannedPeers = (int) peerRegistry.getAll().stream()
				.filter(p -> p.getIdentity() != null && reputationService.isBanned(p.getIdentity()))
				.count();

		connectedPeersGauge.set(totalPeers);
		bannedPeersGauge.set(bannedPeers);

		// Get all potential candidates from directory, excluding currently connected
		// and banned peers
		List<DirectoryService.P2PClient> allKnown = directoryService.getP2PClientList();
		List<DirectoryService.P2PClient> candidates = allKnown.stream()
				.filter(c -> !connectedIdentities.contains(c.getIdentity()))
				.filter(c -> !c.getIdentity().equals(myIdentity)) // Filter out self
				.filter(c -> !reputationService.isBanned(c.getIdentity()))
				.sorted(Comparator
						.comparingInt((DirectoryService.P2PClient c) -> reputationService.score(c.getIdentity()))
						.reversed())
				.collect(Collectors.toList());

		// Rotation Logic: If we are full, check if we should replace a bad peer
		if (effectiveCount >= TARGET_PEER_COUNT && !candidates.isEmpty()) {
			Optional<RemotePeer> worstPeerOpt = peerRegistry.getWorstPeer();
			if (worstPeerOpt.isPresent()) {
				RemotePeer worstPeer = worstPeerOpt.get();
				int score = reputationService.score(worstPeer.getIdentity());
				if (score < LOW_REPUTATION_THRESHOLD) {
					log.info("Rotating out low reputation peer {} (Score: {}).",
							worstPeer.getIdentity().toChecksumAddress(), score);
					worstPeer.disconnect("Rotation: Low Reputation");
					connectedIdentities.remove(worstPeer.getIdentity());
					effectiveCount--;
				}
			}
		}

		if (effectiveCount < TARGET_PEER_COUNT) {
			int slotsOpen = TARGET_PEER_COUNT - effectiveCount;
			// Take top candidates
			List<DirectoryService.P2PClient> selected = candidates.stream().limit(slotsOpen)
					.collect(Collectors.toList());

			for (DirectoryService.P2PClient candidate : selected) {
				if (isAlreadyConnected(candidate, connectedIps)) {
					continue;
				}
				try {
					nettyClient.connect(candidate);
				} catch (Exception e) {
					log.error("Failed to connect to candidate: {}", candidate.toPrettyString(), e);
				}
			}
		}
	}

	private boolean isAlreadyConnected(DirectoryService.P2PClient candidate, Set<String> connectedNettyAddresses) {
		String cleanCandidate = candidate.getP2pListenHost().trim() + ":" + candidate.getP2pListenPort();
		return connectedNettyAddresses.contains(cleanCandidate);
	}
}