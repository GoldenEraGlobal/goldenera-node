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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockMinedEvent;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PBlockReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PHeadersReceivedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEValidationException;
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

	static final int SYNC_CHUNK_SIZE_HEADERS = 100;
	static final int BODY_DOWNLOAD_BATCH_SIZE = 4;
	static final long TIMEOUT_SECONDS = 30;

	final MeterRegistry registry;
	final ReentrantLock masterChainLock;
	final Executor coreTaskExecutor;

	final MiningService miningService;
	final IdentityService identityService;

	final BlockValidator blockValidationService;
	final TxValidator txValidationService;

	final ChainQuery chainQueryService;
	final BlockRepository blockRepository;
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
			TxValidator txValidationService,
			ChainQuery chainQueryService,
			BlockRepository blockRepository,
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
		this.txValidationService = txValidationService;
		this.chainQueryService = chainQueryService;
		this.blockRepository = blockRepository;
		this.blockReorgService = blockReorgService;
		this.peerRegistry = peerRegistry;
		this.peerReputationService = peerReputationService;
		this.blockIngestionService = blockIngestionService;
	}

	final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Sync-Manager"));
	final AtomicBoolean isRunning = new AtomicBoolean(false);

	@Getter
	volatile boolean synced = false;

	final BlockingQueue<Object> signalQueue = new LinkedBlockingQueue<>();

	final Map<Long, CompletableFuture<List<BlockHeader>>> pendingHeaderRequests = new ConcurrentHashMap<>();
	final Map<Long, CompletableFuture<List<List<Tx>>>> pendingBodyRequests = new ConcurrentHashMap<>();

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
				signalQueue.poll(10, TimeUnit.SECONDS);
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
			Block localBest = chainQueryService.getLatestBlockOrThrow();
			Optional<RemotePeer> bestPeerOpt = peerRegistry.getSyncCandidate(localBest.getHeight());
			if (bestPeerOpt.isEmpty()) {
				if (!synced) {
					log.info("Node synced at height {}", localBest.getHeight());
					synced = true;
				}
				miningService.resumeMining();
				return;
			}
			RemotePeer bestPeer = bestPeerOpt.get();
			if (bestPeer.getHeadHeight() > localBest.getHeight()) {
				log.info("Sync needed: local height {} vs peer height {} ({})", localBest.getHeight(),
						bestPeer.getHeadHeight(), bestPeer.getIdentity());
				synced = false;

				boolean success = performSync(bestPeer, localBest);

				if (success) {
					signalQueue.offer(new Object());
				}
			} else {
				if (!synced) {
					log.info("Node synced at height {}", localBest.getHeight());
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
		try {
			log.debug("Starting sync with peer {}", peer.getIdentity());
			List<BlockHeader> headersToSync = downloadHeaders(peer, localBest);
			if (headersToSync.isEmpty()) {
				log.debug("No new headers found from peer");
				return true;
			}
			List<StoredBlock> newChainBlocks = downloadAndPersistBodies(peer, headersToSync);

			if (!newChainBlocks.isEmpty()) {
				miningService.pauseMining();
				StoredBlock commonAncestor = chainQueryService
						.getStoredBlockByHashOrThrow(headersToSync.get(0).getPreviousHash());
				masterChainLock.lock();
				try {
					blockReorgService.executeAtomicReorgSwap(commonAncestor, newChainBlocks);
					log.info("Sync batch completed, new tip at height {}",
							newChainBlocks.get(newChainBlocks.size() - 1).getBlock().getHeight());
				} finally {
					masterChainLock.unlock();
				}
			}

			peerReputationService.recordSuccess(peer.getIdentity());
			registry.counter("blockchain.sync.blocks_downloaded").increment(newChainBlocks.size());
			return true;
		} catch (Exception e) {
			log.warn("Sync failed with peer {}: {}", peer.getIdentity(), e.getMessage());
			peer.disconnect("Sync failed: " + e.getMessage());
			peerReputationService.recordFailure(peer.getIdentity());
			return false;
		} finally {
			sample.stop(registry.timer("blockchain.sync.batch_time"));
		}
	}

	private List<BlockHeader> downloadHeaders(RemotePeer peer, Block localBest) throws Exception {
		List<BlockHeader> allHeaders = new ArrayList<>();
		Hash stopHash = peer.getHeadHash();
		List<Hash> currentLocators = new ArrayList<>(chainQueryService.getLocatorHashes());

		while (allHeaders.size() < SYNC_CHUNK_SIZE_HEADERS) {
			CompletableFuture<List<BlockHeader>> future = new CompletableFuture<>();
			long reqId = peer.reserveRequestId();
			pendingHeaderRequests.put(reqId, future);
			peer.sendGetBlockHeaders(currentLocators, stopHash, 100, reqId);
			try {
				List<BlockHeader> batch = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				if (batch == null || batch.isEmpty())
					break;

				Hash expectedPrev = (allHeaders.isEmpty()) ? null
						: allHeaders.get(allHeaders.size() - 1).getHash();
				for (BlockHeader h : batch) {
					if (expectedPrev != null && !h.getPreviousHash().equals(expectedPrev)) {
						throw new GEValidationException("Broken header linkage");
					}
					expectedPrev = h.getHash();
				}

				allHeaders.addAll(batch);

				BlockHeader lastHeader = batch.get(batch.size() - 1);
				if (lastHeader.getHash().equals(stopHash) || batch.size() < 100)
					break;

				currentLocators.clear();
				currentLocators.add(lastHeader.getHash());

			} catch (Exception e) {
				pendingHeaderRequests.remove(reqId);
				throw e;
			}
		}

		// Validate the entire sequence of headers we just downloaded
		// We do this here because we have the full context (allHeaders)
		if (!allHeaders.isEmpty()) {
			validateBatch(allHeaders);
		}

		return allHeaders;
	}

	private void validateBatch(List<BlockHeader> headers) {
		Map<Long, Hash> contextMap = new java.util.HashMap<>();
		for (BlockHeader h : headers) {
			contextMap.put(h.getHeight(), h.getHash());
		}

		for (BlockHeader h : headers) {
			blockValidationService.validateHeader(h, contextMap);
		}
	}

	private List<StoredBlock> downloadAndPersistBodies(RemotePeer peer, List<BlockHeader> headers) throws Exception {
		List<StoredBlock> allDownloadedBlocks = new ArrayList<>();
		if (headers.isEmpty())
			return allDownloadedBlocks;

		Hash firstParentHash = headers.get(0).getPreviousHash();
		BigInteger currentCumulativeDifficulty = chainQueryService.getStoredBlockByHashOrThrow(firstParentHash)
				.getCumulativeDifficulty();

		for (int i = 0; i < headers.size(); i += BODY_DOWNLOAD_BATCH_SIZE) {
			List<BlockHeader> batchHeaders = headers.subList(i, Math.min(i + BODY_DOWNLOAD_BATCH_SIZE, headers.size()));
			List<Hash> hashes = batchHeaders.stream().map(h -> h.getHash()).collect(Collectors.toList());

			CompletableFuture<List<List<Tx>>> future = new CompletableFuture<>();

			long reqId = peer.reserveRequestId();
			pendingBodyRequests.put(reqId, future);

			peer.sendGetBlockBodies(hashes, reqId);

			try {
				List<List<Tx>> bodies = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

				if (bodies == null || bodies.size() != batchHeaders.size()) {
					throw new GEValidationException("Mismatch body count from peer");
				}

				for (int j = 0; j < batchHeaders.size(); j++) {
					BlockHeader header = batchHeaders.get(j);
					Long height = header.getHeight();
					List<Tx> txs = bodies.get(j);

					Hash calculatedRoot = TxRootUtil.txRootHash(txs);
					if (!calculatedRoot.equals(header.getTxRootHash())) {
						throw new GEValidationException("Invalid Merkle Root at height " + height);
					}

					txs.parallelStream().forEach(tx -> txValidationService.validateStateless(tx));
					currentCumulativeDifficulty = currentCumulativeDifficulty.add(header.getDifficulty());

					Block block = BlockImpl.builder()
							.header(header)
							.txs(txs)
							.build();

					StoredBlock storedBlock = StoredBlock.builder()
							.block(block)
							.cumulativeDifficulty(currentCumulativeDifficulty)
							.receivedAt(block.getHeader().getTimestamp())
							.receivedFrom(peer.getIdentity())
							.connectedSource(ConnectedSource.REORG)
							.build();

					allDownloadedBlocks.add(storedBlock);
				}

			} catch (Exception e) {
				pendingBodyRequests.remove(reqId);
				throw e;
			}
		}

		if (!allDownloadedBlocks.isEmpty()) {
			blockRepository.executeAtomicBatch(batch -> {
				for (StoredBlock sb : allDownloadedBlocks) {
					blockRepository.saveBlockDataToBatch(batch, sb);
				}
			});
		}

		return allDownloadedBlocks;
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
		if (chainQueryService.getStoredBlockByHash(header.getHash()).isPresent()) {
			return;
		}
		if (blockIngestionService.isOrphan(header.getHash())) {
			return;
		}
		try {
			Block localBest = chainQueryService.getLatestBlockOrThrow();

			if (header.getHeight() > localBest.getHeight() ||
					chainQueryService.getStoredBlockByHash(header.getPreviousHash()).isEmpty()) {

				CompletableFuture<List<List<Tx>>> future = new CompletableFuture<>();

				long reqId = peer.reserveRequestId();
				pendingBodyRequests.put(reqId, future);

				log.debug("Headers-First: Requesting body for #{} from {}", header.getHeight(), peer.getIdentity());

				peer.sendGetBlockBodies(new ArrayList<>(List.of(header.getHash())), reqId);

				future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.thenAcceptAsync(bodies -> {
							pendingBodyRequests.remove(reqId);

							if (bodies == null || bodies.isEmpty()) {
								log.warn("Peer {} sent empty body response for #{}", peer.getIdentity(),
										header.getHeight());
								return;
							}

							List<Tx> txs = bodies.get(0);
							if (!TxRootUtil.txRootHash(txs).equals(header.getTxRootHash())) {
								log.warn("Invalid Merkle Root for broadcast block #{} from {}", header.getHeight(),
										peer.getIdentity());
								peerReputationService.recordFailure(peer.getIdentity());
								return;
							}

							Block block = BlockImpl.builder()
									.header(header)
									.txs(txs)
									.build();

							blockIngestionService.processBlock(
									block,
									ConnectedSource.BROADCAST,
									peer.getIdentity(),
									Instant.now());

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
						});
			}
		} catch (Exception e) {
			log.error("Failed to handle broadcast header", e);
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
		blockValidationService.validateFullBlock(event.getBlock());
		BlockIngestionService.IngestionResult result = blockIngestionService.processBlock(
				event.getBlock(), ConnectedSource.BROADCAST, event.getPeer().getIdentity(), Instant.now());

		if (result == BlockIngestionService.IngestionResult.GAP_DETECTED) {
			log.debug("Gap detected from broadcast, triggering sync");
			signalQueue.offer(new Object());
		}
	}

	@EventListener
	public void onBlockMined(BlockMinedEvent event) {
		long startTime = System.currentTimeMillis();
		blockIngestionService.processBlock(event.getBlock(), ConnectedSource.MINER,
				identityService.getNodeIdentityAddress(),
				event.getBlock().getHeader().getTimestamp());
		log.debug("Block mined and processed | Time: {}s",
				String.format("%.2f", (System.currentTimeMillis() - startTime) / 1000.0));
	}
}