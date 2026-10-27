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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidationException;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.events.P2PBlockReceivedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;

class BlockSyncManagerBroadcastIngestionTest {

	private static final Address PEER = Address.fromHexString("0x2222222222222222222222222222222222222222");

	private final BlockIngestionService ingestion = mock(BlockIngestionService.class);
	private final BlockValidator validator = mock(BlockValidator.class);
	private final PeerReputationService reputation = mock(PeerReputationService.class);
	private final ChainQuery chainQuery = mock(ChainQuery.class);
	private final StoredBlock tip = mock(StoredBlock.class);
	private final CountDownLatch releaseIngestion = new CountDownLatch(1);

	@Test
	@Timeout(5)
	void broadcastBlockIsIngestedOffTheReceivingThread() {
		BlockSyncManagerService service = serviceWithBlockedIngestion(BlockIngestionOutcome.Code.REJECTED_CONTEXTUAL);
		RemotePeer peer = peer();
		try {
			service.onNewBlock(new P2PBlockReceivedEvent(this, 0, peer, mock(Block.class)));
			releaseIngestion.countDown();

			verify(reputation, timeout(2_000)).recordFailure(PEER);
			verify(peer, never()).disconnect(any());
		} finally {
			service.stop();
		}
	}

	@Test
	@Timeout(5)
	void invalidBroadcastBlockDisconnectsThePeerWithoutReachingIngestion() {
		BlockSyncManagerService service = serviceWithBlockedIngestion(BlockIngestionOutcome.Code.ACCEPTED);
		when(validator.validateFullBlock(any(Block.class))).thenThrow(new BlockValidationException(
				BlockValidationException.Category.STATELESS, "invalid proof of work", null));
		RemotePeer peer = peer();
		try {
			service.onNewBlock(new P2PBlockReceivedEvent(this, 0, peer, mock(Block.class)));

			verify(peer, timeout(2_000)).disconnect(any());
			verify(reputation).recordFailure(PEER);
			verify(ingestion, never()).processValidatedBlock(any(), any(), any(), any());
		} finally {
			releaseIngestion.countDown();
			service.stop();
		}
	}

	@Test
	@Timeout(5)
	void broadcastBlockBelowTheReorgWindowIsIgnoredBeforeProofOfWork() {
		BlockSyncManagerService service = serviceWithBlockedIngestion(BlockIngestionOutcome.Code.ACCEPTED);
		when(tip.getHeight()).thenReturn(100L);
		Block oldBlock = mock(Block.class);
		when(oldBlock.getHeight()).thenReturn(100L - BlockSyncManagerService.MAX_BROADCAST_REORG_DEPTH - 1);
		RemotePeer peer = peer();
		try {
			service.onNewBlock(new P2PBlockReceivedEvent(this, 0, peer, oldBlock));

			verify(tip, timeout(2_000)).getHeight();
			verify(validator, after(100).never()).validateFullBlock(any(Block.class));
			verify(peer, never()).disconnect(any());
		} finally {
			releaseIngestion.countDown();
			service.stop();
		}
	}

	@Test
	@Timeout(5)
	void fullBroadcastQueueRejectsInsteadOfBlockingTheReceivingThread() {
		BlockSyncManagerService service = serviceWithBlockedIngestion(BlockIngestionOutcome.Code.ACCEPTED);
		RemotePeer peer = peer();
		try {
			for (int i = 0; i <= BlockSyncManagerService.BROADCAST_BLOCK_QUEUE_CAPACITY; i++) {
				service.onNewBlock(new P2PBlockReceivedEvent(this, 0, peer, mock(Block.class)));
			}

			assertThatThrownBy(() -> service.onNewBlock(new P2PBlockReceivedEvent(this, 0, peer, mock(Block.class))))
					.isInstanceOf(RejectedExecutionException.class);
		} finally {
			releaseIngestion.countDown();
			service.stop();
		}
	}

	private BlockSyncManagerService serviceWithBlockedIngestion(BlockIngestionOutcome.Code outcome) {
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(tip);
		when(validator.validateFullBlock(any(Block.class))).thenReturn(mock(StatelessValidatedBlock.class));
		when(ingestion.processValidatedBlock(any(), eq(ConnectedSource.BROADCAST), any(), any()))
				.thenAnswer(invocation -> {
					releaseIngestion.await();
					return BlockIngestionOutcome.of(outcome);
				});
		return new BlockSyncManagerService(
				new SimpleMeterRegistry(),
				new ReentrantLock(),
				Runnable::run,
				mock(MiningService.class),
				mock(IdentityService.class),
				validator,
				chainQuery,
				mock(BlockReorgs.class),
				mock(PeerRegistry.class),
				reputation,
				ingestion,
				mock(SyncVerificationAccelerationPolicy.class));
	}

	private RemotePeer peer() {
		RemotePeer peer = mock(RemotePeer.class);
		Channel channel = mock(Channel.class);
		when(channel.isActive()).thenReturn(true);
		when(peer.getChannel()).thenReturn(channel);
		when(peer.getIdentity()).thenReturn(PEER);
		return peer;
	}
}
