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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PHeadersReceivedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BlockSyncManagerRequestCorrelationTest {

	@Test
	void synchronousHeaderSendFailureRemovesPendingRequestImmediately() {
		Fixture fixture = fixture();
		RemotePeer peer = mock(RemotePeer.class);
		Block localBest = mock(Block.class);
		when(peer.reserveRequestId()).thenReturn(41L);
		doThrow(new IllegalStateException("channel closed"))
				.when(peer).sendGetBlockHeaders(anyList(), any(), anyInt(), eq(41L));

		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
				fixture.service, "downloadHeaders", peer, localBest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("channel closed");
		assertThat(fixture.service.runtimeSnapshot().pendingHeaderRequests()).isZero();
	}

	@Test
	void sameRequestIdFromDifferentPeersCompletesOnlyMatchingBodyRequest() {
		Fixture fixture = fixture();
		RemotePeer firstPeer = mock(RemotePeer.class);
		RemotePeer secondPeer = mock(RemotePeer.class);
		long requestId = 19L;
		CompletableFuture<List<List<Tx>>> first = new CompletableFuture<>();
		CompletableFuture<List<List<Tx>>> second = new CompletableFuture<>();
		fixture.service.registerBodyRequest(
				new BlockSyncManagerService.PeerRequestKey(firstPeer, requestId), first);
		fixture.service.registerBodyRequest(
				new BlockSyncManagerService.PeerRequestKey(secondPeer, requestId), second);
		List<List<Tx>> secondBodies = List.of(List.of(mock(Tx.class)));

		fixture.service.onBodiesReceived(new P2PBlockBodiesReceivedEvent(
				this, requestId, secondPeer, secondBodies));

		assertThat(second).isCompletedWithValue(secondBodies);
		assertThat(first).isNotDone();
		assertThat(fixture.service.runtimeSnapshot().pendingBodyRequests()).isEqualTo(1);

		fixture.service.onBodiesReceived(new P2PBlockBodiesReceivedEvent(
				this, requestId, firstPeer, List.of()));
		assertThat(first).isCompletedWithValue(List.of());
		assertThat(fixture.service.runtimeSnapshot().pendingBodyRequests()).isZero();
	}

	@Test
	void wrongPeerAndUnsolicitedResponsesDoNotConsumePendingRequestsAndAreMetered() {
		Fixture fixture = fixture();
		RemotePeer expectedPeer = mock(RemotePeer.class);
		RemotePeer wrongPeer = mock(RemotePeer.class);
		long requestId = 23L;
		CompletableFuture<List<BlockHeader>> pending = new CompletableFuture<>();
		fixture.service.registerHeaderRequest(
				new BlockSyncManagerService.PeerRequestKey(expectedPeer, requestId), pending);

		fixture.service.onHeadersReceived(new P2PHeadersReceivedEvent(
				this, requestId, wrongPeer, List.of()));
		fixture.service.onHeadersReceived(new P2PHeadersReceivedEvent(
				this, requestId + 1, wrongPeer, List.of()));

		assertThat(pending).isNotDone();
		assertThat(fixture.service.runtimeSnapshot().pendingHeaderRequests()).isEqualTo(1);
		assertThat(fixture.registry.counter("p2p.sync.responses.rejected", "type", "headers", "reason", "wrong_peer")
				.count()).isEqualTo(1.0);
		assertThat(fixture.registry.counter("p2p.sync.responses.rejected", "type", "headers", "reason", "unsolicited")
				.count()).isEqualTo(1.0);
	}

	@Test
	void stopCancelsAndClearsEveryPendingRequestKind() {
		Fixture fixture = fixture();
		RemotePeer peer = mock(RemotePeer.class);
		CompletableFuture<List<BlockHeader>> headers = new CompletableFuture<>();
		CompletableFuture<List<List<Tx>>> bodies = new CompletableFuture<>();
		fixture.service.registerHeaderRequest(new BlockSyncManagerService.PeerRequestKey(peer, 1L), headers);
		fixture.service.registerBodyRequest(new BlockSyncManagerService.PeerRequestKey(peer, 2L), bodies);
		assertThat(fixture.service.tryTrackBroadcastDownload(Hash.ZERO)).isTrue();

		fixture.service.stop();

		assertThat(headers).isCancelled();
		assertThat(bodies).isCancelled();
		assertThat(fixture.service.runtimeSnapshot().pendingHeaderRequests()).isZero();
		assertThat(fixture.service.runtimeSnapshot().pendingBodyRequests()).isZero();
		assertThat(fixture.service.runtimeSnapshot().pendingBroadcastDownloads()).isZero();
	}

	private Fixture fixture() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Executor directExecutor = Runnable::run;
		BlockSyncManagerService service = new BlockSyncManagerService(
				registry,
				new ReentrantLock(),
				directExecutor,
				mock(MiningService.class),
				mock(IdentityService.class),
				mock(BlockValidator.class),
				mock(ChainQuery.class),
				mock(BlockReorgs.class),
				mock(PeerRegistry.class),
				mock(PeerReputationService.class),
				mock(BlockIngestionService.class),
				mock(SyncVerificationAccelerationPolicy.class));
		return new Fixture(service, registry);
	}

	private record Fixture(BlockSyncManagerService service, SimpleMeterRegistry registry) {
	}
}
