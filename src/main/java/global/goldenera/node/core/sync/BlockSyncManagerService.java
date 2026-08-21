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

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_SCHEDULER;
import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedHeader;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PBlockReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PHeadersReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PPeerHeadAdvancedEvent;
import global.goldenera.node.core.p2p.events.P2PHandshakeCompletedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.netty.P2PChannelInitializer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.exceptions.IncompatibleChainException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE)
public class BlockSyncManagerService {

	// Sync configuration
	static final int SYNC_CHUNK_SIZE_HEADERS = 1000; // Headers per sync batch
	static final long TIMEOUT_SECONDS = 20; // Timeout per request (reduced for faster failover)
	static final long SYNC_POLL_DELAY_MS = 100;
	static final long MAX_IN_FLIGHT_BODY_BYTES = 2L * P2PChannelInitializer.MAX_FRAME_SIZE;
	static final long MAX_PERSIST_BATCH_BYTES = 128L * 1024 * 1024;
	static final int EMPTY_HEADER_INCOMPATIBILITY_THRESHOLD = 3;
	static final int MAX_PENDING_BROADCAST_DOWNLOADS = 128;
	static final long MAX_BROADCAST_REORG_DEPTH = 10;

	/**
	 * Calculate max bodies per request based on frame size and max block size.
	 * Uses base maxBlockSizeInBytes (not height-dependent overrides) since we need
	 * a conservative estimate that works for any block height during sync.
	 * Leaves ~15% headroom for RLP encoding overhead and envelope framing.
	 */
	static int calculateBodyBatchSize() {
		long maxFrameSize = P2PChannelInitializer.MAX_FRAME_SIZE;
		long maxBlockSize = Constants.getSettings().maxBlockSizeInBytes();
		// Reserve 15% for overhead, minimum 1 block per batch
		int batchSize = (int) ((maxFrameSize * 0.85) / maxBlockSize);
		return Math.max(1, batchSize);
	}

	/**
	 * Calculate optimal pipeline depth based on batch sizes.
	 * More blocks per batch = fewer requests = can afford deeper pipeline.
	 * Range: 3-8 concurrent requests.
	 */
	static int calculatePipelineDepth(int bodyBatchSize) {
		// Base: 3, scale up if we're sending fewer requests
		// With batchSize=6: depth=5, with batchSize=3: depth=4
		int depth = 3 + (bodyBatchSize / 2);
		return Math.min(8, Math.max(3, depth));
	}

	/**
	 * Persistence batches are bounded by both this count and
	 * MAX_PERSIST_BATCH_BYTES. A single valid block larger than the byte target is
	 * still persisted alone so synchronization always makes progress.
	 */
	static final int PERSIST_BATCH_SIZE = 250;

	final MeterRegistry registry;
	final ReentrantLock masterChainLock;
	final Executor coreTaskExecutor;

	final MiningService miningService;
	final IdentityService identityService;

	final BlockValidator blockValidationService;

	final ChainQuery chainQueryService;
	final BlockReorgs blockReorgService;
	final PeerRegistry peerRegistry;
	final PeerReputationService peerReputationService;
	final BlockIngestionService blockIngestionService;

	public BlockSyncManagerService(
			MeterRegistry registry,
			@Qualifier("masterChainLock") ReentrantLock masterChainLock,
			@Qualifier(CORE_SCHEDULER) Executor coreTaskExecutor,
			MiningService miningService,
			IdentityService identityService,
			BlockValidator blockValidationService,
			ChainQuery chainQueryService,
			BlockReorgs blockReorgService,
			PeerRegistry peerRegistry,
			PeerReputationService peerReputationService,
			BlockIngestionService blockIngestionService) {
		this.registry = registry;
		this.masterChainLock = masterChainLock;
		this.coreTaskExecutor = coreTaskExecutor;
		this.miningService = miningService;
		this.identityService = identityService;
		this.blockValidationService = blockValidationService;
		this.chainQueryService = chainQueryService;
		this.blockReorgService = blockReorgService;
		this.peerRegistry = peerRegistry;
		this.peerReputationService = peerReputationService;
		this.blockIngestionService = blockIngestionService;
	}

	final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Sync-Manager"));
	final AtomicBoolean isRunning = new AtomicBoolean(false);
	private final AtomicBoolean activeSyncCycle = new AtomicBoolean(false);

	@Getter
	volatile boolean synced = false;

	// Wake-ups carry no data, so coalesce them instead of allowing remote broadcasts
	// to grow an unbounded queue.
	final BlockingQueue<Object> signalQueue = new ArrayBlockingQueue<>(1);

	final Map<PeerRequestKey, CompletableFuture<List<BlockHeader>>> pendingHeaderRequests = new ConcurrentHashMap<>();
	final Map<PeerRequestKey, CompletableFuture<List<List<Tx>>>> pendingBodyRequests = new ConcurrentHashMap<>();

	final Set<Hash> pendingBroadcastDownloads = ConcurrentHashMap.newKeySet();
	private final SyncRequestTelemetry syncRequestTelemetry = new SyncRequestTelemetry();
	private final EmptyHeaderClaimTracker emptyHeaderClaimTracker = new EmptyHeaderClaimTracker();
	private final BodyPipelineTelemetry bodyPipelineTelemetry = new BodyPipelineTelemetry();
	private volatile long currentPersistBatchBytes;
	private volatile long peakPersistBatchBytes;

	public SyncRuntimeSnapshot runtimeSnapshot() {
		SyncRequestTelemetry.Snapshot requestTelemetry = syncRequestTelemetry.snapshot();
		BodyPipelineTelemetry.Snapshot bodyTelemetry = bodyPipelineTelemetry.snapshot();
		return new SyncRuntimeSnapshot(
				synced,
				activeSyncCycle.get(),
				chainQueryService.getLatestBlockHeight().orElse(-1L),
				pendingHeaderRequests.size(),
				pendingBodyRequests.size(),
				pendingBroadcastDownloads.size(),
				requestTelemetry.headerRequestsIssued(),
				requestTelemetry.bodyRequestsIssued(),
				requestTelemetry.firstHeaderRequestSequence(),
				requestTelemetry.firstBodyRequestSequence(),
				SYNC_CHUNK_SIZE_HEADERS,
				calculateBodyBatchSize(),
				calculatePipelineDepth(calculateBodyBatchSize()),
				PERSIST_BATCH_SIZE,
				MAX_IN_FLIGHT_BODY_BYTES,
				bodyTelemetry.reservedBytes(),
				bodyTelemetry.peakReservedBytes(),
				bodyTelemetry.activeRequests(),
				bodyTelemetry.peakActiveRequests(),
				bodyTelemetry.activePeers(),
				bodyTelemetry.peakActivePeers(),
				MAX_PERSIST_BATCH_BYTES,
				currentPersistBatchBytes,
				peakPersistBatchBytes);
	}

