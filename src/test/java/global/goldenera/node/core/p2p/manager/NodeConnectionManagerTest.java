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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.netty.client.NettyClientService;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.p2p.services.DirectoryService;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NodeConnectionManagerTest {

	@Test
	void peerIdentityIndexSurvivesDuplicateConnectionCleanup() {
		PeerRegistry registry = new PeerRegistry(mock(PeerReputationService.class));
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		Channel firstChannel = mock(Channel.class);
		Channel duplicateChannel = mock(Channel.class);
		when(firstChannel.id()).thenReturn(DefaultChannelId.newInstance());
		when(duplicateChannel.id()).thenReturn(DefaultChannelId.newInstance());
		RemotePeer first = new RemotePeer(firstChannel, meters);
		RemotePeer duplicate = new RemotePeer(duplicateChannel, meters);
		Address remoteIdentity = Address.fromHexString("0x2222222222222222222222222222222222222222");

		registry.register(first);
		registry.updateIdentity(firstChannel, remoteIdentity);
		registry.register(duplicate);
		registry.updateIdentity(duplicateChannel, remoteIdentity);

		assertThat(registry.get(remoteIdentity)).isSameAs(first);
		assertThat(registry.handshakenCount()).isOne();

		registry.unregister(firstChannel);

		assertThat(registry.get(remoteIdentity)).isSameAs(duplicate);
		assertThat(registry.handshakenCount()).isOne();

		registry.unregister(duplicateChannel);

		assertThat(registry.get(remoteIdentity)).isNull();
		assertThat(registry.handshakenCount()).isZero();
	}

	@Test
	void repeatedStartDoesNotScheduleDuplicateLoops() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		DirectoryService directory = mock(DirectoryService.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		IdentityService identity = mock(IdentityService.class);
		when(directory.getP2PClientList()).thenReturn(List.of());
		when(peers.getAll()).thenReturn(List.of());
		when(identity.getNodeIdentityAddress()).thenReturn(mock(Address.class));
		NodeConnectionManager manager = new NodeConnectionManager(
				scheduler,
				new SimpleMeterRegistry(),
				directory,
				peers,
				mock(PeerReputationService.class),
				mock(NettyClientService.class),
				identity);

		assertThat(manager.start()).isTrue();
		assertThat(manager.start()).isFalse();
		assertThat(manager.isStarted()).isTrue();
		verify(scheduler, times(2)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
	}

	@Test
	void immediateMaintenanceIsQueuedOnlyAfterStartup() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(scheduler).execute(any(Runnable.class));
		DirectoryService directory = mock(DirectoryService.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		IdentityService identity = mock(IdentityService.class);
		when(directory.getP2PClientList()).thenReturn(List.of());
		when(peers.getAll()).thenReturn(List.of());
		when(identity.getNodeIdentityAddress()).thenReturn(mock(Address.class));
		NodeConnectionManager manager = new NodeConnectionManager(
				scheduler, new SimpleMeterRegistry(), directory, peers,
				mock(PeerReputationService.class), mock(NettyClientService.class), identity);

		assertThat(manager.requestMaintenance()).isFalse();
		assertThat(manager.start()).isTrue();
		assertThat(manager.requestMaintenance()).isTrue();
		verify(scheduler).execute(any(Runnable.class));
	}

	@Test
	void queuesMaintenanceWhenCoreBecomesReady() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		DirectoryService directory = mock(DirectoryService.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		IdentityService identity = mock(IdentityService.class);
		when(directory.getP2PClientList()).thenReturn(List.of());
		when(peers.getAll()).thenReturn(List.of());
		when(identity.getNodeIdentityAddress()).thenReturn(mock(Address.class));
		NodeConnectionManager manager = new NodeConnectionManager(
				scheduler, new SimpleMeterRegistry(), directory, peers,
				mock(PeerReputationService.class), mock(NettyClientService.class), identity);

		assertThat(manager.start()).isTrue();
		manager.connectConfiguredPeersWhenCoreIsReady();

		verify(scheduler).execute(any(Runnable.class));
		verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
	}

	@Test
	void requestDuringMaintenanceGuaranteesOneFollowUpPass() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(scheduler).execute(any(Runnable.class));
		DirectoryService directory = mock(DirectoryService.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		IdentityService identity = mock(IdentityService.class);
		when(directory.getP2PClientList()).thenReturn(List.of());
		when(peers.getAll()).thenReturn(List.of());
		when(identity.getNodeIdentityAddress()).thenReturn(mock(Address.class));
		NodeConnectionManager manager = spy(new NodeConnectionManager(
				scheduler, new SimpleMeterRegistry(), directory, peers,
				mock(PeerReputationService.class), mock(NettyClientService.class), identity));
		AtomicInteger passes = new AtomicInteger();
		doAnswer(invocation -> {
			if (passes.incrementAndGet() == 2) {
				assertThat(manager.requestMaintenance()).isFalse();
			}
			return null;
		}).when(manager).maintenanceLoop();
		doNothing().when(manager).heartbeatLoop();

		assertThat(manager.start()).isTrue();
		assertThat(manager.requestMaintenance()).isTrue();

		assertThat(passes).hasValue(3);
		verify(scheduler).execute(any(Runnable.class));
	}

	@Test
	void doesNotOpenDuplicateConnectionWhilePeerHandshakeIsPending() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		DirectoryService directory = mock(DirectoryService.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		PeerReputationService reputation = mock(PeerReputationService.class);
		NettyClientService nettyClient = mock(NettyClientService.class);
		IdentityService identityService = mock(IdentityService.class);
		Address localIdentity = Address.fromHexString("0x1111111111111111111111111111111111111111");
		Address remoteIdentity = Address.fromHexString("0x2222222222222222222222222222222222222222");
		var candidate = new DirectoryService.P2PClient(
				remoteIdentity, Network.TESTNET, "node-b", 9000, Instant.now());
		when(directory.getP2PClientList()).thenReturn(List.of(candidate));
		when(peers.getAll()).thenReturn(List.of());
		when(identityService.getNodeIdentityAddress()).thenReturn(localIdentity);
		when(nettyClient.connect(candidate)).thenReturn(mock(ChannelFuture.class));
		NodeConnectionManager manager = new NodeConnectionManager(
				scheduler, new SimpleMeterRegistry(), directory, peers,
				reputation, nettyClient, identityService);

		manager.maintenanceLoop();
		manager.maintenanceLoop();

		verify(nettyClient).connect(candidate);
	}
}
