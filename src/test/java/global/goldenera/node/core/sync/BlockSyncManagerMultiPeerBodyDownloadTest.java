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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedHeader;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesReceivedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BlockSyncManagerMultiPeerBodyDownloadTest {

	@Test
	void distributesRangesAcrossPeersAndPersistsInHeaderOrder() throws Exception {
		Fixture fixture = fixture(3);
		fixture.respondSuccessfully(fixture.firstPeer);
		fixture.respondSuccessfully(fixture.secondPeer);

		int processed = fixture.service.downloadAndPersistBodiesInBatches(
				fixture.firstPeer, fixture.headers, fixture.validatedHeaders);

		assertThat(processed).isEqualTo(fixture.headers.size());
		assertThat(fixture.requests).hasSize(3);
		assertThat(fixture.requests.get(0).peer()).isSameAs(fixture.firstPeer);
		assertThat(fixture.requests.get(1).peer()).isSameAs(fixture.secondPeer);
		assertThat(fixture.requests.get(0).hashes())
				.containsExactlyElementsOf(fixture.hashesForRange(0));
		assertThat(fixture.requests.get(1).hashes())
				.containsExactlyElementsOf(fixture.hashesForRange(1));
		assertThat(fixture.persistedHashes)
				.containsExactlyElementsOf(fixture.headers.stream().map(BlockHeader::getHash).toList());
		verify(fixture.reputation).recordSuccess(fixture.firstIdentity);
		verify(fixture.reputation).recordSuccess(fixture.secondIdentity);
		BlockSyncManagerService.SyncRuntimeSnapshot snapshot = fixture.service.runtimeSnapshot();
		assertThat(snapshot.bodyInflightReservedBytes()).isZero();
		assertThat(snapshot.activeBodyPeers()).isZero();
		assertThat(snapshot.bodyInflightPeakReservedBytes())
				.isPositive()
				.isLessThanOrEqualTo(snapshot.bodyInflightByteLimit());
	}

	@Test
	void retriesOnlyFailedRangeOnAnotherPeerAndPenalizesOnlyItsSender() throws Exception {
		Fixture fixture = fixture(3);
		AtomicBoolean firstResponse = new AtomicBoolean(true);
		fixture.respond(fixture.firstPeer, count -> firstResponse.getAndSet(false)
				? List.of()
				: emptyBodies(count));
		fixture.respondSuccessfully(fixture.secondPeer);

		int processed = fixture.service.downloadAndPersistBodiesInBatches(
				fixture.firstPeer, fixture.headers, fixture.validatedHeaders);

		assertThat(processed).isEqualTo(fixture.headers.size());
		assertThat(fixture.requests).hasSize(4);
		assertThat(fixture.requests.get(2).peer()).isSameAs(fixture.secondPeer);
		assertThat(fixture.requests.get(2).hashes())
				.containsExactlyElementsOf(fixture.requests.get(0).hashes());
		assertThat(fixture.persistedHashes)
				.containsExactlyElementsOf(fixture.headers.stream().map(BlockHeader::getHash).toList());
		verify(fixture.reputation).recordFailure(fixture.firstIdentity);
		verify(fixture.reputation, never()).recordFailure(fixture.secondIdentity);
		verify(fixture.firstPeer).disconnect(any(String.class));
		verify(fixture.secondPeer, never()).disconnect(any(String.class));
		assertThat(fixture.registry.counter("blockchain.sync.body_range.retries").count()).isEqualTo(1.0);
		assertThat(fixture.registry.counter("blockchain.sync.body_range.failovers").count()).isEqualTo(1.0);
	}

	@Test
	void inFlightBudgetNeverOvercommitsAndReleasesExactly() {
		BlockSyncManagerService.BodyInflightBudget budget =
				new BlockSyncManagerService.BodyInflightBudget(100);

		assertThat(budget.tryReserve(60)).isTrue();
		assertThat(budget.tryReserve(41)).isFalse();
		assertThat(budget.reservedBytes()).isEqualTo(60);
		budget.release(60);
		assertThat(budget.reservedBytes()).isZero();
		assertThat(budget.tryReserve(100)).isTrue();
		assertThat(budget.tryReserve(1)).isFalse();
	}

	@Test
	void sendFailureAfterPartialPipelineCleansEveryPendingRequest() throws Exception {
		Fixture fixture = fixture(3);
		fixture.holdResponses(fixture.firstPeer);
		doThrow(new IllegalStateException("channel closed"))
				.when(fixture.secondPeer).sendGetBlockBodies(any(), anyLong());

		assertThatThrownBy(() -> fixture.service.downloadAndPersistBodiesInBatches(
				fixture.firstPeer, fixture.headers, fixture.validatedHeaders))
				.hasMessageContaining("Failed to send body range");

		assertThat(fixture.service.runtimeSnapshot().pendingBodyRequests()).isZero();
		assertThat(fixture.service.runtimeSnapshot().bodyInflightReservedBytes()).isZero();
	}

	@Test
	void persistenceBatchFlushesOnCountOrActualBlockBytesAndAllowsOneLargeBlock() {
		assertThat(BlockSyncManagerService.shouldFlushPersistenceBatch(249, 127L * 1024 * 1024)).isFalse();
		assertThat(BlockSyncManagerService.shouldFlushPersistenceBatch(250, 1)).isTrue();
		assertThat(BlockSyncManagerService.shouldFlushPersistenceBatch(2, 128L * 1024 * 1024)).isTrue();
		assertThat(BlockSyncManagerService.shouldFlushPersistenceBatch(1, 140L * 1024 * 1024)).isTrue();
	}

	@Test
	void maxSizedBlocksFlushByBytesAndStillAllMakeProgress() throws Exception {
		Fixture fixture = fixture(4, 5_000_000);
		fixture.respondSuccessfully(fixture.firstPeer);
		fixture.respondSuccessfully(fixture.secondPeer);

		int processed = fixture.service.downloadAndPersistBodiesInBatches(
				fixture.firstPeer, fixture.headers, fixture.validatedHeaders);

		assertThat(processed).isEqualTo(28);
		assertThat(fixture.persistedBatchSizes).containsExactly(26, 2);
		assertThat(fixture.service.runtimeSnapshot().persistenceBatchPeakBytes())
				.isEqualTo(130_000_000)
				.isLessThanOrEqualTo(BlockSyncManagerService.MAX_PERSIST_BATCH_BYTES);
	}

	@Test
	void missingHeaderProofIsLocalFailureAndNeverPenalizesOrContactsPeer() throws Exception {
		Fixture fixture = fixture(1);
		Map<Hash, StatelessValidatedHeader> incomplete = new HashMap<>(fixture.validatedHeaders);
		incomplete.remove(fixture.headers.get(0).getHash());

		assertThatThrownBy(() -> fixture.service.downloadAndPersistBodiesInBatches(
				fixture.firstPeer, fixture.headers, incomplete))
				.hasMessageContaining("Missing stateless header proof");

		verify(fixture.firstPeer, never()).sendGetBlockBodies(any(), anyLong());
		verify(fixture.secondPeer, never()).sendGetBlockBodies(any(), anyLong());
		verify(fixture.reputation, never()).recordFailure(any(Address.class));
	}

	@Test
	void thousandBlockThreePeerHarnessKeepsAllSchedulerAndPersistenceBounds() throws Exception {
		Fixture fixture = fixtureForHeaderCount(1_000, 1024 * 1024);
		RemotePeer thirdPeer = peer(address(3));
		when(fixture.peers.getBodySyncPeers(anyLong()))
				.thenReturn(List.of(fixture.firstPeer, fixture.secondPeer, thirdPeer));
		fixture.respondSuccessfully(fixture.firstPeer);
		fixture.respondSuccessfully(fixture.secondPeer);
		fixture.respondSuccessfully(thirdPeer);

		int processed = fixture.service.downloadAndPersistBodiesInBatches(
				fixture.firstPeer, fixture.headers, fixture.validatedHeaders);
		BlockSyncManagerService.SyncRuntimeSnapshot snapshot = fixture.service.runtimeSnapshot();

		assertThat(processed).isEqualTo(1_000);
		assertThat(fixture.requests.stream().map(BodyRequest::peer).distinct()).hasSize(3);
		assertThat(snapshot.peakActiveBodyRequests()).isBetween(2, snapshot.pipelineDepthLimit());
		assertThat(snapshot.peakActiveBodyPeers()).isBetween(2, 3);
		assertThat(snapshot.bodyInflightPeakReservedBytes())
				.isLessThanOrEqualTo(snapshot.bodyInflightByteLimit());
		assertThat(snapshot.bodyInflightReservedBytes()).isZero();
		assertThat(snapshot.activeBodyRequests()).isZero();
		assertThat(fixture.service.runtimeSnapshot().pendingBodyRequests()).isZero();
		assertThat(fixture.persistedHashes)
				.containsExactlyElementsOf(fixture.headers.stream().map(BlockHeader::getHash).toList());
		assertThat(fixture.persistedBatchSizes).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(250));
		assertThat(fixture.persistedBatchBytes)
				.allSatisfy(bytes -> assertThat(bytes)
						.isLessThanOrEqualTo(BlockSyncManagerService.MAX_PERSIST_BATCH_BYTES));
		assertThat(fixture.persistedBatchSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1_000);
		assertThat(fixture.registry.counter("blockchain.sync.body.blocks").count()).isEqualTo(1_000.0);
		assertThat(fixture.registry.counter("blockchain.sync.body.bytes").count())
				.isEqualTo(1_000.0 * 1024 * 1024);
		assertThat(fixture.registry.counter("blockchain.sync.body_serving_peers.successful").count()).isEqualTo(3.0);
		assertThat(fixture.registry.counter("blockchain.sync.persistence.batches").count())
				.isEqualTo(fixture.persistedBatchSizes.size());
		assertThat(fixture.registry.counter("blockchain.sync.persistence.bytes").count())
				.isEqualTo(fixture.persistedBatchBytes.stream().mapToLong(Long::longValue).sum());
		assertThat(fixture.registry.find("blockchain.sync.stage")
				.tag("stage", "body_download_validation").timer().count()).isEqualTo(fixture.requests.size());
		assertThat(fixture.registry.find("blockchain.sync.stage")
				.tag("stage", "state_execution_db_commit").timer().count())
				.isEqualTo(fixture.persistedBatchSizes.size());
	}

	private Fixture fixture(int rangeCount) throws Exception {
		return fixture(rangeCount, 1);
	}

	private Fixture fixture(int rangeCount, int validatedBlockSize) throws Exception {
		return fixtureForHeaderCount(BlockSyncManagerService.calculateBodyBatchSize() * rangeCount,
				validatedBlockSize);
	}

	private Fixture fixtureForHeaderCount(int headerCount, int validatedBlockSize) throws Exception {
		BlockValidator validator = mock(BlockValidator.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		BlockReorgs blockReorgs = mock(BlockReorgs.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		PeerReputationService reputation = mock(PeerReputationService.class);
		Executor directExecutor = Runnable::run;
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		BlockSyncManagerService service = new BlockSyncManagerService(
				registry, new ReentrantLock(), directExecutor,
				mock(MiningService.class), mock(IdentityService.class), validator,
				chainQuery, blockReorgs, peers, reputation, mock(BlockIngestionService.class),
				mock(SyncVerificationAccelerationPolicy.class));

		Address firstIdentity = address(1);
		Address secondIdentity = address(2);
		RemotePeer firstPeer = peer(firstIdentity);
		RemotePeer secondPeer = peer(secondIdentity);
		when(peers.getBodySyncPeers(anyLong())).thenReturn(List.of(firstPeer, secondPeer));

		int rangeSize = BlockSyncManagerService.calculateBodyBatchSize();
		Hash parentHash = hash(90_000);
		StoredBlock ancestor = mock(StoredBlock.class);
		when(ancestor.getHash()).thenReturn(parentHash);
		when(ancestor.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(100));
		when(chainQuery.getStoredBlockByHashOrThrow(parentHash)).thenReturn(ancestor);

		List<BlockHeader> headers = new ArrayList<>();
		Map<Hash, StatelessValidatedHeader> proofs = new java.util.LinkedHashMap<>();
		Hash previousHash = parentHash;
		for (int index = 0; index < headerCount; index++) {
			BlockHeader header = mock(BlockHeader.class);
			Hash headerHash = hash(index + 1);
			when(header.getHash()).thenReturn(headerHash);
			when(header.getPreviousHash()).thenReturn(previousHash);
			when(header.getHeight()).thenReturn((long) index + 1);
			when(header.getDifficulty()).thenReturn(BigInteger.ONE);
			when(header.getIdentity()).thenReturn(address(100));
			when(header.getTimestamp()).thenReturn(Instant.ofEpochSecond(index + 1L));
			headers.add(header);
			proofs.put(headerHash, mock(StatelessValidatedHeader.class));
			previousHash = headerHash;
		}

		doAnswer(invocation -> {
			Block candidate = invocation.getArgument(0);
			BlockHeader header = candidate.getHeader();
			long height = header.getHeight();
			Hash blockHash = header.getHash();
			List<Tx> transactions = candidate.getTxs();
			Block immutableValidatedBlock = mock(Block.class);
			when(immutableValidatedBlock.getHeader()).thenReturn(header);
			when(immutableValidatedBlock.getHeight()).thenReturn(height);
			when(immutableValidatedBlock.getHash()).thenReturn(blockHash);
			when(immutableValidatedBlock.getSize()).thenReturn(validatedBlockSize);
			when(immutableValidatedBlock.getTxs()).thenReturn(transactions);
			StatelessValidatedBlock validated = mock(StatelessValidatedBlock.class);
			when(validated.block()).thenReturn(immutableValidatedBlock);
			when(validated.matches(immutableValidatedBlock)).thenReturn(true);
			return validated;
		}).when(validator).validateBlockBody(any(Block.class), any(StatelessValidatedHeader.class));

		List<Hash> persistedHashes = new ArrayList<>();
		List<Integer> persistedBatchSizes = new ArrayList<>();
		List<Long> persistedBatchBytes = new ArrayList<>();
		doAnswer(invocation -> {
			ValidatedSyncBatch batch = invocation.getArgument(0);
			persistedBatchSizes.add(batch.blocks().size());
			persistedBatchBytes.add(batch.blocks().stream().mapToLong(StoredBlock::getBlockSize).sum());
			batch.blocks().stream().map(StoredBlock::getHash).forEach(persistedHashes::add);
			return null;
		}).when(blockReorgs).executeAtomicReorgSwap(any(ValidatedSyncBatch.class));

		return new Fixture(service, registry, peers, reputation, firstPeer, secondPeer,
				firstIdentity, secondIdentity, headers, Map.copyOf(proofs),
				rangeSize, new ArrayList<>(), persistedHashes, persistedBatchSizes, persistedBatchBytes);
	}

	private RemotePeer peer(Address identity) {
		RemotePeer peer = mock(RemotePeer.class);
		AtomicLong requestIds = new AtomicLong();
		when(peer.getIdentity()).thenReturn(identity);
		when(peer.getHeadHeight()).thenReturn(Long.MAX_VALUE);
		when(peer.reserveRequestId()).thenAnswer(ignored -> requestIds.incrementAndGet());
		return peer;
	}

	private static List<List<Tx>> emptyBodies(int count) {
		List<List<Tx>> bodies = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			bodies.add(List.of());
		}
		return bodies;
	}

	private Address address(int value) {
		return Address.fromHexString("0x" + String.format("%040x", value));
	}

	private Hash hash(int value) {
		return Hash.hash(Bytes.ofUnsignedInt(value));
	}

	private interface ResponseFactory {
		List<List<Tx>> create(int requestedBodyCount);
	}

	private record BodyRequest(RemotePeer peer, List<Hash> hashes) {
	}

	private record Fixture(
			BlockSyncManagerService service,
			SimpleMeterRegistry registry,
			PeerRegistry peers,
			PeerReputationService reputation,
			RemotePeer firstPeer,
			RemotePeer secondPeer,
			Address firstIdentity,
			Address secondIdentity,
			List<BlockHeader> headers,
			Map<Hash, StatelessValidatedHeader> validatedHeaders,
			int rangeSize,
			List<BodyRequest> requests,
			List<Hash> persistedHashes,
			List<Integer> persistedBatchSizes,
			List<Long> persistedBatchBytes) {

		void respondSuccessfully(RemotePeer peer) {
			respond(peer, BlockSyncManagerMultiPeerBodyDownloadTest::emptyBodies);
		}

		void respond(RemotePeer peer, ResponseFactory responseFactory) {
			doAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<Hash> hashes = List.copyOf((List<Hash>) invocation.getArgument(0));
				long requestId = invocation.getArgument(1);
				requests.add(new BodyRequest(peer, hashes));
				service.onBodiesReceived(new P2PBlockBodiesReceivedEvent(
						this, requestId, peer, responseFactory.create(hashes.size())));
				return null;
			}).when(peer).sendGetBlockBodies(any(), anyLong());
		}

		void holdResponses(RemotePeer peer) {
			doAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<Hash> hashes = List.copyOf((List<Hash>) invocation.getArgument(0));
				requests.add(new BodyRequest(peer, hashes));
				return null;
			}).when(peer).sendGetBlockBodies(any(), anyLong());
		}

		List<Hash> hashesForRange(int rangeIndex) {
			int from = rangeIndex * rangeSize;
			int to = Math.min(from + rangeSize, headers.size());
			return headers.subList(from, to).stream().map(BlockHeader::getHash).toList();
		}
	}
}
