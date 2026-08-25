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

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_TASK_EXECUTOR;
import static global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol.INTERNAL_VALIDATION_WINDOW_HEADERS;
import static global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT;
import static global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol.MAX_LOCAL_HEADER_WINDOW;
import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
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
import global.goldenera.node.core.blockchain.validation.BlockValidator.PreparedHeaderValidation;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedHeader;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationContext;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationSession;
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
	static final int SYNC_CHUNK_SIZE_HEADERS = INTERNAL_VALIDATION_WINDOW_HEADERS;
	static final long TIMEOUT_SECONDS = 20; // Timeout per request (reduced for faster failover)
	static final long SYNC_POLL_DELAY_MS = 100;
	static final long HEADER_VALIDATION_DRAIN_TIMEOUT_SECONDS = 30;
	static final long MAX_IN_FLIGHT_BODY_BYTES = 2L * P2PChannelInitializer.MAX_FRAME_SIZE;
	static final long MAX_PERSIST_BATCH_BYTES = 128L * 1024 * 1024;
	static final int EMPTY_HEADER_INCOMPATIBILITY_THRESHOLD = 3;
	static final int MAX_PENDING_BROADCAST_DOWNLOADS = 8;
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
	final SyncVerificationAccelerationPolicy verificationAccelerationPolicy;
	final ThreadPoolExecutor headerValidationExecutor;
	final int headerValidationParallelism;
	final ExecutorService headerFetchExecutor = Executors.newSingleThreadExecutor(
			runnable -> daemonThread(runnable, "Sync-Header-Fetcher"));
	final ExecutorService headerStageExecutor = Executors.newSingleThreadExecutor(
			runnable -> daemonThread(runnable, "Sync-Header-Stage"));

	@Autowired
	public BlockSyncManagerService(
			MeterRegistry registry,
			@Qualifier("masterChainLock") ReentrantLock masterChainLock,
			@Qualifier(CORE_TASK_EXECUTOR) Executor coreTaskExecutor,
			MiningService miningService,
			IdentityService identityService,
			BlockValidator blockValidationService,
			ChainQuery chainQueryService,
			BlockReorgs blockReorgService,
			PeerRegistry peerRegistry,
			PeerReputationService peerReputationService,
			BlockIngestionService blockIngestionService,
			SyncVerificationAccelerationPolicy verificationAccelerationPolicy) {
		this(registry, masterChainLock, coreTaskExecutor, miningService, identityService,
				blockValidationService, chainQueryService, blockReorgService, peerRegistry,
				peerReputationService, blockIngestionService, verificationAccelerationPolicy,
				Runtime.getRuntime().availableProcessors());
	}

	BlockSyncManagerService(
			MeterRegistry registry,
			ReentrantLock masterChainLock,
			Executor coreTaskExecutor,
			MiningService miningService,
			IdentityService identityService,
			BlockValidator blockValidationService,
			ChainQuery chainQueryService,
			BlockReorgs blockReorgService,
			PeerRegistry peerRegistry,
			PeerReputationService peerReputationService,
			BlockIngestionService blockIngestionService,
			SyncVerificationAccelerationPolicy verificationAccelerationPolicy,
			int availableProcessors) {
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
		this.verificationAccelerationPolicy = verificationAccelerationPolicy;
		this.headerValidationParallelism = calculateHeaderValidationParallelism(
				availableProcessors,
				blockValidationService.headerValidationConcurrencyLimit(availableProcessors));
		this.headerValidationExecutor = new ThreadPoolExecutor(
				headerValidationParallelism,
				headerValidationParallelism,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(SYNC_CHUNK_SIZE_HEADERS),
				runnable -> daemonThread(runnable, "Sync-Header-Validator"),
				new ThreadPoolExecutor.AbortPolicy());
	}

	private static Thread daemonThread(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

	static int calculateHeaderValidationParallelism(int availableProcessors, int verifierCapacity) {
		return Math.max(1, Math.min(Math.max(1, availableProcessors), Math.max(1, verifierCapacity)));
	}

	final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Sync-Manager"));
	final AtomicBoolean isRunning = new AtomicBoolean(false);
	private final AtomicBoolean activeSyncCycle = new AtomicBoolean(false);
	private final AtomicBoolean acceleratedCatchUpActive = new AtomicBoolean(false);

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
	private final SyncProgressEstimator syncProgressEstimator = new SyncProgressEstimator();
	private LongSupplier nanoTicker = System::nanoTime;
	private volatile int lastHeaderValidationWorkers;
	private volatile int lastHeaderValidationChunks;
	private volatile int lastHeaderValidationEpochGroups;
	private volatile int lastHeaderPrefetchDepth = 1;
	private volatile int bufferedHeaderWindows;
	private volatile int bufferedHeaderCount;
	private volatile long bufferedHeaderBytes;
	private volatile int peakBufferedHeaderWindows;
	private volatile int peakBufferedHeaderCount;
	private volatile long peakBufferedHeaderBytes;
	private volatile int validatedAheadHeaders;
	private volatile ProofOfWorkVerificationMode lastHeaderValidationMode;
	private int consecutiveFullValidationWindows;
	private RemotePeer lastHeaderSyncPeer;
	private final AtomicLong legacyHeaderPageRequests = new AtomicLong();
	private final AtomicLong v2HeaderPageRequests = new AtomicLong();
	private final AtomicInteger maxHeaderPageRequested = new AtomicInteger();
	private final AtomicLong discardedPrefetchHeaders = new AtomicLong();
	private volatile long currentPersistBatchBytes;
	private volatile long peakPersistBatchBytes;

	HeaderValidationWorkSnapshot headerValidationWorkSnapshot() {
		return new HeaderValidationWorkSnapshot(
				lastHeaderValidationWorkers,
				lastHeaderValidationChunks,
				lastHeaderValidationEpochGroups);
	}

	record HeaderValidationWorkSnapshot(int workers, int chunks, int epochGroups) {
	}

	HeaderPipelineSnapshot headerPipelineSnapshot() {
		return new HeaderPipelineSnapshot(lastHeaderPrefetchDepth, bufferedHeaderWindows,
				bufferedHeaderCount, bufferedHeaderBytes, validatedAheadHeaders,
				lastHeaderValidationMode, consecutiveFullValidationWindows);
	}

	record HeaderPipelineSnapshot(
			int depthLimit,
			int bufferedWindows,
			int bufferedHeaders,
			long bufferedBytes,
			int validatedAheadHeaders,
			ProofOfWorkVerificationMode mode,
			int consecutiveFullWindows) {
	}

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
				peakPersistBatchBytes,
				maxHeaderPageRequested.get(),
				legacyHeaderPageRequests.get(),
				v2HeaderPageRequests.get(),
				lastHeaderPrefetchDepth,
				bufferedHeaderWindows,
				bufferedHeaderCount,
				bufferedHeaderBytes,
				peakBufferedHeaderWindows,
				peakBufferedHeaderCount,
				peakBufferedHeaderBytes,
				validatedAheadHeaders,
				discardedPrefetchHeaders.get());
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
			long persistenceBatchPeakBytes,
			int maxHeaderPageRequested,
			long legacyHeaderPageRequests,
			long v2HeaderPageRequests,
			int headerPrefetchDepthLimit,
			int bufferedHeaderWindows,
			int bufferedHeaderCount,
			long bufferedHeaderBytes,
			int peakBufferedHeaderWindows,
			int peakBufferedHeaderCount,
			long peakBufferedHeaderBytes,
			int validatedAheadHeaders,
			long discardedPrefetchHeaders) {
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
		// -1 means unavailable (no successful forward cycle yet, or an ETA beyond
		// the estimator's safe 100-year horizon).
		registry.gauge("blockchain.sync.effective_blocks_per_second", this,
				svc -> svc.syncProgressEstimator.telemetry().effectiveBlocksPerSecond());
		registry.gauge("blockchain.sync.estimated_remaining_seconds", this,
				svc -> svc.syncProgressEstimator.telemetry().estimatedRemainingSeconds());
		registry.gauge("blockchain.sync.header_validation.active", headerValidationExecutor,
				ThreadPoolExecutor::getActiveCount);
		registry.gauge("blockchain.sync.header_validation.queued", headerValidationExecutor,
				executor -> executor.getQueue().size());
		registry.gauge("blockchain.sync.header_validation.parallelism", this,
				svc -> svc.headerValidationParallelism);
		registry.gauge("blockchain.sync.header_validation.workers", this,
				svc -> svc.lastHeaderValidationWorkers);
		registry.gauge("blockchain.sync.header_validation.chunks", this,
				svc -> svc.lastHeaderValidationChunks);
		registry.gauge("blockchain.sync.header_validation.epoch_groups", this,
				svc -> svc.lastHeaderValidationEpochGroups);
		registry.gauge("blockchain.sync.header_prefetch.depth_limit", this,
				svc -> svc.lastHeaderPrefetchDepth);
		registry.gauge("blockchain.sync.header_prefetch.windows", this,
				svc -> svc.bufferedHeaderWindows);
		registry.gauge("blockchain.sync.header_prefetch.headers", this,
				svc -> svc.bufferedHeaderCount);
		registry.gauge("blockchain.sync.header_prefetch.bytes", this,
				svc -> svc.bufferedHeaderBytes);
		registry.gauge("blockchain.sync.header_validation.validated_ahead_headers", this,
				svc -> svc.validatedAheadHeaders);
	}

	/** Wakes the sync loop immediately instead of waiting for its periodic poll. */
	public boolean requestSync() {
		return isRunning.get() && signalQueue.offer(new Object());
	}

	@PreDestroy
	public void stop() {
		if (isRunning.getAndSet(false)) {
			log.info("Sync Manager stopped");
		}
		syncExecutor.shutdownNow();
		headerFetchExecutor.shutdownNow();
		headerStageExecutor.shutdownNow();
		headerValidationExecutor.shutdownNow();
		pendingHeaderRequests.values().forEach(future -> future.cancel(true));
		pendingBodyRequests.values().forEach(future -> future.cancel(true));
		pendingHeaderRequests.clear();
		pendingBodyRequests.clear();
		pendingBroadcastDownloads.clear();
		signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.STOPPED, true);
	}

	private void syncLoop() {
		while (isRunning.get()) {
			try {
				// Use short poll during active sync, longer when synced
				long pollDelay = synced ? 5000 : SYNC_POLL_DELAY_MS;
				signalQueue.poll(pollDelay, TimeUnit.MILLISECONDS);
				checkAndSync();
			} catch (InterruptedException e) {
				miningService.resumeMining();
				signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.FAILED, false);
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				miningService.resumeMining();
				signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.FAILED, false);
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
				signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP, false);
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
				selectHeaderSyncPeer(bestPeer);
				signalCatchUpGap(localBestStored.getHeight(), bestPeer.getHeadHeight());

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
				signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP, false);
			}
		} catch (Exception e) {
			miningService.resumeMining();
			signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.FAILED, false);
			log.error("Critical error in checkAndSync", e);
		}
	}

	private boolean performSync(RemotePeer peer, StoredBlock localBestStored,
			BigInteger advertisedTotalDifficulty) {
		Timer.Sample sample = Timer.start(registry);
		long cycleStart = nanoTime();
		long targetHeight = peer.getHeadHeight();
		activeSyncCycle.set(true);
		boolean cycleSucceeded = false;
		try {
			log.debug("Starting sync with peer {}", peer.getIdentity());
			Block localBest = localBestStored.getBlock();
			int prefetchDepth = calculateAdaptiveHeaderPrefetchDepth(
					localBestStored.getHeight(), targetHeight);
			lastHeaderPrefetchDepth = prefetchDepth;
			HeaderFetchPipeline headerPipeline = startHeaderPipeline(peer, localBest, prefetchDepth);
			long headerStart = nanoTime();
			HeaderWindow currentWindow = headerPipeline.nextWindow();
			long headerNanos = elapsedNanos(headerStart);

			if (currentWindow == null) {
				headerPipeline.awaitCompletion();
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

				// A previously successful cycle may have retained the SYNC suspension.
				// A genuine no-work result must never strand mining paused.
				miningService.resumeMining();
				emptyHeaderClaimTracker.clear(peer.getIdentity());
				log.debug("No new headers found from peer");
				cycleSucceeded = true;
				return true;
			}
			emptyHeaderClaimTracker.clear(peer.getIdentity());

			Map<Long, Hash> validatedHeaderContext = new HashMap<>();
			long validationNanos = 0L;
			long bodyNanos = 0L;
			int totalBlocksProcessed = 0;
			CompletableFuture<ValidatedHeaderWindow> speculativeValidation = null;
			try {
				ValidatedHeaderWindow currentValidated = validateHeaderWindow(
						currentWindow, Map.copyOf(validatedHeaderContext), true);
				validationNanos += currentValidated.validationNanos();
				while (true) {
					rememberValidatedHeaders(validatedHeaderContext, currentWindow.headers());
					HeaderWindow nextWindow = null;
					Throwable fetchFailure = null;
					try {
						nextWindow = headerPipeline.nextWindow();
					} catch (RuntimeException | Error failure) {
						fetchFailure = failure;
					}
					HeaderWindow validationWindow = nextWindow;
					CompletableFuture<ValidatedHeaderWindow> nextValidation = validationWindow == null
							? null
							: validateHeaderWindowAsync(
									validationWindow, Map.copyOf(validatedHeaderContext));
					speculativeValidation = nextValidation;
					if (nextWindow != null) {
						validatedAheadHeaders = nextWindow.headers().size();
					}

					long bodyStart = nanoTime();
					try {
						totalBlocksProcessed += downloadAndPersistBodiesInBatches(
								peer, currentWindow.headers(), currentValidated.proofs());
					} finally {
						long elapsed = elapsedNanos(bodyStart);
						bodyNanos += elapsed;
						recordStageDuration("header_window_body_persistence", bodyStart);
					}
					assertCommittedWindow(currentWindow);
					recordHeaderWindowCommitted(currentWindow, currentValidated);
					if (fetchFailure != null) {
						throwHeaderValidationFailure(fetchFailure);
					}

					if (nextValidation == null) {
						headerPipeline.awaitCompletion();
						break;
					}
					currentWindow = nextWindow;
					currentValidated = awaitValidatedWindow(nextValidation);
					speculativeValidation = null;
					validationNanos += currentValidated.validationNanos();
					validatedAheadHeaders = 0;
				}
			} finally {
				headerPipeline.cancel();
				cancelAndDrainSpeculativeValidation(speculativeValidation);
				validatedAheadHeaders = 0;
			}
			long totalNanos = elapsedNanos(cycleStart);
			long committedHeight = chainQueryService.getLatestBlockHeight().orElse(-1L);
			long blocksAdvanced = forwardProgress(localBestStored.getHeight(), committedHeight);

			peerReputationService.recordSuccess(peer.getIdentity());
			registry.counter("blockchain.sync.blocks_downloaded").increment(totalBlocksProcessed);
			SyncProgressEstimator.Estimate progress = syncProgressEstimator.recordCycle(
					true, blocksAdvanced, totalNanos, committedHeight, targetHeight).orElseThrow();
			signalSyncProgress(committedHeight, targetHeight);

			log.info("Sync progress: local {}/{}, remaining {}, effective rate {} blocks/s, ETA {}, "
					+ "cycle {} blocks in {}ms (headers {}ms, validation {}ms, bodies {}ms)",
					committedHeight, targetHeight, formatRemaining(progress.remainingBlocks()),
					SyncProgressEstimator.formatRate(progress.effectiveBlocksPerSecond()),
					SyncProgressEstimator.formatEta(progress), totalBlocksProcessed,
					nanosToMillis(totalNanos), nanosToMillis(headerNanos),
					nanosToMillis(validationNanos), nanosToMillis(bodyNanos));
			log.info("Sync pipeline summary: maxHeaderPageRequested={} legacyHeaderPageRequests={} "
					+ "v2HeaderPageRequests={} maxBodyRequestSize={} internalWindowLimit={} "
					+ "peakPrefetchHeaders={} peakPrefetchBytes={} discardedPrefetchHeaders={}",
					maxHeaderPageRequested.get(), legacyHeaderPageRequests.get(), v2HeaderPageRequests.get(),
					calculateBodyBatchSize(), INTERNAL_VALIDATION_WINDOW_HEADERS,
					peakBufferedHeaderCount, peakBufferedHeaderBytes, discardedPrefetchHeaders.get());

			cycleSucceeded = true;
			return true;
		} catch (IncompatibleChainException e) {
			// Peer is on a fundamentally different chain (different genesis or hard fork)
			// Ban them permanently as they'll never be useful to us
			log.warn("INCOMPATIBLE CHAIN: Banning peer {} - {}", peer.getIdentity(), e.getMessage());
			peer.disconnect("Incompatible chain: " + e.getMessage());
			peerReputationService.ban(peer.getIdentity());
			return false;
		} catch (StaleHeaderPrefetchException e) {
			log.info("Discarding stale prefetched header suffix without penalizing peer {}: {}",
					peer.getIdentity(), e.getMessage());
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
			if (!cycleSucceeded) {
				resetHeaderPipelineAdaptation();
				miningService.resumeMining();
				signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.FAILED, false);
			}
			activeSyncCycle.set(false);
			sample.stop(registry.timer("blockchain.sync.batch_time"));
			registry.counter("blockchain.sync.cycles", "outcome", cycleSucceeded ? "success" : "failure")
					.increment();
		}
	}

	private void resetHeaderPipelineAdaptation() {
		lastHeaderPrefetchDepth = 1;
		lastHeaderValidationMode = null;
		consecutiveFullValidationWindows = 0;
	}

	void selectHeaderSyncPeer(RemotePeer peer) {
		if (lastHeaderSyncPeer != null && lastHeaderSyncPeer != peer) {
			resetHeaderPipelineAdaptation();
			recordDiscardedPrefetch("peer_switch", 1L);
		}
		lastHeaderSyncPeer = peer;
	}

	void signalCatchUpGap(long localHeight, long targetHeight) {
		try {
			if (acceleratedCatchUpActive.compareAndSet(false, true)) {
				verificationAccelerationPolicy.bulkCatchUpStarted(localHeight, targetHeight);
			} else {
				verificationAccelerationPolicy.progress(localHeight, targetHeight);
			}
		} catch (RuntimeException failure) {
			log.warn("Sync verification acceleration start signal failed", failure);
		}
	}

	void signalSyncProgress(long localHeight, long targetHeight) {
		try {
			verificationAccelerationPolicy.progress(localHeight, targetHeight);
		} catch (RuntimeException failure) {
			log.warn("Sync verification acceleration progress signal failed", failure);
		}
	}

	void signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason reason, boolean force) {
		if (!acceleratedCatchUpActive.getAndSet(false) && !force) {
			return;
		}
		try {
			verificationAccelerationPolicy.syncEnded(reason);
		} catch (RuntimeException failure) {
			log.warn("Sync verification acceleration end signal failed: {}", reason, failure);
		} finally {
			lastHeaderSyncPeer = null;
			resetHeaderPipelineAdaptation();
		}
	}

	Map<Hash, StatelessValidatedHeader> validateBatchWithMiningCoordination(List<BlockHeader> headers) {
		return validateBatchWithMiningCoordination(headers, Map.of()).proofs();
	}

	HeaderValidationResult validateBatchWithMiningCoordination(
			List<BlockHeader> headers, Map<Long, Hash> priorValidatedContext) {
		return validateBatchWithMiningCoordination(headers, priorValidatedContext, true);
	}

	private HeaderValidationResult validateBatchWithMiningCoordination(
			List<BlockHeader> headers,
			Map<Long, Hash> priorValidatedContext,
			boolean resumeMiningOnFailure) {
		// Header proof-of-work is the dominant CPU stage. Quiesce autonomous mining
		// before it starts instead of allowing mining and verification to compete.
		long miningPauseStart = nanoTime();
		try {
			miningService.pauseMining();
		} finally {
			recordStageDuration("mining_quiescence", miningPauseStart);
		}
		try {
			return validateBatchResult(headers, priorValidatedContext);
		} catch (RuntimeException | Error failure) {
			if (resumeMiningOnFailure) {
				miningService.resumeMining();
			}
			throw failure;
		}
	}

	private void recordStageDuration(String stage, long startNanos) {
		registry.timer("blockchain.sync.stage", "stage", stage)
				.record(elapsedNanos(startNanos), TimeUnit.NANOSECONDS);
	}

	void setNanoTickerForTesting(LongSupplier nanoTicker) {
		this.nanoTicker = Objects.requireNonNull(nanoTicker);
	}

	private long nanoTime() {
		return nanoTicker.getAsLong();
	}

	private long elapsedNanos(long startNanos) {
		return Math.max(0L, nanoTime() - startNanos);
	}

	private static long nanosToMillis(long nanos) {
		return TimeUnit.NANOSECONDS.toMillis(nanos);
	}

	private static long forwardProgress(long previousHeight, long committedHeight) {
		if (previousHeight < 0 || committedHeight <= previousHeight) {
			return 0;
		}
		return committedHeight - previousHeight;
	}

	private static String formatRemaining(long remainingBlocks) {
		return remainingBlocks < 0 ? "unknown" : Long.toString(remainingBlocks);
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
				long bodyAttemptStart = nanoTime();
				try {
					bodies = oldest.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
					validatedBodies = validateBodyResponse(oldest, bodies, validatedHeaders);
				} catch (LocalSyncInvariantException localInvariant) {
					pendingBodyRequests.remove(oldest.requestKey());
					inflightBudget.release(oldest.range().reservedBytes());
					bodyPipelineTelemetry.requestCompleted(oldest.peer(), oldest.range().reservedBytes());
					throw localInvariant;
				} catch (InterruptedException interrupted) {
					pendingBodyRequests.remove(oldest.requestKey());
					inflightBudget.release(oldest.range().reservedBytes());
					bodyPipelineTelemetry.requestCompleted(oldest.peer(), oldest.range().reservedBytes());
					cleanupPendingBodyRequests(pendingRequests);
					Thread.currentThread().interrupt();
					throw interrupted;
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
		long stageStart = nanoTime();
		try {
			long start = nanoTime();
			blockReorgService.executeAtomicReorgSwap(new ValidatedSyncBatch(commonAncestor, blocks));
			long elapsed = nanosToMillis(elapsedNanos(start));
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

	static int calculateHeaderPrefetchDepth(
			long localHeight,
			long targetHeight,
			ProofOfWorkVerificationMode previousMode,
			int consecutiveFullWindows) {
		long gap = targetHeight > localHeight ? targetHeight - localHeight : 0L;
		if (gap <= INTERNAL_VALIDATION_WINDOW_HEADERS) {
			return 1;
		}
		if (previousMode == ProofOfWorkVerificationMode.RANDOMX_FULL
				&& consecutiveFullWindows >= 2) {
			return 4;
		}
		return 2;
	}

	private int calculateAdaptiveHeaderPrefetchDepth(long localHeight, long targetHeight) {
		return calculateHeaderPrefetchDepth(
				localHeight, targetHeight, lastHeaderValidationMode, consecutiveFullValidationWindows);
	}

	private HeaderFetchPipeline startHeaderPipeline(
			RemotePeer peer, Block localBest, int depthLimit) {
		int maxHeaders = depthLimit >= 4 ? MAX_LOCAL_HEADER_WINDOW
				: Math.multiplyExact(depthLimit, INTERNAL_VALIDATION_WINDOW_HEADERS);
		HeaderFetchPipeline pipeline = new HeaderFetchPipeline(depthLimit);
		Future<?> fetchTask = headerFetchExecutor.submit(() -> {
			try {
				fetchHeaderWindows(peer, localBest, maxHeaders, pipeline);
				pipeline.complete();
			} catch (Throwable failure) {
				pipeline.fail(failure);
			}
		});
		pipeline.attach(fetchTask);
		return pipeline;
	}

	private void fetchHeaderWindows(
			RemotePeer peer,
			Block localBest,
			int maxHeaders,
			HeaderFetchPipeline pipeline) throws Exception {
		Hash stopHash = peer.getHeadHash();
		List<Hash> currentLocators = new ArrayList<>(chainQueryService.getLocatorHashes());
		Hash expectedPrevious = null;
		Long expectedPreviousHeight = null;
		boolean firstHeader = true;
		int totalHeaders = 0;
		long totalBytes = 0L;
		long totalAllowedBytes = 0L;
		List<BlockHeader> currentWindow = new ArrayList<>(INTERNAL_VALIDATION_WINDOW_HEADERS);
		long currentWindowBytes = 0L;

		while (!pipeline.cancelled() && totalHeaders < maxHeaders) {
			int negotiatedLimit = negotiatedHeaderPageLimit(peer);
			int requested = Math.min(negotiatedLimit, maxHeaders - totalHeaders);
			recordHeaderPageRequest(negotiatedLimit, requested);
			CompletableFuture<List<BlockHeader>> future = new CompletableFuture<>();
			long requestId = peer.reserveRequestId();
			PeerRequestKey requestKey = new PeerRequestKey(peer, requestId);
			registerHeaderRequest(requestKey, future);
			recordHeaderRequest();
			long requestStarted = nanoTime();
			List<BlockHeader> page;
			try {
				peer.sendGetBlockHeaders(currentLocators, stopHash, requested, requestId);
				page = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (Exception failure) {
				pendingHeaderRequests.remove(requestKey);
				throw failure;
			} finally {
				registry.timer("blockchain.sync.header_window.fetch")
						.record(elapsedNanos(requestStarted), TimeUnit.NANOSECONDS);
			}
			if (page == null) {
				throw new GEValidationException("Peer returned a null header page");
			}
			requireHeaderPageWithinBudget(page.size(), requested, totalHeaders, maxHeaders);
			if (page.isEmpty()) {
				break;
			}

			for (BlockHeader header : page) {
				if (header == null) {
					throw new GEValidationException("Peer returned a null header");
				}
				if (firstHeader) {
					Hash firstParent = header.getPreviousHash();
					if (!chainQueryService.hasBlockData(firstParent)) {
						long localHeight = chainQueryService.getLatestBlockHeight().orElse(-1L);
						if (localHeight >= 0) {
							throw new IncompatibleChainException(
									"Peer chain does not connect to local storage at " + firstParent);
						}
						throw new GEValidationException("Peer header parent is missing from local storage");
					}
					firstHeader = false;
				} else {
					if (!header.getPreviousHash().equals(expectedPrevious)) {
						throw new GEValidationException("Broken prefetched header linkage");
					}
					if (expectedPreviousHeight != null
							&& header.getHeight() != expectedPreviousHeight + 1L) {
						throw new GEValidationException("Broken prefetched header height sequence");
					}
				}

				long headerBytes = header.getSize();
				long allowedBytes = Constants.getSettings().getMaxHeaderSizeInBytes(header.getHeight());
				if (headerBytes < 1 || headerBytes > allowedBytes) {
					throw new GEValidationException("Header size exceeded during prefetch at height "
							+ header.getHeight());
				}
				totalHeaders = Math.addExact(totalHeaders, 1);
				totalBytes = Math.addExact(totalBytes, headerBytes);
				totalAllowedBytes = Math.addExact(totalAllowedBytes, allowedBytes);
				if (totalHeaders > maxHeaders || totalBytes > totalAllowedBytes) {
					throw new GEValidationException("Local header super-window budget exceeded");
				}
				currentWindow.add(header);
				currentWindowBytes = Math.addExact(currentWindowBytes, headerBytes);
				expectedPrevious = header.getHash();
				expectedPreviousHeight = header.getHeight();
				if (currentWindow.size() == INTERNAL_VALIDATION_WINDOW_HEADERS) {
					pipeline.publish(new HeaderWindow(
							List.copyOf(currentWindow), currentWindowBytes, peer));
					currentWindow.clear();
					currentWindowBytes = 0L;
				}
			}

			if (expectedPrevious != null && expectedPrevious.equals(stopHash)) {
				break;
			}
			if (page.size() < requested) {
				break;
			}
			currentLocators = List.of(expectedPrevious);
		}
		if (!currentWindow.isEmpty()) {
			pipeline.publish(new HeaderWindow(List.copyOf(currentWindow), currentWindowBytes, peer));
		}
	}

	private int negotiatedHeaderPageLimit(RemotePeer peer) {
		int negotiated = peer.negotiatedHeaderPageLimit();
		return negotiated == LEGACY_HEADER_PAGE_LIMIT || negotiated == MAX_LOCAL_HEADER_WINDOW
				? negotiated : LEGACY_HEADER_PAGE_LIMIT;
	}

	private void recordHeaderPageRequest(int negotiatedLimit, int requested) {
		maxHeaderPageRequested.accumulateAndGet(requested, Math::max);
		if (negotiatedLimit == LEGACY_HEADER_PAGE_LIMIT) {
			legacyHeaderPageRequests.incrementAndGet();
		} else {
			v2HeaderPageRequests.incrementAndGet();
		}
	}

	private void recordDiscardedPrefetch(String reason, long headers) {
		long bounded = Math.max(0L, headers);
		discardedPrefetchHeaders.addAndGet(bounded);
		registry.counter("blockchain.sync.header_window.discarded", "reason", reason).increment(bounded);
	}

	static void requireHeaderPageWithinBudget(
			int received, int requested, int accumulated, int maxHeaders) {
		if (maxHeaders < 1 || maxHeaders > MAX_LOCAL_HEADER_WINDOW
				|| requested < 1 || requested > maxHeaders
				|| received < 0 || received > requested
				|| accumulated < 0 || accumulated > maxHeaders
				|| received > maxHeaders - accumulated) {
			throw new GEValidationException("Peer exceeded negotiated/local header page budget: requested "
					+ requested + ", received " + received + ", accumulated " + accumulated
					+ ", maximum " + maxHeaders);
		}
	}

	private ValidatedHeaderWindow validateHeaderWindow(
			HeaderWindow window,
			Map<Long, Hash> priorValidatedContext,
			boolean resumeMiningOnFailure) {
		long started = nanoTime();
		HeaderValidationResult result = validateBatchWithMiningCoordination(
				window.headers(), priorValidatedContext, resumeMiningOnFailure);
		long elapsed = elapsedNanos(started);
		registry.timer("blockchain.sync.header_window.validation", "mode", modeTag(result.mode()))
				.record(elapsed, TimeUnit.NANOSECONDS);
		registry.counter("blockchain.sync.header_window.validated", "mode", modeTag(result.mode()))
				.increment();
		return new ValidatedHeaderWindow(window, result.proofs(), result.mode(), elapsed);
	}

	CompletableFuture<ValidatedHeaderWindow> validateHeaderWindowAsync(
			HeaderWindow window, Map<Long, Hash> priorValidatedContext) {
		return CompletableFuture.supplyAsync(
				() -> validateHeaderWindow(window, priorValidatedContext, false), headerStageExecutor);
	}

	void cancelAndDrainSpeculativeValidation(CompletableFuture<?> validation) {
		if (validation == null) {
			return;
		}
		validation.cancel(true);
		Future<?> drainBarrier;
		try {
			drainBarrier = headerStageExecutor.submit(() -> {
				// A single-thread executor makes this a barrier behind the speculative task.
			});
		} catch (RuntimeException shutdown) {
			log.debug("Header validation stage was already stopped while draining", shutdown);
			return;
		}
		try {
			drainBarrier.get(HEADER_VALIDATION_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			drainBarrier.cancel(true);
			Thread.currentThread().interrupt();
			log.warn("Interrupted while draining speculative header validation", interrupted);
		} catch (ExecutionException failure) {
			log.warn("Speculative header validation drain barrier failed", failure.getCause());
		} catch (TimeoutException timeout) {
			drainBarrier.cancel(true);
			log.warn("Speculative header validation did not drain within {} seconds",
					HEADER_VALIDATION_DRAIN_TIMEOUT_SECONDS);
		}
	}

	private String modeTag(ProofOfWorkVerificationMode mode) {
		return mode == null ? "unknown" : mode.name().toLowerCase(Locale.ROOT);
	}

	private ValidatedHeaderWindow awaitValidatedWindow(
			CompletableFuture<ValidatedHeaderWindow> validation) {
		try {
			return validation.join();
		} catch (CompletionException failure) {
			Throwable cause = failure.getCause();
			throwHeaderValidationFailure(cause == null ? failure : cause);
			throw new IllegalStateException("unreachable");
		}
	}

	void rememberValidatedHeaders(Map<Long, Hash> context, List<BlockHeader> headers) {
		for (BlockHeader header : headers) {
			context.put(header.getHeight(), header.getHash());
		}
	}

	void assertCommittedWindow(HeaderWindow window) {
		BlockHeader expected = window.headers().getLast();
		StoredBlock committed = chainQueryService.getLatestStoredBlockOrThrow();
		if (!matchesCommittedWindow(expected.getHeight(), expected.getHash(), committed)) {
			recordDiscardedPrefetch("reorg", window.headers().size());
			throw new StaleHeaderPrefetchException("Canonical head changed while a header suffix was prefetched");
		}
	}

	static boolean matchesCommittedWindow(long expectedHeight, Hash expectedHash, StoredBlock committed) {
		return committed != null && expectedHash != null
				&& committed.getHeight() == expectedHeight
				&& expectedHash.equals(committed.getHash());
	}

	private void recordHeaderWindowCommitted(
			HeaderWindow window, ValidatedHeaderWindow validated) {
		registry.counter("blockchain.sync.header_window.committed", "mode", modeTag(validated.mode()))
				.increment();
		lastHeaderValidationMode = validated.mode();
		if (validated.mode() == ProofOfWorkVerificationMode.RANDOMX_FULL) {
			consecutiveFullValidationWindows++;
		} else {
			consecutiveFullValidationWindows = 0;
		}
	}

	private List<BlockHeader> downloadHeaders(RemotePeer peer, Block localBest) throws Exception {
		HeaderFetchPipeline pipeline = startHeaderPipeline(peer, localBest, 1);
		List<BlockHeader> headers = new ArrayList<>(INTERNAL_VALIDATION_WINDOW_HEADERS);
		try {
			for (HeaderWindow window; (window = pipeline.nextWindow()) != null;) {
				headers.addAll(window.headers());
			}
			pipeline.awaitCompletion();
			return List.copyOf(headers);
		} finally {
			pipeline.cancel();
		}
	}

	List<HeaderWindow> downloadHeaderWindowsForTesting(
			RemotePeer peer, Block localBest, int depthLimit) {
		HeaderFetchPipeline pipeline = startHeaderPipeline(peer, localBest, depthLimit);
		List<HeaderWindow> windows = new ArrayList<>(depthLimit);
		try {
			for (HeaderWindow window; (window = pipeline.nextWindow()) != null;) {
				windows.add(window);
			}
			pipeline.awaitCompletion();
			return List.copyOf(windows);
		} finally {
			pipeline.cancel();
		}
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
		private static final int MAX_OBSERVATIONS = 1_024;
		private static final long EXPIRY_NANOS = TimeUnit.MINUTES.toNanos(15);
		private final Map<Address, EmptyHeaderObservation> observations = new ConcurrentHashMap<>();

		boolean record(Address identity, Hash localHeadHash) {
			if (identity == null) {
				return false;
			}
			AtomicBoolean thresholdReached = new AtomicBoolean();
			long now = System.nanoTime();
			observations.entrySet().removeIf(entry -> now - entry.getValue().observedAtNanos() >= EXPIRY_NANOS);
			if (observations.size() >= MAX_OBSERVATIONS && !observations.containsKey(identity)) {
				Address eviction = observations.keySet().stream().findFirst().orElse(null);
				if (eviction != null) {
					observations.remove(eviction);
				}
			}
			observations.compute(identity, (ignored, previous) -> {
				int count = previous != null && previous.localHeadHash().equals(localHeadHash)
						? previous.count() + 1
						: 1;
				thresholdReached.set(count >= EMPTY_HEADER_INCOMPATIBILITY_THRESHOLD);
				return new EmptyHeaderObservation(localHeadHash, count, now);
			});
			return thresholdReached.get();
		}

		void clear(Address identity) {
			if (identity != null) {
				observations.remove(identity);
			}
		}

		int size() {
			return observations.size();
		}
	}

	private record EmptyHeaderObservation(Hash localHeadHash, int count, long observedAtNanos) {
	}

	/** Validates headers in parallel against the complete batch seed context. */
	Map<Hash, StatelessValidatedHeader> validateBatch(List<BlockHeader> headers) {
		return validateBatchResult(headers, Map.of()).proofs();
	}

	private HeaderValidationResult validateBatchResult(
			List<BlockHeader> headers, Map<Long, Hash> priorValidatedContext) {
		Timer.Sample sample = Timer.start(registry);
		try {
			Map<Long, Hash> contextMap = new HashMap<>(
					Math.addExact(headers.size(), priorValidatedContext.size()));
			contextMap.putAll(priorValidatedContext);
			for (BlockHeader header : headers) {
				contextMap.put(header.getHeight(), header.getHash());
			}
			Map<Long, Hash> immutableContext = Map.copyOf(contextMap);
			List<PreparedHeaderValidation> preparedHeaders = new ArrayList<>(headers.size());
			for (BlockHeader header : headers) {
				preparedHeaders.add(blockValidationService.prepareHeader(header, immutableContext));
			}

			List<HeaderEpochGroup> epochGroups = groupPreparedHeaders(preparedHeaders);
			AtomicReferenceArray<StatelessValidatedHeader> proofs =
					new AtomicReferenceArray<>(headers.size());
			AtomicReference<ProofOfWorkVerificationMode> mode = new AtomicReference<>();
			List<HeaderEpochWork> epochWork = epochGroups.stream()
					.map(group -> new HeaderEpochWork(group.context(), partitionEpochGroup(group)))
					.toList();
			lastHeaderValidationEpochGroups = epochGroups.size();
			lastHeaderValidationChunks = epochWork.stream()
					.mapToInt(work -> work.chunks().size())
					.sum();
			lastHeaderValidationWorkers = epochWork.stream()
					.mapToInt(work -> work.chunks().size())
					.max()
					.orElse(0);
			for (HeaderEpochWork work : epochWork) {
				validateEpochGroup(work.context(), work.chunks(), proofs, mode);
			}

			Map<Hash, StatelessValidatedHeader> validatedHeaders = new HashMap<>();
			for (int index = 0; index < headers.size(); index++) {
				StatelessValidatedHeader proof = proofs.get(index);
				if (proof == null) {
					throw new GEFailedException("Missing stateless header proof at index " + index);
				}
				validatedHeaders.put(headers.get(index).getHash(), proof);
			}
			return new HeaderValidationResult(Map.copyOf(validatedHeaders), mode.get());
		} catch (RuntimeException | Error failure) {
			registry.counter("blockchain.sync.header_validation.failures").increment();
			throw failure;
		} finally {
			sample.stop(registry.timer("blockchain.sync.header_validation.duration"));
		}
	}

	private List<HeaderEpochGroup> groupPreparedHeaders(List<PreparedHeaderValidation> preparedHeaders) {
		List<MutableHeaderEpochGroup> groups = new ArrayList<>();
		for (int index = 0; index < preparedHeaders.size(); index++) {
			PreparedHeaderValidation prepared = preparedHeaders.get(index);
			ProofOfWorkVerificationContext context = prepared.verificationContext();
			MutableHeaderEpochGroup group = groups.isEmpty() ? null : groups.getLast();
			if (group == null || !group.context.equals(context)) {
				group = new MutableHeaderEpochGroup(context);
				groups.add(group);
			}
			group.headers.add(new IndexedPreparedHeader(index, prepared));
		}
		return groups.stream()
				.map(group -> new HeaderEpochGroup(group.context, List.copyOf(group.headers)))
				.toList();
	}

	private List<HeaderValidationChunk> partitionEpochGroup(HeaderEpochGroup group) {
		int workerCount = Math.min(headerValidationParallelism, group.headers().size());
		int baseSize = group.headers().size() / workerCount;
		int remainder = group.headers().size() % workerCount;
		List<HeaderValidationChunk> chunks = new ArrayList<>(workerCount);
		int offset = 0;
		for (int worker = 0; worker < workerCount; worker++) {
			int size = baseSize + (worker < remainder ? 1 : 0);
			int end = offset + size;
			chunks.add(new HeaderValidationChunk(List.copyOf(group.headers().subList(offset, end))));
			offset = end;
		}
		return List.copyOf(chunks);
	}

	private void validateEpochGroup(
			ProofOfWorkVerificationContext context,
			List<HeaderValidationChunk> chunks,
			AtomicReferenceArray<StatelessValidatedHeader> proofs,
			AtomicReference<ProofOfWorkVerificationMode> mode) {
		AtomicReference<IndexedValidationFailure> earliestFailure = new AtomicReference<>();
		List<Callable<Void>> tasks = chunks.stream()
				.map(chunk -> (Callable<Void>) () -> {
					validateChunk(context, chunk, proofs, earliestFailure, mode);
					return null;
				})
				.toList();
		executeHeaderValidationTasks(tasks);
		IndexedValidationFailure failure = earliestFailure.get();
		if (failure != null) {
			throwHeaderValidationFailure(failure.cause());
		}
	}

	private void validateChunk(
			ProofOfWorkVerificationContext context,
			HeaderValidationChunk chunk,
			AtomicReferenceArray<StatelessValidatedHeader> proofs,
			AtomicReference<IndexedValidationFailure> earliestFailure,
			AtomicReference<ProofOfWorkVerificationMode> mode) {
		int firstIndex = chunk.headers().getFirst().index();
		int validationFailureIndex = -1;
		try (ProofOfWorkVerificationSession session =
				blockValidationService.openVerificationSession(context)) {
			mode.accumulateAndGet(session.mode(), BlockSyncManagerService::preferVerificationMode);
			for (IndexedPreparedHeader indexed : chunk.headers()) {
				IndexedValidationFailure cutoff = earliestFailure.get();
				if (cutoff != null && indexed.index() > cutoff.index()) {
					return;
				}
				try {
					StatelessValidatedHeader proof = blockValidationService.validatePreparedHeader(
							indexed.prepared(), session);
					proofs.set(indexed.index(), proof);
				} catch (RuntimeException | Error failure) {
					validationFailureIndex = indexed.index();
					recordEarlierFailure(earliestFailure, indexed.index(), failure);
					break;
				}
			}
		} catch (RuntimeException | Error failure) {
			if (validationFailureIndex < 0) {
				recordEarlierFailure(earliestFailure, firstIndex, failure);
			}
		}
	}

	private static ProofOfWorkVerificationMode preferVerificationMode(
			ProofOfWorkVerificationMode current, ProofOfWorkVerificationMode candidate) {
		if (current == ProofOfWorkVerificationMode.RANDOMX_FULL
				|| candidate == ProofOfWorkVerificationMode.RANDOMX_FULL) {
			return ProofOfWorkVerificationMode.RANDOMX_FULL;
		}
		return current == null ? candidate : current;
	}

	private void recordEarlierFailure(
			AtomicReference<IndexedValidationFailure> earliestFailure,
			int index,
			Throwable cause) {
		earliestFailure.accumulateAndGet(
				new IndexedValidationFailure(index, cause),
				(current, candidate) -> current == null || candidate.index() < current.index()
						? candidate : current);
	}

	private void throwHeaderValidationFailure(Throwable cause) {
		if (cause instanceof RuntimeException runtimeException) {
			throw runtimeException;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		throw new GEFailedException("Header validation failed", cause);
	}

	private <T> List<T> executeHeaderValidationTasks(List<? extends Callable<T>> tasks) {
		List<Future<T>> futures = new ArrayList<>(tasks.size());
		try {
			for (Callable<T> task : tasks) {
				futures.add(headerValidationExecutor.submit(task));
			}
			List<T> results = new ArrayList<>(tasks.size());
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		} catch (InterruptedException e) {
			cancelHeaderValidation(futures);
			Thread.currentThread().interrupt();
			throw new GEFailedException("Header validation was interrupted", e);
		} catch (ExecutionException e) {
			cancelHeaderValidation(futures);
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new GEFailedException("Header validation failed", cause);
		} catch (RuntimeException | Error e) {
			cancelHeaderValidation(futures);
			throw e;
		}
	}

	private void cancelHeaderValidation(List<? extends Future<?>> futures) {
		futures.forEach(future -> future.cancel(true));
		headerValidationExecutor.purge();
		long nextWarning = System.nanoTime()
				+ TimeUnit.SECONDS.toNanos(HEADER_VALIDATION_DRAIN_TIMEOUT_SECONDS);
		while (headerValidationExecutor.getActiveCount() > 0
				|| !headerValidationExecutor.getQueue().isEmpty()) {
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
			if (System.nanoTime() >= nextWarning) {
				log.warn("Still waiting for native header validation workers to stop safely");
				nextWarning = System.nanoTime()
						+ TimeUnit.SECONDS.toNanos(HEADER_VALIDATION_DRAIN_TIMEOUT_SECONDS);
			}
		}
	}

	private static final class MutableHeaderEpochGroup {
		private final ProofOfWorkVerificationContext context;
		private final List<IndexedPreparedHeader> headers = new ArrayList<>();

		private MutableHeaderEpochGroup(ProofOfWorkVerificationContext context) {
			this.context = context;
		}
	}

	private record IndexedPreparedHeader(int index, PreparedHeaderValidation prepared) {
	}

	private record HeaderEpochGroup(
			ProofOfWorkVerificationContext context,
			List<IndexedPreparedHeader> headers) {
	}

	private record HeaderValidationChunk(List<IndexedPreparedHeader> headers) {
	}

	private record HeaderEpochWork(
			ProofOfWorkVerificationContext context,
			List<HeaderValidationChunk> chunks) {
	}

	private record IndexedValidationFailure(int index, Throwable cause) {
	}

	record HeaderValidationResult(
			Map<Hash, StatelessValidatedHeader> proofs,
			ProofOfWorkVerificationMode mode) {
	}

	record HeaderWindow(List<BlockHeader> headers, long canonicalBytes, RemotePeer peer) {
		HeaderWindow {
			headers = List.copyOf(headers);
			if (headers.isEmpty() || headers.size() > INTERNAL_VALIDATION_WINDOW_HEADERS) {
				throw new IllegalArgumentException("Header window size is out of bounds");
			}
			if (canonicalBytes < 1) {
				throw new IllegalArgumentException("Header window canonical bytes must be positive");
			}
			Objects.requireNonNull(peer, "peer");
		}
	}

	record ValidatedHeaderWindow(
			HeaderWindow window,
			Map<Hash, StatelessValidatedHeader> proofs,
			ProofOfWorkVerificationMode mode,
			long validationNanos) {
	}

	private final class HeaderFetchPipeline {
		private final ArrayBlockingQueue<HeaderWindow> windows;
		private final CompletableFuture<Void> completion = new CompletableFuture<>();
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private volatile Future<?> fetchTask;
		private int queuedHeaders;
		private long queuedBytes;

		private HeaderFetchPipeline(int depthLimit) {
			this.windows = new ArrayBlockingQueue<>(Math.max(1, depthLimit));
		}

		private void attach(Future<?> task) {
			this.fetchTask = task;
		}

		private boolean cancelled() {
			return cancelled.get();
		}

		private void publish(HeaderWindow window) throws InterruptedException {
			if (cancelled()) {
				return;
			}
			synchronized (this) {
				queuedHeaders += window.headers().size();
				queuedBytes += window.canonicalBytes();
			}
			try {
				windows.put(window);
			} catch (InterruptedException failure) {
				if (!cancelled()) {
					synchronized (this) {
						queuedHeaders -= window.headers().size();
						queuedBytes -= window.canonicalBytes();
					}
				}
				throw failure;
			}
			synchronized (this) {
				publishBufferMetrics();
			}
			registry.counter("blockchain.sync.header_window.fetched").increment();
		}

		private HeaderWindow nextWindow() {
			boolean stalled = windows.peek() == null && !completion.isDone();
			while (true) {
				try {
					HeaderWindow window = windows.poll(SYNC_POLL_DELAY_MS, TimeUnit.MILLISECONDS);
					if (window != null) {
						synchronized (this) {
							queuedHeaders -= window.headers().size();
							queuedBytes -= window.canonicalBytes();
							publishBufferMetrics();
						}
						registry.counter("blockchain.sync.header_prefetch",
								"outcome", stalled ? "stall" : "hit").increment();
						return window;
					}
					if (completion.isDone()) {
						awaitCompletion();
						return null;
					}
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new GEFailedException("Interrupted while waiting for prefetched headers", failure);
				}
			}
		}

		private void complete() {
			completion.complete(null);
		}

		private void fail(Throwable failure) {
			completion.completeExceptionally(failure);
		}

		private void awaitCompletion() {
			try {
				completion.join();
			} catch (CompletionException failure) {
				Throwable cause = failure.getCause();
				throwHeaderValidationFailure(cause == null ? failure : cause);
			}
		}

		private void cancel() {
			if (!cancelled.compareAndSet(false, true)) {
				return;
			}
			Future<?> task = fetchTask;
			if (task != null && !task.isDone()) {
				task.cancel(true);
			}
			List<HeaderWindow> discarded = new ArrayList<>();
			windows.drainTo(discarded);
			if (!discarded.isEmpty()) {
				long headers = discarded.stream().mapToLong(window -> window.headers().size()).sum();
				recordDiscardedPrefetch("cancelled", headers);
			}
			synchronized (this) {
				queuedHeaders = 0;
				queuedBytes = 0L;
				publishBufferMetrics();
			}
		}

		private void publishBufferMetrics() {
			bufferedHeaderWindows = windows.size();
			bufferedHeaderCount = queuedHeaders;
			bufferedHeaderBytes = queuedBytes;
			peakBufferedHeaderWindows = Math.max(peakBufferedHeaderWindows, bufferedHeaderWindows);
			peakBufferedHeaderCount = Math.max(peakBufferedHeaderCount, bufferedHeaderCount);
			peakBufferedHeaderBytes = Math.max(peakBufferedHeaderBytes, bufferedHeaderBytes);
		}
	}

	private static final class StaleHeaderPrefetchException extends RuntimeException {
		private StaleHeaderPrefetchException(String message) {
			super(message);
		}
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

						if (bodies == null || bodies.size() != 1) {
							log.warn("Peer {} sent {} bodies for a one-block request at #{}", peer.getIdentity(),
									bodies == null ? 0 : bodies.size(),
									header.getHeight());
							peerReputationService.recordFailure(peer.getIdentity());
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
						Throwable cause = unwrapCompletionFailure(e);
						if (cause instanceof CancellationException || cause instanceof InterruptedException
								|| !isRunning.get()) {
							log.debug("Broadcast body processing cancelled for block #{}", header.getHeight());
						} else if (cause instanceof TimeoutException) {
							log.warn("Timeout waiting for body of block #{} from {}", header.getHeight(),
									peer.getIdentity());
							peerReputationService.recordFailure(peer.getIdentity()); // Penalty for slowness
						} else {
							log.warn("Failed to download/process broadcast body for #{} - {}", header.getHeight(),
									cause.getMessage());
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

	private Throwable unwrapCompletionFailure(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof CompletionException || current instanceof ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
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
