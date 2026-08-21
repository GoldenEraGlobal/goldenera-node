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

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class PeerRegistry {

	PeerReputationService reputationService;

	ConcurrentHashMap<ChannelId, RemotePeer> activeConnections = new ConcurrentHashMap<>();
	ConcurrentHashMap<Address, RemotePeer> identityIndex = new ConcurrentHashMap<>();

	public void register(RemotePeer peer) {
		activeConnections.put(peer.getChannel().id(), peer);
	}

	public void unregister(Channel channel) {
		RemotePeer peer = activeConnections.remove(channel.id());
		if (peer != null && peer.getIdentity() != null) {
			Address identity = peer.getIdentity();
			if (identityIndex.remove(identity, peer)) {
				activeConnections.values().stream()
						.filter(candidate -> identity.equals(candidate.getIdentity()))
						.findFirst()
						.ifPresent(candidate -> identityIndex.putIfAbsent(identity, candidate));
			}
		}
	}

	public void updateIdentity(Channel channel, Address identity) {
		RemotePeer peer = activeConnections.get(channel.id());
		if (peer != null) {
			peer.setIdentity(identity);
			identityIndex.putIfAbsent(identity, peer);
		}
	}

	public RemotePeer get(Channel channel) {
		return activeConnections.get(channel.id());
	}

	public RemotePeer get(Address identity) {
		return identityIndex.get(identity);
	}

	public Collection<RemotePeer> getAll() {
		return activeConnections.values();
	}

	public int count() {
		return activeConnections.size();
	}

	public int handshakenCount() {
		return identityIndex.size();
	}

	/**
	 * Returns the best peers for broadcasting, sorted by reputation and then chain
	 * height.
	 */
	public List<RemotePeer> getBestPeers(int count, Predicate<RemotePeer> filter) {
		return activeConnections.values().stream()
				.filter(p -> p.getIdentity() != null)
				.filter(p -> !reputationService.isBanned(p.getIdentity()))
				.filter(filter != null ? filter : p -> true)
				.sorted(Comparator
						.comparingInt((RemotePeer peer) -> reputationService.score(peer.getIdentity()))
						.thenComparingLong(RemotePeer::getHeadHeight)
						.reversed())
				.limit(count)
				.collect(Collectors.toList());
	}

	/**
	 * Finds the best candidate for synchronization:
	 * - Must be not banned
	 * - Must advertise strictly more cumulative work than the local chain
	 * - Prefer greater total difficulty, then higher reputation and height
	 *
	 * Height is deliberately not an eligibility requirement: a shorter fork can
	 * have more cumulative work and must remain eligible for synchronization.
	 */
	public Optional<RemotePeer> getSyncCandidate(BigInteger localTotalDifficulty) {
		Comparator<RemotePeer> peerComparator = Comparator
				.comparing(RemotePeer::getTotalDifficulty)
				.thenComparingInt(peer -> reputationService.score(peer.getIdentity()))
				.thenComparingLong(RemotePeer::getHeadHeight);

		return activeConnections.values().stream()
				.filter(p -> p.getIdentity() != null)
				.filter(p -> !reputationService.isBanned(p.getIdentity()))
				.filter(p -> p.getTotalDifficulty() != null)
				.filter(p -> p.getTotalDifficulty().compareTo(localTotalDifficulty) > 0)
				.max(peerComparator);
	}

	/**
	 * Returns handshaken, non-banned peers that claim to have every block through
	 * {@code requiredHeight}. Body requests use hashes, so peers on another fork are
	 * harmless: a missing or mismatched positional body response is rejected by the
	 * sync manager and retried elsewhere.
	 */
	public List<RemotePeer> getBodySyncPeers(long requiredHeight) {
		return activeConnections.values().stream()
				.filter(p -> p.getIdentity() != null)
				.filter(p -> !reputationService.isBanned(p.getIdentity()))
				.filter(p -> p.getHeadHeight() >= requiredHeight)
				.sorted(Comparator
						.comparingInt((RemotePeer peer) -> reputationService.score(peer.getIdentity()))
						.thenComparing(RemotePeer::getTotalDifficulty,
								Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparingLong(RemotePeer::getHeadHeight)
						.reversed())
				.collect(Collectors.toList());
	}

	/**
	 * Returns the worst peer currently connected (lowest reputation).
	 * Used for eviction when we want to make room for better peers.
	 */
	public Optional<RemotePeer> getWorstPeer() {
		return activeConnections.values().stream()
				.filter(p -> p.getIdentity() != null)
				.min(Comparator.comparingInt(p -> reputationService.score(p.getIdentity())));
	}
}
