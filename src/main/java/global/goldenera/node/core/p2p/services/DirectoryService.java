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
import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.node.NodeTerminationService;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadiness;
import global.goldenera.node.core.p2p.directory.DirectoryApiV1Client;
import global.goldenera.node.core.p2p.directory.DirectoryApiV1Serializer;
import global.goldenera.node.core.p2p.directory.DirectoryNodeUpgradeRequiredException;
import global.goldenera.node.core.p2p.directory.v1.NodeInfoResponse;
import global.goldenera.node.core.p2p.directory.v1.NodePingRequest;
import global.goldenera.node.core.p2p.directory.v1.NodePongResponse;
import global.goldenera.node.core.properties.DirectoryProperties;
import global.goldenera.node.core.properties.P2PProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.utils.ValidatorUtil;
import lombok.AllArgsConstructor;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Getter
@Service
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class DirectoryService {

	ConcurrentHashMap<Address, P2PClient> clients = new ConcurrentHashMap<>();
	@Getter(AccessLevel.NONE)
	AtomicBoolean started = new AtomicBoolean();
	@Getter(AccessLevel.NONE)
	AtomicInteger advertisedP2pPort = new AtomicInteger(-1);

	Address selfAddress;
	ThreadPoolTaskScheduler coreScheduler;
	ChainQuery chainQuery;

	DirectoryApiV1Client directoryApiV1Client;
	DirectoryApiV1Serializer directoryApiV1Serializer;
	IdentityService identityService;
	P2PProperties p2pProperties;
	GeneralProperties generalProperties;
	DirectoryProperties directoryProperties;
	CoreRuntimeReadiness coreReadiness;
	NodeTerminationService nodeTerminationService;

	public DirectoryService(
			DirectoryApiV1Client directoryApiV1Client,
			DirectoryApiV1Serializer directoryApiV1Serializer,
			IdentityService identityService, GeneralProperties generalProperties,
			P2PProperties p2pProperties,
			ChainQuery chainQuery,
			DirectoryProperties directoryProperties,
			CoreRuntimeReadiness coreReadiness,
			NodeTerminationService nodeTerminationService,
			@Qualifier(CORE_SCHEDULER) ThreadPoolTaskScheduler coreScheduler) {
		this.selfAddress = identityService.getNodeIdentityAddress();
		this.directoryApiV1Client = directoryApiV1Client;
		this.directoryApiV1Serializer = directoryApiV1Serializer;
		this.identityService = identityService;
		this.generalProperties = generalProperties;
		this.chainQuery = chainQuery;
		this.p2pProperties = p2pProperties;
		this.directoryProperties = directoryProperties;
		this.coreReadiness = coreReadiness;
		this.nodeTerminationService = nodeTerminationService;
		this.coreScheduler = coreScheduler;
	}

	public boolean start(int boundP2pPort) {
		if (boundP2pPort <= 0 || boundP2pPort > 65535) {
			throw new IllegalArgumentException("Bound P2P port must be in range 1..65535");
		}
		if (!started.compareAndSet(false, true)) {
			return false;
		}
		advertisedP2pPort.set(boundP2pPort);
		try {
			if (directoryProperties.isDisable()) {
				loadManualPeers();
				return true;
			}
			final long pingIntervalMs = directoryProperties.getPingIntervalInMs();
			coreScheduler.schedule(
					this::pingDirectory,
					triggerContext -> {
						var lastCompletion = triggerContext.lastCompletion();
						if (lastCompletion == null) {
							return Instant.now().plus(Duration.ofMillis(10000));
						}
						return lastCompletion.plus(Duration.ofMillis(pingIntervalMs));
					});
			log.info("DirectoryService: Scheduled pingDirectory on coreTaskScheduler (initial 10s, then {}ms)",
					pingIntervalMs);
			return true;
		} catch (RuntimeException e) {
			advertisedP2pPort.set(-1);
			started.set(false);
			throw e;
		}
	}

	public boolean isStarted() {
		return started.get();
	}

	public int advertisedP2pPort() {
		return advertisedP2pPort.get();
	}

	/**
	 * Loads manual peers from configuration.
	 * Used when directory is disabled (e.g., for local development).
	 */
	private void loadManualPeers() {
		List<DirectoryProperties.ManualPeer> manualPeers = directoryProperties.getPeers();
		if (manualPeers == null || manualPeers.isEmpty()) {
			log.info("DirectoryService: Directory disabled, no manual peers configured");
			return;
		}

		Network defaultNetwork = generalProperties.getNetwork();
		for (DirectoryProperties.ManualPeer peer : manualPeers) {
			try {
				if (peer.getIdentity() == null || peer.getHost() == null || peer.getPort() == null) {
					log.warn("DirectoryService: Incomplete peer config, need identity, host, port, and network");
					continue;
				}

				Address peerIdentity = Address.fromHexString(peer.getIdentity());
				Network peerNetwork = peer.getNetwork() != null
						? Network.valueOf(peer.getNetwork().toUpperCase())
						: defaultNetwork;
				String host = peer.getHost().trim();
				int port = peer.getPort();

				P2PClient client = new P2PClient(peerIdentity, peerNetwork, host, port, Instant.now());
				clients.put(peerIdentity, client);
				log.info("DirectoryService: Added manual peer {}:{} [{}] with identity {}",
						host, port, peerNetwork, peerIdentity.toChecksumAddress());
			} catch (Exception e) {
				log.warn("DirectoryService: Failed to add manual peer: {}", e.getMessage());
			}
		}
		log.info("DirectoryService: Directory disabled, loaded {} manual peers", clients.size());
	}

	/**
	 * Pings directory server to register this node and get peer list.
	 * Scheduled via coreTaskScheduler in init().
	 */
	public void pingDirectory() {
		if (directoryProperties.isDisable() || !started.get() || !coreReadiness.isReady()) {
			return;
		}
		StoredBlock block = chainQuery.getLatestStoredBlockOrThrow();
		BigInteger totalDifficulty = block.getCumulativeDifficulty();
		Hash headHash = block.getHash();
		long headHeight = block.getHeight();

		NodePingRequest request = new NodePingRequest();
		request.setP2pListenHost(p2pProperties.getHost());
		request.setP2pListenPort(advertisedP2pPort.get());
		request.setP2pProtocolVersion(Constants.P2P_PROTOCOL_VERSION);
		request.setSoftwareVersion(Constants.NODE_VERSION);
		request.setTimestamp(Instant.now().getEpochSecond());
		request.setNetwork(generalProperties.getNetwork());
		request.setNodeIdentity(selfAddress.toChecksumAddress());
		request.setTotalDifficulty(totalDifficulty.toString());
		request.setHeadHash(headHash.toHexString());
		request.setHeadHeight(headHeight);

		Bytes encodedRequest = directoryApiV1Serializer.encodePingV1(request);
		Hash requestHash = Hash.hash(encodedRequest);
		Signature signature = identityService.getPrivateKey().sign(requestHash);
		request.setHash(requestHash.toHexString());
		request.setSignature(signature.toHexString());

		NodePongResponse response;
		try {
			response = directoryApiV1Client.ping(request);
		} catch (RuntimeException e) {
			handlePingFailure(e);
			return;
		}

		if (response == null || response.getPayload() == null || response.getPayload().getPeers() == null) {
			log.warn("Directory: Response or payload is null");
			return;
		}

		Hash responseHash = Hash.hash(directoryApiV1Serializer.encodePongV1(response.getPayload()));
		Signature responseSignature = Signature.wrap(Bytes.fromHexString(response.getSignature()));
		if (!responseSignature.validate(responseHash, Constants.getDirectoryConfig().identityAddress())) {
			log.warn("Directory: Response signature is invalid");
			return;
		}

		Set<Address> peersFromPong = new HashSet<>();
		for (NodeInfoResponse peer : response.getPayload().getPeers()) {
			Address peerIdentity = Address.fromHexString(peer.getNodeIdentity());
			if (peerIdentity.equals(selfAddress)) {
				continue;
			}
			if (!ValidatorUtil.HostValidator.isSafe(peer.getP2pListenHost())) {
				log.debug("Directory: Peer {} has unsafe IP address {}", peerIdentity, peer.getP2pListenHost());
				continue;
			}
			peersFromPong.add(peerIdentity);
			clients.compute(peerIdentity, (addr, existingClient) -> {
				Instant updatedAt = Instant.ofEpochSecond(peer.getUpdatedAt());
				if (existingClient == null) {
					return new P2PClient(peerIdentity, peer.getNetwork(), peer.getP2pListenHost(),
							peer.getP2pListenPort(), updatedAt);
				} else {
					existingClient.updateInfo(peer.getP2pListenHost(), peer.getP2pListenPort(), updatedAt);
					return existingClient;
				}
			});
		}

		int peersBefore = clients.size();
		clients.keySet().retainAll(peersFromPong);
		int peersRemoved = peersBefore - clients.size();

		if (peersRemoved > 0) {
			log.debug("Removed {} stale peers not present in directory response.", peersRemoved);
		}
		log.debug("Directory ping complete. Known peers: {}", clients.size());
	}

	/**
	 * Gets all known peers, sorted by reliability (best first).
	 * The node's own identity is always excluded.
	 */
	public List<P2PClient> getP2PClientList() {
		return new ArrayList<>(clients.values());
	}

	/**
	 * Gets a single P2P client by its identity.
	 */
	public Optional<P2PClient> getP2PClient(Address identity) {
		if (identity == null) {
			return Optional.empty();
		}
		if (!clients.containsKey(identity)) {
			return Optional.empty();
		}
		return Optional.of(clients.get(identity));
	}

	/**
	 * Gets the total count of known peers (excludes self).
	 */
	public int getP2PClientCount() {
		return clients.size();
	}

	void handlePingFailure(RuntimeException failure) {
		if (failure instanceof DirectoryNodeUpgradeRequiredException upgradeRequired) {
			nodeTerminationService.terminateForRequiredUpgrade(upgradeRequired.getMinimumVersion());
			return;
		}
		log.warn("Directory: Failed to ping directory: {}", failure.getMessage());
	}

	@AllArgsConstructor
	@Getter
	@EqualsAndHashCode(of = "identity")
	public static class P2PClient {
		/**
		 * Identity of the node
		 */
		@NonNull
		Address identity;

		/**
		 * Network of the node
		 */
		@NonNull
		Network network;

		/**
		 * Host of the node
		 */
		@NonNull
		String p2pListenHost;

		/**
		 * Port of the node
		 */
		@NonNull
		Integer p2pListenPort;

		/**
		 * Last updated timestamp (from Directory Server)
		 */
		@NonNull
		Instant updatedAt;

		/**
		 * Updates info from Directory Server, preserving failure count.
		 */
		public void updateInfo(String newP2pListenHost, Integer newP2pListenPort, Instant newUpdatedAt) {
			this.p2pListenHost = newP2pListenHost;
			this.p2pListenPort = newP2pListenPort;
			this.updatedAt = newUpdatedAt;
		}

		public String toPrettyString() {
			return "[identity=" + identity.toChecksumAddress() + ", " + "network=" + network + ", " + "host="
					+ p2pListenHost + ":" + p2pListenPort + "]";
		}
	}
}
