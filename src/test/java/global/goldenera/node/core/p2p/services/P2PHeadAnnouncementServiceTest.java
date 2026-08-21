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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockConnectionBatchCompletedEvent;
import global.goldenera.node.core.p2p.events.P2PHandshakeCompletedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;

class P2PHeadAnnouncementServiceTest {

	@Test
	void announcesCanonicalHeadsConnectedBySyncForDownstreamPeers() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		P2PHeadAnnouncementService service = new P2PHeadAnnouncementService(
				scheduler, mock(PeerRegistry.class), mock(P2PStatusProvider.class));
		BlockConnectedEvent blockEvent = mock(BlockConnectedEvent.class);
		when(blockEvent.getConnectedSource()).thenReturn(ConnectedSource.SYNC);
		when(blockEvent.isBatchMember()).thenReturn(true);

		service.onBlockConnected(blockEvent);
		verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));

		service.onBlockConnectionBatchCompleted(new BlockConnectionBatchCompletedEvent(
				this, ConnectedSource.SYNC, List.of(blockEvent)));

		verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
	}

	@Test
	void announcesCurrentHeadToNewlyHandshakenPeers() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		P2PHeadAnnouncementService service = new P2PHeadAnnouncementService(
				scheduler, mock(PeerRegistry.class), mock(P2PStatusProvider.class));

		service.onHandshakeCompleted(mock(P2PHandshakeCompletedEvent.class));

		verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
	}

	@Test
	void coalescesBurstsAndSendsLatestStatusOnlyToHandshakenPeers() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		P2PStatusProvider statusProvider = mock(P2PStatusProvider.class);
		P2PStatusDto status = mock(P2PStatusDto.class);
		RemotePeer handshaken = mock(RemotePeer.class);
		RemotePeer awaitingHandshake = mock(RemotePeer.class);
		when(handshaken.getIdentity()).thenReturn(Address.ZERO);
		when(peers.getAll()).thenReturn(List.of(handshaken, awaitingHandshake));
		when(statusProvider.currentStatus()).thenReturn(status);
		ArgumentCaptor<Runnable> scheduled = ArgumentCaptor.forClass(Runnable.class);
		P2PHeadAnnouncementService service = new P2PHeadAnnouncementService(scheduler, peers, statusProvider);

		assertThat(service.requestAnnouncement()).isTrue();
		assertThat(service.requestAnnouncement()).isFalse();
		assertThat(service.requestAnnouncement()).isFalse();
		verify(scheduler, times(1)).schedule(scheduled.capture(), any(Instant.class));

		scheduled.getValue().run();

		verify(statusProvider).currentStatus();
		verify(handshaken).sendPong(status);
		verify(awaitingHandshake, never()).sendPong(any());
	}
}
