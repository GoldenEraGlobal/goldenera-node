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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationContext;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationSession;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.BlockValidator.PreparedHeaderValidation;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedHeader;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.events.P2PHeadersReceivedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BlockSyncManagerHeaderPipelineTest {

	@Test
	@Timeout(5)
	void legacyPagesComposeLocallyIntoTwoIndependentValidationWindows() {
		Fixture fixture = fixture(2);
		RemotePeer peer = fixture.peer(P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT, 2_000);

		try {
			List<BlockSyncManagerService.HeaderWindow> windows =
					fixture.service.downloadHeaderWindowsForTesting(peer, fixture.localBest, 2);

			assertThat(windows).hasSize(2);
			assertThat(windows).allSatisfy(window ->
					assertThat(window.headers()).hasSize(P2PSyncProtocol.INTERNAL_VALIDATION_WINDOW_HEADERS));
			assertThat(fixture.requestedPages).containsExactly(1_000, 1_000);
			var telemetry = fixture.service.runtimeSnapshot();
			assertThat(telemetry.maxHeaderPageRequested()).isEqualTo(1_000);
			assertThat(telemetry.legacyHeaderPageRequests()).isEqualTo(2);
			assertThat(telemetry.v2HeaderPageRequests()).isZero();
			assertThat(telemetry.peakBufferedHeaderCount()).isBetween(1_000, 2_000);
		} finally {
			fixture.service.stop();
		}
	}

	@Test
	@Timeout(5)
	void negotiatedV2PageSplitsIntoFourIndependentValidationWindows() {
		Fixture fixture = fixture(4);
		RemotePeer peer = fixture.peer(P2PSyncProtocol.V2_HEADER_PAGE_LIMIT, 4_096);

		try {
			List<BlockSyncManagerService.HeaderWindow> windows =
					fixture.service.downloadHeaderWindowsForTesting(peer, fixture.localBest, 4);

			assertThat(windows).hasSize(5);
			assertThat(windows.subList(0, 4)).allSatisfy(window ->
					assertThat(window.headers()).hasSize(1_000));
			assertThat(windows.getLast().headers()).hasSize(96);
			assertThat(fixture.requestedPages).containsExactly(4_096);
			var telemetry = fixture.service.runtimeSnapshot();
			assertThat(telemetry.maxHeaderPageRequested()).isEqualTo(4_096);
			assertThat(telemetry.legacyHeaderPageRequests()).isZero();
			assertThat(telemetry.v2HeaderPageRequests()).isOne();
			assertThat(telemetry.peakBufferedHeaderCount()).isBetween(1_000, 4_096);
		} finally {
			fixture.service.stop();
		}
	}

	@Test
	@Timeout(5)
	void nextWindowValidationRunsWhileTheCallingThreadCanProcessCurrentBodies() throws Exception {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(anyInt())).thenReturn(2);
		List<BlockHeader> headers = linkedHeaders(Hash.hash(Bytes.of(9)), 1, 4);
		ProofOfWorkVerificationContext context = new ProofOfWorkVerificationContext(Bytes.of(1));
		Map<BlockHeader, PreparedHeaderValidation> prepared = new IdentityHashMap<>();
		for (BlockHeader header : headers) {
			PreparedHeaderValidation item = mock(PreparedHeaderValidation.class);
			when(item.verificationContext()).thenReturn(context);
			prepared.put(header, item);
		}
		when(validator.prepareHeader(any(BlockHeader.class), anyMap()))
				.thenAnswer(invocation -> prepared.get(invocation.getArgument(0)));
		when(validator.openVerificationSession(context)).thenAnswer(ignored -> {
			ProofOfWorkVerificationSession session = mock(ProofOfWorkVerificationSession.class);
			when(session.mode()).thenReturn(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			return session;
		});
		CountDownLatch validationEntered = new CountDownLatch(1);
		CountDownLatch bodyWorkCompleted = new CountDownLatch(1);
		when(validator.validatePreparedHeader(any(PreparedHeaderValidation.class),
				any(ProofOfWorkVerificationSession.class))).thenAnswer(ignored -> {
			validationEntered.countDown();
			assertThat(bodyWorkCompleted.await(1, TimeUnit.SECONDS)).isTrue();
			return mock(StatelessValidatedHeader.class);
		});
		BlockSyncManagerService service = service(validator, mock(ChainQuery.class));
		BlockSyncManagerService.HeaderWindow window = new BlockSyncManagerService.HeaderWindow(
				headers, headers.size(), mock(RemotePeer.class));

		try {
			CompletableFuture<BlockSyncManagerService.ValidatedHeaderWindow> future =
					service.validateHeaderWindowAsync(window, Map.of());
			assertThat(validationEntered.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(future).isNotDone();
			bodyWorkCompleted.countDown();
			assertThat(future.get(2, TimeUnit.SECONDS).proofs()).hasSize(headers.size());
		} finally {
			bodyWorkCompleted.countDown();
			service.stop();
		}
	}

	@Test
	void speculativeNextWindowFailureDoesNotResumeMiningBeforeCurrentBodyStageFinishes() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(anyInt())).thenReturn(1);
		ProofOfWorkVerificationContext context = new ProofOfWorkVerificationContext(Bytes.of(8));
		PreparedHeaderValidation prepared = mock(PreparedHeaderValidation.class);
		when(prepared.verificationContext()).thenReturn(context);
		when(validator.prepareHeader(any(BlockHeader.class), anyMap())).thenReturn(prepared);
		when(validator.openVerificationSession(context)).thenAnswer(ignored -> {
			ProofOfWorkVerificationSession session = mock(ProofOfWorkVerificationSession.class);
			when(session.mode()).thenReturn(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			return session;
		});
		when(validator.validatePreparedHeader(eq(prepared), any(ProofOfWorkVerificationSession.class)))
				.thenThrow(new IllegalStateException("invalid speculative header"));
		MiningService mining = mock(MiningService.class);
		BlockSyncManagerService service = service(validator, mock(ChainQuery.class), mining);
		BlockSyncManagerService.HeaderWindow window = new BlockSyncManagerService.HeaderWindow(
				linkedHeaders(Hash.hash(Bytes.of(5)), 2, 1), 1L, mock(RemotePeer.class));

		try {
			assertThatThrownBy(() -> service.validateHeaderWindowAsync(window, Map.of()).join())
					.hasRootCauseMessage("invalid speculative header");
			verify(mining).pauseMining();
			verify(mining, never()).resumeMining();
		} finally {
			service.stop();
		}
	}

	@Test
	@Timeout(5)
	void failedBodyStageDrainsSpeculativeValidationBeforeMiningCanResume() throws Exception {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(anyInt())).thenReturn(1);
		ProofOfWorkVerificationContext context = new ProofOfWorkVerificationContext(Bytes.of(6));
		PreparedHeaderValidation prepared = mock(PreparedHeaderValidation.class);
		when(prepared.verificationContext()).thenReturn(context);
		when(validator.prepareHeader(any(BlockHeader.class), anyMap())).thenReturn(prepared);
		when(validator.openVerificationSession(context)).thenAnswer(ignored -> {
			ProofOfWorkVerificationSession session = mock(ProofOfWorkVerificationSession.class);
			when(session.mode()).thenReturn(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			return session;
		});
		CountDownLatch validationEntered = new CountDownLatch(1);
		CountDownLatch validationMayFinish = new CountDownLatch(1);
		when(validator.validatePreparedHeader(eq(prepared), any(ProofOfWorkVerificationSession.class)))
				.thenAnswer(ignored -> {
					validationEntered.countDown();
					assertThat(validationMayFinish.await(2, TimeUnit.SECONDS)).isTrue();
					return mock(StatelessValidatedHeader.class);
				});
		BlockSyncManagerService service = service(validator, mock(ChainQuery.class));
		BlockSyncManagerService.HeaderWindow window = new BlockSyncManagerService.HeaderWindow(
				linkedHeaders(Hash.hash(Bytes.of(6)), 3, 1), 1L, mock(RemotePeer.class));

		try {
			CompletableFuture<BlockSyncManagerService.ValidatedHeaderWindow> validation =
					service.validateHeaderWindowAsync(window, Map.of());
			assertThat(validationEntered.await(1, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<Void> drain = CompletableFuture.runAsync(
					() -> service.cancelAndDrainSpeculativeValidation(validation));
			assertThatThrownBy(() -> drain.get(100, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			validationMayFinish.countDown();
			drain.get(2, TimeUnit.SECONDS);
			assertThat(validation).isCancelled();
		} finally {
			validationMayFinish.countDown();
			service.stop();
		}
	}

	@Test
	void adaptiveDepthUsesTailLightAndStableFullBounds() {
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(9_000, 10_000, null, 0)).isOne();
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(
				0, 10_000, ProofOfWorkVerificationMode.RANDOMX_LIGHT, 20)).isEqualTo(2);
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(
				0, 10_000, ProofOfWorkVerificationMode.RANDOMX_FULL, 1)).isEqualTo(2);
		assertThat(BlockSyncManagerService.calculateHeaderPrefetchDepth(
				0, 10_000, ProofOfWorkVerificationMode.RANDOMX_FULL, 2)).isEqualTo(4);
	}

	@Test
	void validatedPriorWindowSeedHashIsAvailableToTheNextWindowPreparation() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(anyInt())).thenReturn(1);
		ProofOfWorkVerificationContext verificationContext =
				new ProofOfWorkVerificationContext(Bytes.of(7));
		PreparedHeaderValidation prepared = mock(PreparedHeaderValidation.class);
		when(prepared.verificationContext()).thenReturn(verificationContext);
		AtomicReference<Map<Long, Hash>> observedContext = new AtomicReference<>();
		when(validator.prepareHeader(any(BlockHeader.class), anyMap())).thenAnswer(invocation -> {
			observedContext.set(Map.copyOf(invocation.getArgument(1)));
			return prepared;
		});
		when(validator.openVerificationSession(verificationContext)).thenAnswer(ignored -> {
			ProofOfWorkVerificationSession session = mock(ProofOfWorkVerificationSession.class);
			when(session.mode()).thenReturn(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			return session;
		});
		when(validator.validatePreparedHeader(eq(prepared), any(ProofOfWorkVerificationSession.class)))
				.thenReturn(mock(StatelessValidatedHeader.class));
		BlockSyncManagerService service = service(validator, mock(ChainQuery.class));
		BlockHeader seedHeader = linkedHeaders(Hash.hash(Bytes.of(3)), 1_024, 1).getFirst();
		Map<Long, Hash> prior = new HashMap<>();
		service.rememberValidatedHeaders(prior, List.of(seedHeader));
		BlockHeader next = linkedHeaders(seedHeader.getHash(), 1_025, 1).getFirst();

		try {
			assertThat(service.validateBatchWithMiningCoordination(List.of(next), Map.copyOf(prior)).proofs())
					.hasSize(1);
			assertThat(observedContext.get()).containsEntry(1_024L, seedHeader.getHash());
		} finally {
			service.stop();
		}
	}

	@Test
	void staleCanonicalWindowIsRejectedAndPeerSwitchResetsAdaptiveState() {
		ChainQuery chainQuery = mock(ChainQuery.class);
		BlockSyncManagerService service = service(mock(BlockValidator.class), chainQuery);
		RemotePeer firstPeer = mock(RemotePeer.class);
		RemotePeer secondPeer = mock(RemotePeer.class);
		service.selectHeaderSyncPeer(firstPeer);
		ReflectionTestUtils.setField(service, "lastHeaderPrefetchDepth", 4);
		ReflectionTestUtils.setField(service, "lastHeaderValidationMode",
				ProofOfWorkVerificationMode.RANDOMX_FULL);
		ReflectionTestUtils.setField(service, "consecutiveFullValidationWindows", 3);
		service.selectHeaderSyncPeer(secondPeer);
		assertThat(service.headerPipelineSnapshot().depthLimit()).isOne();
		assertThat(service.headerPipelineSnapshot().mode()).isNull();
		assertThat(service.headerPipelineSnapshot().consecutiveFullWindows()).isZero();

		Hash parent = Hash.hash(Bytes.of(4));
		BlockHeader expected = linkedHeaders(parent, 1, 1).getFirst();
		StoredBlock competing = mock(StoredBlock.class);
		when(competing.getHeight()).thenReturn(1L);
		when(competing.getHash()).thenReturn(Hash.hash(Bytes.of(99)));
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(competing);
		BlockSyncManagerService.HeaderWindow window = new BlockSyncManagerService.HeaderWindow(
				List.of(expected), 1L, secondPeer);

		try {
			assertThatThrownBy(() -> service.assertCommittedWindow(window))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Canonical head changed");
		} finally {
			service.stop();
		}
	}

	private Fixture fixture(int depth) {
		ChainQuery chainQuery = mock(ChainQuery.class);
		Hash parentHash = Hash.hash(Bytes.of(1));
		when(chainQuery.getLocatorHashes()).thenReturn(new LinkedHashSet<>(List.of(parentHash)));
		when(chainQuery.hasBlockData(parentHash)).thenReturn(true);
		when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(0L));
		Block localBest = mock(Block.class);
		when(localBest.getHeight()).thenReturn(0L);
		BlockSyncManagerService service = service(mock(BlockValidator.class), chainQuery);
		return new Fixture(service, localBest, parentHash);
	}

	private BlockSyncManagerService service(BlockValidator validator, ChainQuery chainQuery) {
		return service(validator, chainQuery, mock(MiningService.class));
	}

	private BlockSyncManagerService service(
			BlockValidator validator, ChainQuery chainQuery, MiningService miningService) {
		return new BlockSyncManagerService(
				new SimpleMeterRegistry(), new ReentrantLock(), Runnable::run,
				miningService, mock(IdentityService.class), validator, chainQuery,
				mock(BlockReorgs.class), mock(PeerRegistry.class), mock(PeerReputationService.class),
				mock(BlockIngestionService.class), mock(SyncVerificationAccelerationPolicy.class));
	}

	private static List<BlockHeader> linkedHeaders(Hash parentHash, int firstHeight, int count) {
		List<BlockHeader> headers = new ArrayList<>(count);
		Hash previous = parentHash;
		for (int offset = 0; offset < count; offset++) {
			BlockHeader header = mock(BlockHeader.class);
			Hash hash = Hash.hash(Bytes.ofUnsignedInt(firstHeight + offset + 10));
			when(header.getPreviousHash()).thenReturn(previous);
			when(header.getHash()).thenReturn(hash);
			when(header.getHeight()).thenReturn((long) firstHeight + offset);
			when(header.getSize()).thenReturn(1);
			when(header.getDifficulty()).thenReturn(BigInteger.ONE);
			headers.add(header);
			previous = hash;
		}
		return headers;
	}

	private final class Fixture {
		private final BlockSyncManagerService service;
		private final Block localBest;
		private final Hash parentHash;
		private final List<Integer> requestedPages = new ArrayList<>();

		private Fixture(
				BlockSyncManagerService service,
				Block localBest,
				Hash parentHash) {
			this.service = service;
			this.localBest = localBest;
			this.parentHash = parentHash;
		}

		private RemotePeer peer(int negotiatedPage, int totalHeaders) {
			RemotePeer peer = mock(RemotePeer.class);
			when(peer.negotiatedHeaderPageLimit()).thenReturn(negotiatedPage);
			AtomicInteger requestIds = new AtomicInteger();
			when(peer.reserveRequestId()).thenAnswer(ignored -> (long) requestIds.incrementAndGet());
			List<BlockHeader> headers = linkedHeaders(parentHash, 1, totalHeaders);
			Hash headHash = headers.getLast().getHash();
			when(peer.getHeadHash()).thenReturn(headHash);
			AtomicInteger next = new AtomicInteger();
			doAnswer(invocation -> {
				int requested = invocation.getArgument(2);
				long requestId = invocation.getArgument(3);
				requestedPages.add(requested);
				int start = next.getAndAdd(requested);
				int end = Math.min(totalHeaders, start + requested);
				List<BlockHeader> page = start >= end ? List.of() : headers.subList(start, end);
				service.onHeadersReceived(new P2PHeadersReceivedEvent(
						this, requestId, peer, List.copyOf(page)));
				return null;
			}).when(peer).sendGetBlockHeaders(any(), any(), anyInt(), anyLong());
			return peer;
		}
	}
}
