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

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.directory.DirectoryApiV1Client;
import global.goldenera.node.core.p2p.directory.DirectoryApiV1Serializer;
import global.goldenera.node.core.p2p.directory.v1.NodeInfoResponse;
import global.goldenera.node.core.p2p.directory.v1.NodePingRequest;
import global.goldenera.node.core.p2p.directory.v1.NodePongResponse;
import global.goldenera.node.core.properties.DirectoryProperties;
import global.goldenera.node.core.properties.P2PProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.utils.ValidatorUtil;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
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

	Address selfAddress;
	ThreadPoolTaskScheduler coreScheduler;
	ChainQuery chainQuery;
	long pingIntervalMs;

	DirectoryApiV1Client directoryApiV1Client;
	DirectoryApiV1Serializer directoryApiV1Serializer;
	IdentityService identityService;
	P2PProperties p2pProperties;
	GeneralProperties generalProperties;

	public DirectoryService(
			DirectoryApiV1Client directoryApiV1Client,
			DirectoryApiV1Serializer directoryApiV1Serializer,
			IdentityService identityService, GeneralProperties generalProperties,
			P2PProperties p2pProperties,
			ChainQuery chainQuery,
			DirectoryProperties directoryProperties,
			@Qualifier(CORE_SCHEDULER) ThreadPoolTaskScheduler coreScheduler,
			@Value("${ge.core.directory.ping-interval-in-ms:30000}") long pingIntervalMs) {
		this.selfAddress = identityService.getNodeIdentityAddress();
		this.directoryApiV1Client = directoryApiV1Client;
		this.directoryApiV1Serializer = directoryApiV1Serializer;
		this.identityService = identityService;
		this.generalProperties = generalProperties;
		this.chainQuery = chainQuery;
		this.p2pProperties = p2pProperties;
		this.coreScheduler = coreScheduler;
		this.pingIntervalMs = pingIntervalMs;
	}

	@PostConstruct
	public void init() {
		// Schedule with initial delay and fixed delay
		coreScheduler.schedule(
				this::pingDirectory,
				triggerContext -> {
					var lastCompletion = triggerContext.lastCompletion();
					if (lastCompletion == null) {
						// First execution - use initial delay
						return Instant.now().plus(Duration.ofMillis(10000));
					}
					// Subsequent executions - use fixed delay
					return lastCompletion.plus(Duration.ofMillis(pingIntervalMs));
				});
		log.info("DirectoryService: Scheduled pingDirectory on coreTaskScheduler (initial 10s, then {}ms)",
				pingIntervalMs);
	}

	/**
	 * Pings directory server to register this node and get peer list.
	 * Scheduled via coreTaskScheduler in init().
	 */
	public void pingDirectory() {
		StoredBlock block = chainQuery.getLatestStoredBlockOrThrow();
		BigInteger totalDifficulty = block.getCumulativeDifficulty();
		Hash headHash = block.getHash();
		long headHeight = block.getHeight();

		NodePingRequest request = new NodePingRequest();
		request.setP2pListenHost(p2pProperties.getHost());
		request.setP2pListenPort(p2pProperties.getPort());
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
		} catch (Exception e) {
			log.warn("Directory: Failed to ping directory: {}", e.getMessage());
			checkNodeVersion(e.getMessage());
			return;
		}

		if (response == null || response.getPayload() == null || response.getPayload().getPeers() == null) {
			log.warn("Directory: Response or payload is null");
			return;
		}

		Hash responseHash = Hash.hash(directoryApiV1Serializer.encodePongV1(response.getPayload()));
		Signature responseSignature = Signature.wrap(Bytes.fromHexString(response.getSignature()));
		if (!responseSignature.validate(responseHash, Constants.DIRECTORY_IDENTITY_ADDRESS)) {
			log.warn("Directory: Response signature is invalid");
			return;
		}

		Set<Address> peersFromPong = new HashSet<>();
		for (NodeInfoResponse peer : response.getPayload().getPeers()) {
			Address peerIdentity = Address.fromHexString(peer.getNodeIdentity());
			if (peerIdentity.equals(selfAddress)) {
				continue;
			}
			if (!ValidatorUtil.IpAddress.isSafe(peer.getP2pListenHost())) {
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

	private void checkNodeVersion(String errorMsg) {
		if (errorMsg.trim().toLowerCase()
				.contains("Node tried to ping with software version code below minimum.".trim().toLowerCase())) {
			log.error("! UPDATE NODE TO THE LATEST VERSION ! EXITING...");
			System.exit(0);
		}
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