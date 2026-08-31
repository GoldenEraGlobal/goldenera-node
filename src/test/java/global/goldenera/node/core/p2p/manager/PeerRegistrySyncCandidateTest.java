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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;

class PeerRegistrySyncCandidateTest {

	@Test
	void onlyPeersAdvertisingStrictlyMoreWorkAreEligibleRegardlessOfHeight() {
		PeerReputationService reputation = mock(PeerReputationService.class);
		PeerRegistry registry = new PeerRegistry(reputation);
		RemotePeer tallerEqualWork = peer(1, 200, BigInteger.valueOf(100));
		RemotePeer shorterHigherWork = peer(2, 80, BigInteger.valueOf(101));
		RemotePeer unknownWork = peer(3, 300, null);
		register(registry, tallerEqualWork);
		register(registry, shorterHigherWork);
		register(registry, unknownWork);

		assertThat(registry.getSyncCandidate(BigInteger.valueOf(100)))
				.contains(shorterHigherWork);
		assertThat(registry.getSyncCandidate(BigInteger.valueOf(101))).isEmpty();
	}

	@Test
	void greatestAdvertisedWorkWinsBeforeReputation() {
		PeerReputationService reputation = mock(PeerReputationService.class);
		PeerRegistry registry = new PeerRegistry(reputation);
		RemotePeer reputable = peer(4, 101, BigInteger.valueOf(110));
		RemotePeer moreWork = peer(5, 90, BigInteger.valueOf(120));
		when(reputation.score(reputable.getIdentity())).thenReturn(1_000);
		when(reputation.score(moreWork.getIdentity())).thenReturn(1);
		register(registry, reputable);
		register(registry, moreWork);

		assertThat(registry.getSyncCandidate(BigInteger.valueOf(100))).contains(moreWork);
	}

	@Test
	void bodyPeersMustBeHandshakenAndAdvertiseTheWholeRequestedRange() {
		PeerReputationService reputation = mock(PeerReputationService.class);
		PeerRegistry registry = new PeerRegistry(reputation);
		RemotePeer sufficient = peer(6, 120, BigInteger.valueOf(120));
		RemotePeer tooShort = peer(7, 119, BigInteger.valueOf(500));
		RemotePeer notHandshaken = peer(8, 200, BigInteger.valueOf(500));
		notHandshaken.setIdentity(null);
		register(registry, sufficient);
		register(registry, tooShort);
		register(registry, notHandshaken);

		assertThat(registry.getBodySyncPeers(120)).containsExactly(sufficient);
	}

	private RemotePeer peer(int identityByte, long height, BigInteger totalDifficulty) {
		Channel channel = mock(Channel.class);
		when(channel.id()).thenReturn(mock(ChannelId.class));
		RemotePeer peer = new RemotePeer(channel, new SimpleMeterRegistry());
		peer.setIdentity(Address.fromHexString("0x" + String.format("%040x", identityByte)));
		peer.setHeadHeight(height);
		peer.setTotalDifficulty(totalDifficulty);
		return peer;
	}

	private void register(PeerRegistry registry, RemotePeer peer) {
		registry.register(peer);
	}
}