	public record SyncRuntimeSnapshot(
			boolean synced,
			boolean activeCycle,
			long localHeight,
			int pendingHeaderRequests,
			int pendingBodyRequests,
			int pendingBroadcastDownloads,
			long headerRequestsIssued,
			long bodyRequestsIssued,
			long firstHeaderRequestSequence,
			long firstBodyRequestSequence,
			int headerBatchLimit,
			int bodyBatchLimit,
			int pipelineDepthLimit,
			int persistenceBatchLimit,
			long bodyInflightByteLimit,
			long bodyInflightReservedBytes,
			long bodyInflightPeakReservedBytes,
			int activeBodyRequests,
			int peakActiveBodyRequests,
			int activeBodyPeers,
			int peakActiveBodyPeers,
			long persistenceBatchByteLimit,
			long persistenceBatchCurrentBytes,
			long persistenceBatchPeakBytes) {
	}

	public void start() {
		if (isRunning.getAndSet(true))
			return;
		log.info("Sync Manager started");
		syncExecutor.submit(this::syncLoop);
		signalQueue.offer(new Object());
		registry.gauge("blockchain.sync.status", this, svc -> svc.isSynced() ? 1 : 0);
		registry.gauge("blockchain.sync.body_inflight.bytes", this,
				svc -> svc.bodyPipelineTelemetry.snapshot().reservedBytes());
		registry.gauge("blockchain.sync.body_inflight.peak_bytes", this,
				svc -> svc.bodyPipelineTelemetry.snapshot().peakReservedBytes());
		registry.gauge("blockchain.sync.body_inflight.active_requests", this,
				svc -> svc.bodyPipelineTelemetry.snapshot().activeRequests());
		registry.gauge("blockchain.sync.body_inflight.active_peers", this,
				svc -> svc.bodyPipelineTelemetry.snapshot().activePeers());
		registry.gauge("blockchain.sync.persistence_batch.bytes", this,
				svc -> svc.currentPersistBatchBytes);
		registry.gauge("blockchain.sync.persistence_batch.peak_bytes", this,
				svc -> svc.peakPersistBatchBytes);
	}

	/** Wakes the sync loop immediately instead of waiting for its periodic poll. */
	public boolean requestSync() {
		return isRunning.get() && signalQueue.offer(new Object());
	}

	@PreDestroy
	public void stop() {
		if (!isRunning.getAndSet(false))
			return;
		log.info("Sync Manager stopped");
		syncExecutor.shutdownNow();
	}

	private void syncLoop() {
		while (isRunning.get()) {
			try {
				// Use short poll during active sync, longer when synced
				long pollDelay = synced ? 5000 : SYNC_POLL_DELAY_MS;
				signalQueue.poll(pollDelay, TimeUnit.MILLISECONDS);
				checkAndSync();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.error("Error in Sync Loop", e);
			}
		}
	}

	private void checkAndSync() {
		try {
			StoredBlock localBestStored = chainQueryService.getLatestStoredBlockOrThrow();
			BigInteger localTotalDifficulty = localBestStored.getCumulativeDifficulty();
			Optional<RemotePeer> bestPeerOpt = peerRegistry.getSyncCandidate(localTotalDifficulty);
			if (bestPeerOpt.isEmpty()) {
				if (!synced) {
					log.info("Node synced at height {}", localBestStored.getHeight());
					synced = true;
				}
				miningService.resumeMining();
				return;
			}
			RemotePeer bestPeer = bestPeerOpt.get();
			BigInteger advertisedTotalDifficulty = bestPeer.getTotalDifficulty();
			// Peer status is mutable. Re-check the advertised work after selection; the
			// authoritative check is repeated against actual downloaded headers under the
			// master-chain lock by ChainSwitchService.
			if (advertisedTotalDifficulty != null
					&& advertisedTotalDifficulty.compareTo(localTotalDifficulty) > 0) {
				log.info("Sync needed: local height {} (TD {}) vs peer height {} (TD {}) ({})",
						localBestStored.getHeight(), localTotalDifficulty,
						bestPeer.getHeadHeight(), advertisedTotalDifficulty, bestPeer.getIdentity());
				synced = false;

				boolean success = performSync(bestPeer, localBestStored, advertisedTotalDifficulty);

				if (success) {
					signalQueue.offer(new Object());
				}
			} else {
				if (!synced) {
					log.info("Node synced at height {}", localBestStored.getHeight());
					synced = true;
				}
				miningService.resumeMining();
			}
		} catch (Exception e) {
			log.error("Critical error in checkAndSync", e);
		}
	}

