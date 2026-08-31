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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.events.P2PHeadersReceivedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BlockSyncManagerBroadcastHeaderSecurityTest {

	@Test
	void remoteDifficultyZeroAndNonValidatorDifficultyOneNeverFanOutBodyRequests() {
		Fixture fixture = fixture();
		doThrow(new GEValidationException("difficulty zero"))
				.when(fixture.validator).validateHeader(fixture.header);

		fixture.service.onHeadersReceived(new P2PHeadersReceivedEvent(
				this, 0, fixture.peer, List.of(fixture.header)));

		verify(fixture.peer, never()).sendGetBlockBodies(any(), anyLong());

		Fixture nonValidator = fixture();
		doThrow(new GEValidationException("not an active validator"))
				.when(nonValidator.ingestion).validateBroadcastHeaderContext(nonValidator.header, nonValidator.parentBlock);

		nonValidator.service.onHeadersReceived(new P2PHeadersReceivedEvent(
				this, 0, nonValidator.peer, List.of(nonValidator.header)));

		verify(nonValidator.peer, never()).sendGetBlockBodies(any(), anyLong());
	}

	@Test
	void synchronousBroadcastBodySendFailureRemovesPendingRequestImmediately() {
		Fixture fixture = fixture();
		when(fixture.peer.reserveRequestId()).thenReturn(73L);
		doThrow(new IllegalStateException("channel closed"))
				.when(fixture.peer).sendGetBlockBodies(any(), anyLong());

		fixture.service.onHeadersReceived(new P2PHeadersReceivedEvent(
				this, 0, fixture.peer, List.of(fixture.header)));

		assertThat(fixture.service.runtimeSnapshot().pendingBodyRequests()).isZero();
		assertThat(fixture.service.runtimeSnapshot().pendingBroadcastDownloads()).isZero();
	}

	@Test
	void oldHeadersAndGlobalDownloadCapAreRejectedBeforeExpensiveValidation() {
		Fixture old = fixture();
		when(old.header.getHeight()).thenReturn(1L);
		when(old.localBest.getHeight()).thenReturn(100L);

		old.service.onHeadersReceived(new P2PHeadersReceivedEvent(this, 0, old.peer, List.of(old.header)));

		verify(old.validator, never()).validateHeader(any());
		verify(old.peer, never()).sendGetBlockBodies(any(), anyLong());

		Fixture capped = fixture();
		for (int index = 0; index < BlockSyncManagerService.MAX_PENDING_BROADCAST_DOWNLOADS; index++) {
			assertThat(capped.service.tryTrackBroadcastDownload(
					Hash.hash(Bytes.ofUnsignedInt(1_000 + index)))).isTrue();
		}

		capped.service.onHeadersReceived(new P2PHeadersReceivedEvent(
				this, 0, capped.peer, List.of(capped.header)));

		verify(capped.validator, never()).validateHeader(any());
		verify(capped.peer, never()).sendGetBlockBodies(any(), anyLong());
	}

	@Test
	void missingParentFloodCannotAccelerateRunningSyncLoopOrRequestBodies() throws Exception {
		Fixture fixture = fixture();
		AtomicInteger syncChecks = new AtomicInteger();
		CountDownLatch firstCheck = new CountDownLatch(1);
		CountDownLatch unexpectedSecondCheck = new CountDownLatch(1);
		when(fixture.peerRegistry.getSyncCandidate(any())).thenAnswer(invocation -> {
			if (syncChecks.incrementAndGet() == 1) {
				firstCheck.countDown();
			} else {
				unexpectedSecondCheck.countDown();
			}
			return Optional.empty();
		});

		fixture.service.start();
		try {
			assertThat(firstCheck.await(1, TimeUnit.SECONDS)).isTrue();
			for (int index = 0; index < 1_000; index++) {
				BlockHeader missingParent = mock(BlockHeader.class);
				when(missingParent.getHash()).thenReturn(Hash.hash(Bytes.ofUnsignedInt(2_000 + index)));
				when(missingParent.getPreviousHash()).thenReturn(Hash.hash(Bytes.ofUnsignedInt(3_000 + index)));
				when(missingParent.getHeight()).thenReturn(11L);
				fixture.service.onHeadersReceived(new P2PHeadersReceivedEvent(
						this, 0, fixture.peer, List.of(missingParent)));
			}

			assertThat(unexpectedSecondCheck.await(300, TimeUnit.MILLISECONDS)).isFalse();
			assertThat(syncChecks).hasValue(1);
			verify(fixture.validator, never()).validateHeader(any());
			verify(fixture.peer, never()).sendGetBlockBodies(any(), anyLong());
		} finally {
			fixture.service.stop();
		}
	}

	private Fixture fixture() {
		BlockHeader header = mock(BlockHeader.class);
		Hash headerHash = Hash.hash(Bytes.of(1));
		Hash parentHash = Hash.hash(Bytes.of(2));
		when(header.getHash()).thenReturn(headerHash);
		when(header.getPreviousHash()).thenReturn(parentHash);
		when(header.getHeight()).thenReturn(11L);
		StoredBlock localBest = mock(StoredBlock.class);
		when(localBest.getHeight()).thenReturn(10L);
		StoredBlock parent = mock(StoredBlock.class);
		Block parentBlock = mock(Block.class);
		when(parent.getBlock()).thenReturn(parentBlock);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getStoredBlockByHash(headerHash)).thenReturn(Optional.empty());
		when(chainQuery.getStoredBlockByHash(parentHash)).thenReturn(Optional.of(parent));
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(localBest);
		BlockValidator validator = mock(BlockValidator.class);
		BlockIngestionService ingestion = mock(BlockIngestionService.class);
		when(ingestion.isOrphan(headerHash)).thenReturn(false);
		PeerRegistry peerRegistry = mock(PeerRegistry.class);
		RemotePeer peer = mock(RemotePeer.class);
		Executor directExecutor = Runnable::run;
		BlockSyncManagerService service = new BlockSyncManagerService(
				new SimpleMeterRegistry(),
				new ReentrantLock(),
				directExecutor,
				mock(MiningService.class),
				mock(IdentityService.class),
				validator,
				chainQuery,
				mock(BlockReorgs.class),
				peerRegistry,
				mock(PeerReputationService.class),
				ingestion,
				mock(SyncVerificationAccelerationPolicy.class));
		return new Fixture(service, validator, ingestion, peerRegistry, peer, header, parentBlock, localBest);
	}

	private record Fixture(BlockSyncManagerService service, BlockValidator validator,
			BlockIngestionService ingestion, PeerRegistry peerRegistry, RemotePeer peer, BlockHeader header,
			Block parentBlock, StoredBlock localBest) {
	}
}
