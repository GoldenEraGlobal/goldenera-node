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
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.netty.P2PChannelInitializer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
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
	 * Number of StoredBlocks to accumulate before persisting to disk.
	 * This limits RAM usage while allowing efficient batch writes.
	 * At 5MB per block max, 250 blocks = ~1.25GB RAM worst case.
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

	final Map<Long, CompletableFuture<List<BlockHeader>>> pendingHeaderRequests = new ConcurrentHashMap<>();
	final Map<Long, CompletableFuture<List<List<Tx>>>> pendingBodyRequests = new ConcurrentHashMap<>();

	final Set<Hash> pendingBroadcastDownloads = ConcurrentHashMap.newKeySet();
	private final SyncRequestTelemetry syncRequestTelemetry = new SyncRequestTelemetry();

	public SyncRuntimeSnapshot runtimeSnapshot() {
		SyncRequestTelemetry.Snapshot requestTelemetry = syncRequestTelemetry.snapshot();
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
				PERSIST_BATCH_SIZE);
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
			int persistenceBatchLimit) {
	}

	public void start() {
		if (isRunning.getAndSet(true))
			return;
		log.info("Sync Manager started");
		syncExecutor.submit(this::syncLoop);
		signalQueue.offer(new Object());
		registry.gauge("blockchain.sync.status", this, svc -> svc.isSynced() ? 1 : 0);
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
			Optional<RemotePeer> bestPeerOpt = peerRegistry.getSyncCandidate(localBestStored.getHeight());
			if (bestPeerOpt.isEmpty()) {
				if (!synced) {
					log.info("Node synced at height {}", localBestStored.getHeight());
					synced = true;
				}
				miningService.resumeMining();
				return;
			}
			RemotePeer bestPeer = bestPeerOpt.get();
			if (bestPeer.getHeadHeight() > localBestStored.getHeight()) {
				log.info("Sync needed: local height {} vs peer height {} ({})", localBestStored.getHeight(),
						bestPeer.getHeadHeight(), bestPeer.getIdentity());
				synced = false;

				boolean success = performSync(bestPeer, localBestStored.getBlock());

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

	private boolean performSync(RemotePeer peer, Block localBest) {
		Timer.Sample sample = Timer.start(registry);
		activeSyncCycle.set(true);
		try {
			log.debug("Starting sync with peer {}", peer.getIdentity());

			long headerStart = System.currentTimeMillis();
			List<BlockHeader> headersToSync = downloadHeaders(peer, localBest);
			long headerTime = System.currentTimeMillis() - headerStart;

			if (headersToSync.isEmpty()) {
				// If peer has much higher height but sends nothing, they found no common
				// ancestor
				// This implies an incompatible chain (different genesis or hard fork)
				if (peer.getHeadHeight() > localBest.getHeight()) {
					throw new IncompatibleChainException("Peer claimed height " + peer.getHeadHeight()
							+ " (local: " + localBest.getHeight()
							+ ") but sent no headers. Likely no common ancestor found.");
				}

				log.debug("No new headers found from peer");
				return true;
			}

			log.info("Downloaded {} headers in {}ms", headersToSync.size(), headerTime);

			// Validate headers in parallel AND warm up lazy getters (hash, size)
			// BlockHeaderImpl caches these values internally after first call
			long validateStart = System.currentTimeMillis();
			Map<Hash, StatelessValidatedHeader> validatedHeaders = validateBatch(headersToSync);
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
			return true;
		} catch (IncompatibleChainException e) {
			// Peer is on a fundamentally different chain (different genesis or hard fork)
			// Ban them permanently as they'll never be useful to us
			log.warn("INCOMPATIBLE CHAIN: Banning peer {} - {}", peer.getIdentity(), e.getMessage());
			peer.disconnect("Incompatible chain: " + e.getMessage());
			peerReputationService.ban(peer.getIdentity());
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
		}
	}

	/**
	 * Downloads block bodies and persists them in batches of PERSIST_BATCH_SIZE.
	 * This limits RAM usage while maintaining efficient network and disk I/O.
	 * 
	 * @return Total number of blocks processed
	 */
	private int downloadAndPersistBodiesInBatches(RemotePeer peer, List<BlockHeader> headers,
			Map<Hash, StatelessValidatedHeader> validatedHeaders) throws Exception {
		if (headers.isEmpty())
			return 0;

		int totalProcessed = 0;
		List<ValidatedSyncBlock> currentBatch = new ArrayList<>(PERSIST_BATCH_SIZE);

		Hash firstParentHash = headers.get(0).getPreviousHash();
		StoredBlock commonAncestor = chainQueryService.getStoredBlockByHashOrThrow(firstParentHash);
		BigInteger currentCumulativeDifficulty = commonAncestor.getCumulativeDifficulty();

		// Pipeline configuration
		final int bodyBatchSize = calculateBodyBatchSize();
		final int pipelineDepth = calculatePipelineDepth(bodyBatchSize);
		List<PendingBodyRequest> pendingRequests = new ArrayList<>();
		int nextBatchIndex = 0;

		while (nextBatchIndex < headers.size() || !pendingRequests.isEmpty()) {
			// Send new requests up to pipeline depth
			while (pendingRequests.size() < pipelineDepth && nextBatchIndex < headers.size()) {
				int startIdx = nextBatchIndex;
				int endIdx = Math.min(nextBatchIndex + bodyBatchSize, headers.size());
				List<BlockHeader> batchHeaders = headers.subList(startIdx, endIdx);
				List<Hash> hashes = batchHeaders.stream().map(BlockHeader::getHash).collect(Collectors.toList());

				CompletableFuture<List<List<Tx>>> future = new CompletableFuture<>();
				long reqId = peer.reserveRequestId();
				pendingBodyRequests.put(reqId, future);
				recordBodyRequest();
				peer.sendGetBlockBodies(hashes, reqId);

				pendingRequests.add(new PendingBodyRequest(reqId, batchHeaders, future, startIdx));
				nextBatchIndex = endIdx;
			}

			if (pendingRequests.isEmpty())
				break;

			// Wait for the oldest request in the pipeline
			PendingBodyRequest oldest = pendingRequests.remove(0);
			try {
				List<List<Tx>> bodies = oldest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

				if (bodies == null || bodies.isEmpty()) {
					throw new GEValidationException("Empty body response from peer");
				}

				if (bodies.size() != oldest.batchHeaders.size()) {
					throw new GEValidationException("Mismatch body count from peer: got " + bodies.size()
							+ ", expected " + oldest.batchHeaders.size());
				}

				for (int j = 0; j < bodies.size(); j++) {
					BlockHeader header = oldest.batchHeaders.get(j);
					Long height = header.getHeight();
					List<Tx> txs = bodies.get(j);

					Block block = BlockImpl.builder()
							.header(header)
							.txs(txs)
							.build();
					// Header PoW/signature/structure was validated in validateBatch(); the
					// body must still pass the shared size/count/Merkle/TX gate.
					StatelessValidatedHeader validatedHeader = Optional
							.ofNullable(validatedHeaders.get(header.getHash()))
							.orElseThrow(() -> new GEValidationException(
									"Missing stateless header proof at height " + height));
					StatelessValidatedBlock validatedBlock = blockValidationService
							.validateBlockBody(block, validatedHeader);
					block = validatedBlock.block();

					currentCumulativeDifficulty = currentCumulativeDifficulty.add(header.getDifficulty());

					Address minerIdentity = block.getHeader().getIdentity();
					if (minerIdentity == null) {
						throw new GEValidationException("Critical: Header identity is null for block " + height);
					}
					Address peerIdentity = peer.getIdentity();
					if (peerIdentity == null) {
						throw new GEValidationException("Critical: Peer identity is null during sync");
					}

					StoredBlock storedBlock = StoredBlock.builder()
							.block(block)
							.cumulativeDifficulty(currentCumulativeDifficulty)
							.identity(minerIdentity)
							.receivedAt(block.getHeader().getTimestamp())
							.receivedFrom(peerIdentity)
							.connectedSource(ConnectedSource.REORG)
							.computeIndexes()
							.build();

					currentBatch.add(new ValidatedSyncBlock(storedBlock, validatedBlock));

					// Persist batch when full
					if (currentBatch.size() >= PERSIST_BATCH_SIZE) {
						persistBatch(commonAncestor, currentBatch);
						totalProcessed += currentBatch.size();
						// Update ancestor for next batch
						commonAncestor = currentBatch.get(currentBatch.size() - 1).storedBlock();
						currentBatch.clear();
					}
				}

			} catch (Exception e) {
				pendingBodyRequests.remove(oldest.reqId);
				for (PendingBodyRequest req : pendingRequests) {
					pendingBodyRequests.remove(req.reqId);
				}
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

	/**
	 * Persists a batch of blocks atomically.
	 */
	private void persistBatch(StoredBlock commonAncestor, List<ValidatedSyncBlock> blocks) throws Exception {
		if (blocks.isEmpty())
			return;

		masterChainLock.lock();
		try {
			long start = System.currentTimeMillis();
			blockReorgService.executeAtomicReorgSwap(new ValidatedSyncBatch(commonAncestor, blocks));
			long elapsed = System.currentTimeMillis() - start;
			log.info("Persisted batch of {} blocks (heights {}-{}) in {}ms",
					blocks.size(),
					blocks.get(0).storedBlock().getHeight(),
					blocks.get(blocks.size() - 1).storedBlock().getHeight(),
					elapsed);
		} finally {
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
			pendingHeaderRequests.put(reqId, future);
			recordHeaderRequest();
			int remaining = SYNC_CHUNK_SIZE_HEADERS - allHeaders.size();

			long sendStart = System.currentTimeMillis();
			peer.sendGetBlockHeaders(currentLocators, stopHash, remaining, reqId);
			log.debug("Sent GetHeaders request {} for {} headers from height {}", reqId, remaining,
					localBest.getHeight());

			try {
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
				pendingHeaderRequests.remove(reqId);
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

	private record PendingBodyRequest(long reqId, List<BlockHeader> batchHeaders,
			CompletableFuture<List<List<Tx>>> future, int startIndex) {
	}

	// --- EVENT LISTENERS ---

	@EventListener
	public void onHeadersReceived(P2PHeadersReceivedEvent event) {
		long reqId = event.getRequestId();
		CompletableFuture<List<BlockHeader>> future = pendingHeaderRequests.remove(reqId);

		if (future != null) {
			future.complete(event.getHeaders());
		} else if (reqId == 0 && event.getHeaders().size() == 1) {
			handleBroadcastHeader(event.getPeer(), event.getHeaders().get(0));
		} else {
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
			pendingBodyRequests.put(reqId, future);

			log.debug("Headers-First: Requesting body for #{} from {}", header.getHeight(), peer.getIdentity());

			peer.sendGetBlockBodies(new ArrayList<>(List.of(headerHash)), reqId);

			future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.thenAcceptAsync(bodies -> {
						pendingBodyRequests.remove(reqId);

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
						pendingBodyRequests.remove(reqId);
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
		CompletableFuture<List<List<Tx>>> future = pendingBodyRequests.remove(event.getRequestId());
		if (future != null) {
			future.complete(event.getBodies());
		} else {
			log.debug("Received bodies with ID {} but no pending request found (Timed out?)", event.getRequestId());
		}
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

	private boolean isPeerFaultRejection(BlockIngestionOutcome outcome) {
		return switch (outcome.code()) {
			case REJECTED_STATELESS, REJECTED_CONTEXTUAL, REJECTED_CONSENSUS_POLICY,
					REJECTED_EXECUTION, REJECTED_STATE_ROOT -> true;
			case ACCEPTED, ORPHAN_BUFFERED, GAP_DETECTED, ALREADY_EXISTS, INTERNAL_FAILURE -> false;
		};
	}
}