	private boolean performSync(RemotePeer peer, StoredBlock localBestStored,
			BigInteger advertisedTotalDifficulty) {
		Timer.Sample sample = Timer.start(registry);
		activeSyncCycle.set(true);
		boolean cycleSucceeded = false;
		try {
			log.debug("Starting sync with peer {}", peer.getIdentity());
			Block localBest = localBestStored.getBlock();

			long headerStart = System.currentTimeMillis();
			List<BlockHeader> headersToSync;
			try {
				headersToSync = downloadHeaders(peer, localBest);
			} finally {
				recordStageDuration("header_download", headerStart);
			}
			long headerTime = System.currentTimeMillis() - headerStart;

			if (headersToSync.isEmpty()) {
				if (advertisedTotalDifficulty.compareTo(localBestStored.getCumulativeDifficulty()) > 0) {
					if (!emptyHeaderClaimTracker.record(peer.getIdentity(), localBest.getHash())) {
						log.debug("Peer {} returned no headers for advertised height {}; retrying before "
								+ "classifying the chain as incompatible", peer.getIdentity(), peer.getHeadHeight());
						return false;
					}
					throw new IncompatibleChainException("Peer claimed height " + peer.getHeadHeight()
							+ " (local: " + localBest.getHeight()
							+ ") but repeatedly sent no headers for the same local head.");
				}

				emptyHeaderClaimTracker.clear(peer.getIdentity());
				log.debug("No new headers found from peer");
				cycleSucceeded = true;
				return true;
			}
			emptyHeaderClaimTracker.clear(peer.getIdentity());

			log.info("Downloaded {} headers in {}ms", headersToSync.size(), headerTime);

			// Validate headers in parallel AND warm up lazy getters (hash, size)
			// BlockHeaderImpl caches these values internally after first call
			long validateStart = System.currentTimeMillis();
			Map<Hash, StatelessValidatedHeader> validatedHeaders;
			try {
				validatedHeaders = validateBatch(headersToSync);
			} finally {
				recordStageDuration("header_validation", validateStart);
			}
			long validateTime = System.currentTimeMillis() - validateStart;
			log.info("Validated {} headers in {}ms", headersToSync.size(), validateTime);

			// Download bodies and persist in batches to limit RAM usage
			miningService.pauseMining();
			long bodyStart = System.currentTimeMillis();
			int totalBlocksProcessed = downloadAndPersistBodiesInBatches(
					peer, headersToSync, validatedHeaders);
			long bodyTime = System.currentTimeMillis() - bodyStart;

			log.info("Sync completed: {} blocks downloaded and persisted in {}ms (headers: {}ms, validation: {}ms)",
					totalBlocksProcessed, bodyTime, headerTime, validateTime);

			peerReputationService.recordSuccess(peer.getIdentity());
			registry.counter("blockchain.sync.blocks_downloaded").increment(totalBlocksProcessed);
			cycleSucceeded = true;
			return true;
		} catch (IncompatibleChainException e) {
			// Peer is on a fundamentally different chain (different genesis or hard fork)
			// Ban them permanently as they'll never be useful to us
			log.warn("INCOMPATIBLE CHAIN: Banning peer {} - {}", peer.getIdentity(), e.getMessage());
			peer.disconnect("Incompatible chain: " + e.getMessage());
			peerReputationService.ban(peer.getIdentity());
			return false;
		} catch (BodyRangeDownloadException e) {
			// Individual body peers were already attributed and penalized at the point of
			// failure. Do not blame the peer that supplied the header chain merely because
			// no remaining peer could serve one range.
			log.warn("Body sync failed without an eligible failover: {}", e.getMessage());
			return false;
		} catch (Exception e) {
			String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			log.warn("Sync failed with peer {}: {}", peer.getIdentity(), errorMsg);
			peer.disconnect("Sync failed: " + errorMsg);
			peerReputationService.recordFailure(peer.getIdentity());
			return false;
		} finally {
			activeSyncCycle.set(false);
			sample.stop(registry.timer("blockchain.sync.batch_time"));
			registry.counter("blockchain.sync.cycles", "outcome", cycleSucceeded ? "success" : "failure")
					.increment();
		}
	}

	private void recordStageDuration(String stage, long startMillis) {
		registry.timer("blockchain.sync.stage", "stage", stage)
				.record(System.currentTimeMillis() - startMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Downloads block bodies and persists them in batches of PERSIST_BATCH_SIZE.
	 * This limits RAM usage while maintaining efficient network and disk I/O.
	 * 
	 * @return Total number of blocks processed
	 */
	int downloadAndPersistBodiesInBatches(RemotePeer peer, List<BlockHeader> headers,
			Map<Hash, StatelessValidatedHeader> validatedHeaders) throws Exception {
		bodyPipelineTelemetry.begin();
		currentPersistBatchBytes = 0;
		peakPersistBatchBytes = 0;
		try {
			return doDownloadAndPersistBodiesInBatches(headers, validatedHeaders);
		} finally {
			bodyPipelineTelemetry.end();
			currentPersistBatchBytes = 0;
		}
	}

	private int doDownloadAndPersistBodiesInBatches(List<BlockHeader> headers,
			Map<Hash, StatelessValidatedHeader> validatedHeaders) throws Exception {
		if (headers.isEmpty())
			return 0;
		for (BlockHeader header : headers) {
			if (!validatedHeaders.containsKey(header.getHash())) {
				throw new GEFailedException(
						"Missing stateless header proof at height " + header.getHeight());
			}
		}

		int totalProcessed = 0;
		List<ValidatedSyncBlock> currentBatch = new ArrayList<>(PERSIST_BATCH_SIZE);
		long currentBatchBytes = 0;

		Hash firstParentHash = headers.get(0).getPreviousHash();
		StoredBlock commonAncestor = chainQueryService.getStoredBlockByHashOrThrow(firstParentHash);
		BigInteger currentCumulativeDifficulty = commonAncestor.getCumulativeDifficulty();

		// Pipeline configuration
		final int bodyBatchSize = calculateBodyBatchSize();
		final int pipelineDepth = calculatePipelineDepth(bodyBatchSize);
		final long maxBlockSize = Constants.getSettings().maxBlockSizeInBytes();
		BodyInflightBudget inflightBudget = new BodyInflightBudget(MAX_IN_FLIGHT_BODY_BYTES);
		List<PendingBodyRequest> pendingRequests = new ArrayList<>();
		Set<RemotePeer> failedBodyPeers = new HashSet<>();
		Set<RemotePeer> successfulBodyPeers = new HashSet<>();
		int nextBatchIndex = 0;
		int nextExpectedBodyIndex = 0;

		while (nextBatchIndex < headers.size() || !pendingRequests.isEmpty()) {
			// Keep a rolling pipeline, bounded by both request count and conservatively
			// reserved response bytes. Header chunks are themselves bounded, so this list
			// can never grow without limit.
			while (pendingRequests.size() < pipelineDepth && nextBatchIndex < headers.size()) {
				int startIdx = nextBatchIndex;
				int endIdx = Math.min(nextBatchIndex + bodyBatchSize, headers.size());
				BodyRange range = BodyRange.create(
						startIdx / bodyBatchSize, startIdx, headers.subList(startIdx, endIdx), maxBlockSize);
				if (!inflightBudget.tryReserve(range.reservedBytes())) {
					break;
				}

				PendingBodyRequest request;
				try {
					request = issueBodyRangeRequest(range, failedBodyPeers);
				} catch (Exception e) {
					inflightBudget.release(range.reservedBytes());
					cleanupPendingBodyRequests(pendingRequests);
					throw e;
				}
				pendingRequests.add(request);
				bodyPipelineTelemetry.requestIssued(request.peer(), range.reservedBytes());
				nextBatchIndex = endIdx;
			}

			if (pendingRequests.isEmpty()) {
				throw new BodyRangeDownloadException(
						"Body range cannot fit the configured in-flight byte budget");
			}

			// Wait for the oldest request in the pipeline
			PendingBodyRequest oldest = pendingRequests.remove(0);
			try {
				List<List<Tx>> bodies;
				List<StatelessValidatedBlock> validatedBodies;
				long bodyAttemptStart = System.currentTimeMillis();
				try {
					bodies = oldest.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
					validatedBodies = validateBodyResponse(oldest, bodies, validatedHeaders);
				} catch (LocalSyncInvariantException localInvariant) {
					pendingBodyRequests.remove(oldest.requestKey());
					inflightBudget.release(oldest.range().reservedBytes());
					bodyPipelineTelemetry.requestCompleted(oldest.peer(), oldest.range().reservedBytes());
					throw localInvariant;
				} catch (Exception peerFailure) {
					pendingBodyRequests.remove(oldest.requestKey());
					failedBodyPeers.add(oldest.peer());
					penalizeBodyPeer(oldest.peer(), peerFailure);
					inflightBudget.release(oldest.range().reservedBytes());
					bodyPipelineTelemetry.requestCompleted(oldest.peer(), oldest.range().reservedBytes());
					registry.counter("blockchain.sync.body_range.retries").increment();

					if (!inflightBudget.tryReserve(oldest.range().reservedBytes())) {
						throw new BodyRangeDownloadException("Unable to reserve retry byte budget", peerFailure);
					}
					try {
						PendingBodyRequest retry = issueBodyRangeRequest(oldest.range(), failedBodyPeers);
						pendingRequests.add(0, retry);
						bodyPipelineTelemetry.requestIssued(retry.peer(), retry.range().reservedBytes());
						registry.counter("blockchain.sync.body_range.failovers").increment();
						continue;
					} catch (Exception noFailover) {
						inflightBudget.release(oldest.range().reservedBytes());
						throw new BodyRangeDownloadException(
								"No untried peer can serve body range " + oldest.range().rangeIndex(), noFailover);
					}
				} finally {
					recordStageDuration("body_download_validation", bodyAttemptStart);
				}

				// The response has now been consumed and transformed into validated blocks;
				// release its conservative network-buffer reservation before rolling onward.
				inflightBudget.release(oldest.range().reservedBytes());
				bodyPipelineTelemetry.requestCompleted(oldest.peer(), oldest.range().reservedBytes());
				if (successfulBodyPeers.add(oldest.peer()) && oldest.peer().getIdentity() != null) {
					peerReputationService.recordSuccess(oldest.peer().getIdentity());
					registry.counter("blockchain.sync.body_serving_peers.successful").increment();
				}
				long acceptedBodyBytes = validatedBodies.stream()
						.map(StatelessValidatedBlock::block)
						.mapToLong(Block::getSize)
						.sum();
				registry.counter("blockchain.sync.body.blocks").increment(validatedBodies.size());
				registry.counter("blockchain.sync.body.bytes").increment(acceptedBodyBytes);
				if (oldest.range().startIndex() != nextExpectedBodyIndex) {
					throw new GEValidationException("Body ranges completed out of order: expected index "
							+ nextExpectedBodyIndex + ", got " + oldest.range().startIndex());
				}
				for (int j = 0; j < bodies.size(); j++) {
					BlockHeader header = oldest.range().headers().get(j);
					Long height = header.getHeight();
					StatelessValidatedBlock validatedBlock = validatedBodies.get(j);
					Block block = validatedBlock.block();

					currentCumulativeDifficulty = currentCumulativeDifficulty.add(header.getDifficulty());

					Address minerIdentity = block.getHeader().getIdentity();
					if (minerIdentity == null) {
						throw new GEValidationException("Critical: Header identity is null for block " + height);
					}
					Address peerIdentity = oldest.peer().getIdentity();
					if (peerIdentity == null) {
						throw new GEValidationException("Critical: Peer identity is null during sync");
					}

					StoredBlock storedBlock = StoredBlock.builder()
							.block(block)
							.cumulativeDifficulty(currentCumulativeDifficulty)
							.identity(minerIdentity)
							.receivedAt(block.getHeader().getTimestamp())
							.receivedFrom(peerIdentity)
							.connectedSource(ConnectedSource.SYNC)
							.computeIndexes()
							.build();

					long storedBlockBytes = Math.max(1L, storedBlock.getBlockSize());
					if (!currentBatch.isEmpty()
							&& storedBlockBytes > MAX_PERSIST_BATCH_BYTES - currentBatchBytes) {
						persistBatch(commonAncestor, currentBatch);
						totalProcessed += currentBatch.size();
						commonAncestor = currentBatch.get(currentBatch.size() - 1).storedBlock();
						currentBatch.clear();
						currentBatchBytes = 0;
						currentPersistBatchBytes = 0;
					}
					currentBatch.add(new ValidatedSyncBlock(storedBlock, validatedBlock));
					try {
						currentBatchBytes = Math.addExact(currentBatchBytes, storedBlockBytes);
					} catch (ArithmeticException e) {
						throw new GEFailedException("Persistence batch byte size overflow", e);
					}
					peakPersistBatchBytes = Math.max(peakPersistBatchBytes, currentBatchBytes);
					currentPersistBatchBytes = currentBatchBytes;

					// A single valid block may exceed the target and is persisted by itself. The
					// threshold is checked after insertion so progress is always possible.
					if (shouldFlushPersistenceBatch(currentBatch.size(), currentBatchBytes)) {
						persistBatch(commonAncestor, currentBatch);
						totalProcessed += currentBatch.size();
						// Update ancestor for next batch
						commonAncestor = currentBatch.get(currentBatch.size() - 1).storedBlock();
						currentBatch.clear();
						currentBatchBytes = 0;
						currentPersistBatchBytes = 0;
					}
				}
				nextExpectedBodyIndex += bodies.size();

			} catch (Exception e) {
				pendingBodyRequests.remove(oldest.requestKey());
				cleanupPendingBodyRequests(pendingRequests);
				throw e;
			}
		}

		// Persist remaining blocks
		if (!currentBatch.isEmpty()) {
			persistBatch(commonAncestor, currentBatch);
			totalProcessed += currentBatch.size();
		}

		return totalProcessed;
	}

	static boolean shouldFlushPersistenceBatch(int blockCount, long serializedBlockBytes) {
		return blockCount >= PERSIST_BATCH_SIZE || serializedBlockBytes >= MAX_PERSIST_BATCH_BYTES;
	}

	private PendingBodyRequest issueBodyRangeRequest(BodyRange range, Set<RemotePeer> failedBodyPeers)
			throws BodyRangeDownloadException {
		List<RemotePeer> eligiblePeers = peerRegistry.getBodySyncPeers(range.endHeight());
		if (eligiblePeers.isEmpty()) {
			throw new BodyRangeDownloadException(
					"No handshaken peer advertises height " + range.endHeight());
		}

		int startOffset = Math.floorMod(range.rangeIndex(), eligiblePeers.size());
		RemotePeer selected = null;
		for (int offset = 0; offset < eligiblePeers.size(); offset++) {
			RemotePeer candidate = eligiblePeers.get((startOffset + offset) % eligiblePeers.size());
			if (!failedBodyPeers.contains(candidate) && !range.attemptedPeers().contains(candidate)) {
				selected = candidate;
				break;
			}
		}
		if (selected == null) {
			throw new BodyRangeDownloadException(
					"All eligible peers already failed body range " + range.rangeIndex());
		}

		range.attemptedPeers().add(selected);
		CompletableFuture<List<List<Tx>>> future = new CompletableFuture<>();
		long requestId = selected.reserveRequestId();
		PeerRequestKey requestKey = new PeerRequestKey(selected, requestId);
		registerBodyRequest(requestKey, future);
		recordBodyRequest();
		try {
			selected.sendGetBlockBodies(range.expectedHashes(), requestId);
		} catch (RuntimeException sendFailure) {
			pendingBodyRequests.remove(requestKey);
			throw new BodyRangeDownloadException(
					"Failed to send body range " + range.rangeIndex(), sendFailure);
		}
		return new PendingBodyRequest(requestKey, range, selected, future);
	}

	private List<StatelessValidatedBlock> validateBodyResponse(PendingBodyRequest request, List<List<Tx>> bodies,
			Map<Hash, StatelessValidatedHeader> validatedHeaders) {
		BodyRange range = request.range();
		if (bodies == null || bodies.isEmpty()) {
			throw new GEValidationException("Empty body response from peer");
		}
		if (bodies.size() != range.headers().size()) {
			throw new GEValidationException("Mismatch body count from peer: got " + bodies.size()
					+ ", expected " + range.headers().size());
		}

		List<StatelessValidatedBlock> validatedBodies = new ArrayList<>(bodies.size());
		for (int index = 0; index < bodies.size(); index++) {
			BlockHeader header = range.headers().get(index);
			Hash expectedHash = range.expectedHashes().get(index);
			if (!expectedHash.equals(header.getHash())) {
				throw new LocalSyncInvariantException("Body range header changed at index " + index);
			}
			StatelessValidatedHeader validatedHeader = Optional.ofNullable(validatedHeaders.get(expectedHash))
					.orElseThrow(() -> new LocalSyncInvariantException(
							"Missing stateless header proof at height " + header.getHeight()));
			Block candidate = BlockImpl.builder().header(header).txs(bodies.get(index)).build();
			// v1 body responses are positional and carry no block hash. Binding each body
			// to the exact requested header and running the shared Merkle/body gate is the
			// wire-compatible equivalent of validating the response hash.
			validatedBodies.add(blockValidationService.validateBlockBody(candidate, validatedHeader));
		}
		return List.copyOf(validatedBodies);
	}

	private void penalizeBodyPeer(RemotePeer peer, Exception failure) {
		Address identity = peer.getIdentity();
		String message = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
		log.warn("Rejecting body range response from {}: {}", identity, message);
		if (identity != null) {
			peerReputationService.recordFailure(identity);
		}
		peer.disconnect("Invalid sync body range: " + message);
	}

	private void cleanupPendingBodyRequests(List<PendingBodyRequest> pendingRequests) {
		for (PendingBodyRequest request : pendingRequests) {
			pendingBodyRequests.remove(request.requestKey());
		}
	}

	/**
	 * Persists a batch of blocks atomically.
	 */
	private void persistBatch(StoredBlock commonAncestor, List<ValidatedSyncBlock> blocks) throws Exception {
		if (blocks.isEmpty())
			return;

		masterChainLock.lock();
		long batchBytes = blocks.stream()
				.map(ValidatedSyncBlock::storedBlock)
				.mapToLong(block -> Math.max(1L, block.getBlockSize()))
				.sum();
		long stageStart = System.currentTimeMillis();
		try {
			long start = System.currentTimeMillis();
			blockReorgService.executeAtomicReorgSwap(new ValidatedSyncBatch(commonAncestor, blocks));
			long elapsed = System.currentTimeMillis() - start;
			log.info("Persisted batch of {} blocks (heights {}-{}) in {}ms",
					blocks.size(),
					blocks.get(0).storedBlock().getHeight(),
					blocks.get(blocks.size() - 1).storedBlock().getHeight(),
					elapsed);
			registry.counter("blockchain.sync.persistence.batches").increment();
			registry.counter("blockchain.sync.persistence.blocks").increment(blocks.size());
			registry.counter("blockchain.sync.persistence.bytes").increment(batchBytes);
		} finally {
			recordStageDuration("state_execution_db_commit", stageStart);
			masterChainLock.unlock();
		}
	}

	private List<BlockHeader> downloadHeaders(RemotePeer peer, Block localBest) throws Exception {
		List<BlockHeader> allHeaders = new ArrayList<>();
		Hash stopHash = peer.getHeadHash();
		long locatorStart = System.currentTimeMillis();
		List<Hash> currentLocators = new ArrayList<>(chainQueryService.getLocatorHashes());
		long locatorTime = System.currentTimeMillis() - locatorStart;
		if (locatorTime > 100) {
			log.warn("SLOW getLocatorHashes: {}ms for {} locators at height {}",
					locatorTime, currentLocators.size(), localBest.getHeight());
		}

		// Cache for the last header hash across batches (to avoid recalculating)
		Hash lastCachedHash = null;

		while (allHeaders.size() < SYNC_CHUNK_SIZE_HEADERS) {
			CompletableFuture<List<BlockHeader>> future = new CompletableFuture<>();
			long reqId = peer.reserveRequestId();
			PeerRequestKey requestKey = new PeerRequestKey(peer, reqId);
			registerHeaderRequest(requestKey, future);
			recordHeaderRequest();
			int remaining = SYNC_CHUNK_SIZE_HEADERS - allHeaders.size();

			long sendStart = System.currentTimeMillis();
			try {
				// Keep registration and send in the same cleanup scope. A synchronous
				// channel failure must not leave a request that can never complete.
				peer.sendGetBlockHeaders(currentLocators, stopHash, remaining, reqId);
				log.debug("Sent GetHeaders request {} for {} headers from height {}", reqId, remaining,
						localBest.getHeight());
				List<BlockHeader> batch = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				long waitTime = System.currentTimeMillis() - sendStart;
				if (waitTime > 2000) {
					log.warn("SLOW header response: {}ms for {} headers (reqId: {})",
							waitTime, batch != null ? batch.size() : 0, reqId);
				}

				if (batch == null || batch.isEmpty())
					break;

				// Cache previous hash to avoid repeated getHash() calls
				Hash expectedPrev = (allHeaders.isEmpty()) ? null
						: lastCachedHash; // Use cached hash from previous iteration

				// Validate first header connects to something we have
				if (allHeaders.isEmpty() && !batch.isEmpty()) {
					Hash firstParent = batch.get(0).getPreviousHash();
					if (!chainQueryService.hasBlockData(firstParent)) {
						// Check if this is an incompatible chain (different genesis/hard fork)
						// If we have a genesis (height >= 0) but don't have their parent,
						// they must be on a completely different chain
						long localHeight = chainQueryService.getLatestBlockHeight().orElse(-1L);
						if (localHeight >= 0) {
							// We have a genesis, but their chain doesn't connect to ours
							// This is a fundamentally incompatible chain - ban them
							throw new IncompatibleChainException(
									"Peer chain does not connect to our chain. Their header at height "
											+ batch.get(0).getHeight() + " has parent " + firstParent
											+ " which is not in our chain (local height: " + localHeight + ")");
						}
						throw new GEValidationException("Peer sent header at height " + batch.get(0).getHeight()
								+ " whose parent " + firstParent + " is missing from our DB");
					}
				}

				// Validate linkage and compute hashes once per header
				Hash currentHash = null;
				for (BlockHeader h : batch) {
					if (expectedPrev != null && !h.getPreviousHash().equals(expectedPrev)) {
						throw new GEValidationException("Broken header linkage");
					}
					currentHash = h.getHash(); // Compute once, cache for next iteration
					expectedPrev = currentHash;
				}

				allHeaders.addAll(batch);
				lastCachedHash = currentHash; // Save for next batch's expectedPrev

				if (lastCachedHash.equals(stopHash) || batch.size() < remaining)
					break;

				currentLocators.clear();
				currentLocators.add(lastCachedHash); // Use cached hash

			} catch (Exception e) {
				pendingHeaderRequests.remove(requestKey);
				throw e;
			}
		}

		return allHeaders;
	}

	private void recordHeaderRequest() {
		syncRequestTelemetry.recordHeaderRequest();
	}

	private void recordBodyRequest() {
		syncRequestTelemetry.recordBodyRequest();
	}

	void registerHeaderRequest(PeerRequestKey requestKey, CompletableFuture<List<BlockHeader>> future) {
		pendingHeaderRequests.put(requestKey, future);
	}

	void registerBodyRequest(PeerRequestKey requestKey, CompletableFuture<List<List<Tx>>> future) {
		pendingBodyRequests.put(requestKey, future);
	}

	static final class SyncRequestTelemetry {
		private final AtomicLong sequence = new AtomicLong();
		private final AtomicLong headerRequestsIssued = new AtomicLong();
		private final AtomicLong bodyRequestsIssued = new AtomicLong();
		private final AtomicLong firstHeaderRequestSequence = new AtomicLong();
		private final AtomicLong firstBodyRequestSequence = new AtomicLong();

		synchronized void recordHeaderRequest() {
			long requestSequence = sequence.incrementAndGet();
			headerRequestsIssued.incrementAndGet();
			firstHeaderRequestSequence.compareAndSet(0, requestSequence);
		}

		synchronized void recordBodyRequest() {
			long requestSequence = sequence.incrementAndGet();
			bodyRequestsIssued.incrementAndGet();
			firstBodyRequestSequence.compareAndSet(0, requestSequence);
		}

		synchronized Snapshot snapshot() {
			return new Snapshot(
					headerRequestsIssued.get(),
					bodyRequestsIssued.get(),
					firstHeaderRequestSequence.get(),
					firstBodyRequestSequence.get());
		}

		record Snapshot(
				long headerRequestsIssued,
				long bodyRequestsIssued,
				long firstHeaderRequestSequence,
				long firstBodyRequestSequence) {
		}
	}

	static final class EmptyHeaderClaimTracker {
		private final Map<Address, EmptyHeaderObservation> observations = new ConcurrentHashMap<>();

		boolean record(Address identity, Hash localHeadHash) {
			if (identity == null) {
				return false;
			}
			AtomicBoolean thresholdReached = new AtomicBoolean();
			observations.compute(identity, (ignored, previous) -> {
				int count = previous != null && previous.localHeadHash().equals(localHeadHash)
						? previous.count() + 1
						: 1;
				thresholdReached.set(count >= EMPTY_HEADER_INCOMPATIBILITY_THRESHOLD);
				return new EmptyHeaderObservation(localHeadHash, count);
			});
			return thresholdReached.get();
		}

		void clear(Address identity) {
			if (identity != null) {
				observations.remove(identity);
			}
		}
	}

	private record EmptyHeaderObservation(Hash localHeadHash, int count) {
	}

	/**
	 * Validates headers in parallel AND warms up lazy getters (hash, size).
	 * BlockHeaderImpl caches these values internally after first call,
	 * so subsequent calls to getHash()/getSize() are O(1).
	 */
	private Map<Hash, StatelessValidatedHeader> validateBatch(List<BlockHeader> headers) {
		// Build contextMap - this also warms up getHash() for each header
		Map<Long, Hash> contextMap = new ConcurrentHashMap<>();

		// First populate the entire batch seed map. Building and consuming this map in
		// one parallel pass allowed seed availability to depend on scheduling order.
		headers.parallelStream().forEach(h -> {
			h.getHash(); // Warm up hash cache
			h.getSize(); // Warm up size cache
			h.getIdentity(); // Warm up identity cache
			contextMap.put(h.getHeight(), h.getHash()); // Now cached, O(1)
		});

		// Then validate PoW in parallel against the complete immutable-in-practice map.
		Map<Hash, StatelessValidatedHeader> validatedHeaders = new ConcurrentHashMap<>();
		headers.parallelStream().forEach(h -> {
			StatelessValidatedHeader validatedHeader = blockValidationService.validateHeader(h, contextMap);
			validatedHeaders.put(h.getHash(), validatedHeader);
		});
		return Map.copyOf(validatedHeaders);
	}

	static final class BodyInflightBudget {
		private final long limitBytes;
		private long reservedBytes;

		BodyInflightBudget(long limitBytes) {
			if (limitBytes <= 0) {
				throw new IllegalArgumentException("Body in-flight byte limit must be positive");
			}
			this.limitBytes = limitBytes;
		}

		synchronized boolean tryReserve(long bytes) {
			if (bytes <= 0 || bytes > limitBytes - reservedBytes) {
				return false;
			}
			reservedBytes += bytes;
			return true;
		}

		synchronized void release(long bytes) {
			if (bytes <= 0 || bytes > reservedBytes) {
				throw new IllegalStateException("Invalid body in-flight byte release");
			}
			reservedBytes -= bytes;
		}

		synchronized long reservedBytes() {
			return reservedBytes;
		}
	}

	static final class BodyPipelineTelemetry {
		private final Map<RemotePeer, Integer> activeRequestsByPeer = new HashMap<>();
		private long reservedBytes;
		private long peakReservedBytes;
		private int activeRequests;
		private int peakActiveRequests;
		private int peakActivePeers;

		synchronized void begin() {
			activeRequestsByPeer.clear();
			reservedBytes = 0;
			peakReservedBytes = 0;
			activeRequests = 0;
			peakActiveRequests = 0;
			peakActivePeers = 0;
		}

		synchronized void requestIssued(RemotePeer peer, long bytes) {
			reservedBytes = Math.addExact(reservedBytes, bytes);
			peakReservedBytes = Math.max(peakReservedBytes, reservedBytes);
			activeRequests++;
			peakActiveRequests = Math.max(peakActiveRequests, activeRequests);
			activeRequestsByPeer.merge(peer, 1, Integer::sum);
			peakActivePeers = Math.max(peakActivePeers, activeRequestsByPeer.size());
		}

		synchronized void requestCompleted(RemotePeer peer, long bytes) {
			if (bytes <= 0 || bytes > reservedBytes) {
				throw new IllegalStateException("Invalid body pipeline telemetry release");
			}
			reservedBytes -= bytes;
			activeRequests--;
			Integer requests = activeRequestsByPeer.get(peer);
			if (requests == null || requests <= 0) {
				throw new IllegalStateException("Body pipeline peer was not active");
			}
			if (requests == 1) {
				activeRequestsByPeer.remove(peer);
			} else {
				activeRequestsByPeer.put(peer, requests - 1);
			}
		}

		synchronized void end() {
			activeRequestsByPeer.clear();
			reservedBytes = 0;
			activeRequests = 0;
		}

		synchronized Snapshot snapshot() {
			return new Snapshot(reservedBytes, peakReservedBytes, activeRequests,
					peakActiveRequests, activeRequestsByPeer.size(), peakActivePeers);
		}

		record Snapshot(long reservedBytes, long peakReservedBytes, int activeRequests,
				int peakActiveRequests, int activePeers, int peakActivePeers) {
		}
	}

	private static final class BodyRange {
		private final int rangeIndex;
		private final int startIndex;
		private final List<BlockHeader> headers;
		private final List<Hash> expectedHashes;
		private final long reservedBytes;
		private final Set<RemotePeer> attemptedPeers = new HashSet<>();

		private BodyRange(int rangeIndex, int startIndex, List<BlockHeader> headers,
				List<Hash> expectedHashes, long reservedBytes) {
			this.rangeIndex = rangeIndex;
			this.startIndex = startIndex;
			this.headers = headers;
			this.expectedHashes = expectedHashes;
			this.reservedBytes = reservedBytes;
		}

		static BodyRange create(int rangeIndex, int startIndex, List<BlockHeader> headers, long maxBlockSize) {
			List<BlockHeader> immutableHeaders = List.copyOf(headers);
			List<Hash> hashes = immutableHeaders.stream().map(BlockHeader::getHash).toList();
			long reservation;
			try {
				reservation = Math.multiplyExact(maxBlockSize, immutableHeaders.size());
			} catch (ArithmeticException e) {
				throw new IllegalArgumentException("Body range reservation overflow", e);
			}
			return new BodyRange(rangeIndex, startIndex, immutableHeaders, hashes, reservation);
		}

		int rangeIndex() { return rangeIndex; }
		int startIndex() { return startIndex; }
		List<BlockHeader> headers() { return headers; }
		List<Hash> expectedHashes() { return expectedHashes; }
		long reservedBytes() { return reservedBytes; }
		long endHeight() { return headers.get(headers.size() - 1).getHeight(); }
		Set<RemotePeer> attemptedPeers() { return attemptedPeers; }
	}

	private record PendingBodyRequest(PeerRequestKey requestKey, BodyRange range,
			RemotePeer peer, CompletableFuture<List<List<Tx>>> future) {
	}

	private static class BodyRangeDownloadException extends Exception {
		BodyRangeDownloadException(String message) {
			super(message);
		}

		BodyRangeDownloadException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private static class LocalSyncInvariantException extends GEFailedException {
		LocalSyncInvariantException(String message) {
			super(message);
		}
	}

	record PeerRequestKey(RemotePeer peer, long requestId) {
		PeerRequestKey {
			if (peer == null) {
				throw new IllegalArgumentException("peer is required");
			}
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof PeerRequestKey that
					&& peer == that.peer && requestId == that.requestId;
		}

		@Override
		public int hashCode() {
			return 31 * System.identityHashCode(peer) + Long.hashCode(requestId);
		}
	}

	// --- EVENT LISTENERS ---

	@EventListener
	public void onHeadersReceived(P2PHeadersReceivedEvent event) {
		long reqId = event.getRequestId();
		PeerRequestKey requestKey = new PeerRequestKey(event.getPeer(), reqId);
		CompletableFuture<List<BlockHeader>> future = pendingHeaderRequests.remove(requestKey);

		if (future != null) {
			future.complete(event.getHeaders());
		} else if (reqId == 0 && event.getHeaders().size() == 1) {
			handleBroadcastHeader(event.getPeer(), event.getHeaders().get(0));
		} else {
			recordUnmatchedResponse("headers", reqId, event.getPeer(), pendingHeaderRequests);
			log.debug("Received headers with ID {} but no pending request found", reqId);
		}
	}

	/**
	 * Logic for HEADERS-FIRST propagation.
	 */
	private void handleBroadcastHeader(RemotePeer peer, BlockHeader header) {
		Hash headerHash = header.getHash();

		if (chainQueryService.getStoredBlockByHash(headerHash).isPresent()) {
			return;
		}
		if (blockIngestionService.isOrphan(headerHash)) {
			return;
		}
		try {
			StoredBlock localBestStored = chainQueryService.getLatestStoredBlockOrThrow();
			if (header.getHeight() <= 0
					|| header.getHeight() < localBestStored.getHeight() - MAX_BROADCAST_REORG_DEPTH) {
				log.debug("Ignoring old broadcast block #{} (local tip: {})",
						header.getHeight(), localBestStored.getHeight());
				return;
			}

			Optional<StoredBlock> parent = chainQueryService.getStoredBlockByHash(header.getPreviousHash());
			if (parent.isEmpty()) {
				log.debug("Ignoring broadcast block #{} - parent is not stored", header.getHeight());
				return;
			}
			if (!tryTrackBroadcastDownload(headerHash)) {
				log.debug("Ignoring duplicate or excess broadcast block #{}", header.getHeight());
				return;
			}

			// Run expensive PoW only after cheap age, parent, duplicate and global-cap
			// checks. Evidence is recorded only by the contextual validation below.
			StatelessValidatedHeader validatedHeader = blockValidationService.validateHeader(header);
			blockIngestionService.validateBroadcastHeaderContext(header, parent.orElseThrow().getBlock());

			// At this point: either block extends tip with valid parent,
			// or is a potential reorg block we should evaluate
			CompletableFuture<List<List<Tx>>> future = new CompletableFuture<>();

			long reqId = peer.reserveRequestId();
			PeerRequestKey requestKey = new PeerRequestKey(peer, reqId);
			registerBodyRequest(requestKey, future);

			log.debug("Headers-First: Requesting body for #{} from {}", header.getHeight(), peer.getIdentity());

			try {
				peer.sendGetBlockBodies(new ArrayList<>(List.of(headerHash)), reqId);
			} catch (RuntimeException sendFailure) {
				pendingBodyRequests.remove(requestKey);
				throw sendFailure;
			}

			future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.thenAcceptAsync(bodies -> {
						pendingBodyRequests.remove(requestKey);

						if (bodies == null || bodies.isEmpty()) {
							log.warn("Peer {} sent empty body response for #{}", peer.getIdentity(),
									header.getHeight());
							return;
						}

						List<Tx> txs = bodies.get(0);
						Block block = BlockImpl.builder()
								.header(header)
								.txs(txs)
								.build();
						StatelessValidatedBlock validatedBlock = blockValidationService
								.validateBlockBody(block, validatedHeader);

						BlockIngestionOutcome outcome = blockIngestionService.processValidatedBlock(
								validatedBlock,
								ConnectedSource.BROADCAST,
								peer.getIdentity(),
								Instant.now());
						if (isPeerFaultRejection(outcome)) {
							log.debug("Rejected broadcast body for block #{} with outcome {}",
									header.getHeight(), outcome.code());
							peerReputationService.recordFailure(peer.getIdentity());
						}

					}, coreTaskExecutor)
					.exceptionally(e -> {
						if (e instanceof TimeoutException) {
							log.warn("Timeout waiting for body of block #{} from {}", header.getHeight(),
									peer.getIdentity());
							peerReputationService.recordFailure(peer.getIdentity()); // Penalty for slowness
						} else {
							log.warn("Failed to download/process broadcast body for #{} - {}", header.getHeight(),
									e.getMessage());
						}
						pendingBodyRequests.remove(requestKey);
						return null;
					})
					.whenComplete((v, e) -> {
						pendingBroadcastDownloads.remove(headerHash);
					});
		} catch (GEValidationException e) {
			log.debug("Rejected broadcast header #{}: {}", header.getHeight(), e.getMessage());
			pendingBroadcastDownloads.remove(headerHash);
		} catch (Exception e) {
			log.error("Failed to handle broadcast header", e);
			pendingBroadcastDownloads.remove(headerHash);
		}
	}

	boolean tryTrackBroadcastDownload(Hash headerHash) {
		synchronized (pendingBroadcastDownloads) {
			return pendingBroadcastDownloads.size() < MAX_PENDING_BROADCAST_DOWNLOADS
					&& pendingBroadcastDownloads.add(headerHash);
		}
	}

	@EventListener
	public void onBodiesReceived(P2PBlockBodiesReceivedEvent event) {
		PeerRequestKey requestKey = new PeerRequestKey(event.getPeer(), event.getRequestId());
		CompletableFuture<List<List<Tx>>> future = pendingBodyRequests.remove(requestKey);
		if (future != null) {
			future.complete(event.getBodies());
		} else {
			recordUnmatchedResponse("bodies", event.getRequestId(), event.getPeer(), pendingBodyRequests);
			log.debug("Received bodies with ID {} but no pending request found (Timed out?)", event.getRequestId());
		}
	}

	private void recordUnmatchedResponse(String type, long requestId, RemotePeer responsePeer,
			Map<PeerRequestKey, ?> pendingRequests) {
		boolean belongsToAnotherPeer = pendingRequests.keySet().stream()
				.anyMatch(key -> key.requestId() == requestId && key.peer() != responsePeer);
		registry.counter("p2p.sync.responses.rejected", "type", type, "reason",
				belongsToAnotherPeer ? "wrong_peer" : "unsolicited").increment();
	}

	@EventListener
	public void onNewBlock(P2PBlockReceivedEvent event) {
		BlockIngestionOutcome outcome = blockIngestionService.processBlock(
				event.getBlock(), ConnectedSource.BROADCAST, event.getPeer().getIdentity(), Instant.now());

		if (outcome.code() == BlockIngestionOutcome.Code.GAP_DETECTED) {
			log.debug("Gap detected from broadcast, triggering sync");
			signalQueue.offer(new Object());
		} else if (isPeerFaultRejection(outcome)) {
			peerReputationService.recordFailure(event.getPeer().getIdentity());
		}
	}

	@EventListener
	public void onPeerHeadAdvanced(P2PPeerHeadAdvancedEvent event) {
		requestSync();
	}

	@EventListener
	public void onPeerHandshakeCompleted(P2PHandshakeCompletedEvent event) {
		requestSync();
	}

	private boolean isPeerFaultRejection(BlockIngestionOutcome outcome) {
		return switch (outcome.code()) {
			case REJECTED_STATELESS, REJECTED_CONTEXTUAL, REJECTED_CONSENSUS_POLICY,
					REJECTED_EXECUTION, REJECTED_STATE_ROOT -> true;
			case ACCEPTED, ORPHAN_BUFFERED, GAP_DETECTED, ALREADY_EXISTS, INTERNAL_FAILURE -> false;
		};
	}
}
