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
package global.goldenera.node.core.mining;

import static global.goldenera.node.core.config.CoreAsyncConfig.BLOCK_MINING_EXECUTOR;
import static global.goldenera.node.core.config.CoreAsyncConfig.MINER_THREAD_FACTORY;
import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockMinedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.core.exceptions.GETxValidationFailedException;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.randomx.RandomXVM;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@FieldDefaults(level = PRIVATE)
@Slf4j
public class MiningService {

	final ReentrantLock masterChainLock;
	final AtomicBoolean isMining = new AtomicBoolean(false);
	final int hashingThreads;

	final MeterRegistry registry;
	final ApplicationEventPublisher applicationEventPublisher;
	final ExecutorService blockMiningExecutor;

	// Worker pool for parallel hashing (re-used)
	volatile ExecutorService blockHashingWorker;
	// Reference to the main mining loop thread for interruption
	volatile Thread miningThread;

	final MiningBlockAssemblerService miningBlockAssemblerService;
	final IdentityService identityService;
	final MempoolManager mempoolService;
	final ChainQuery chainQueryService;
	final MiningProperties miningConfig;
	final RandomXManager randomXService;
	final ThreadFactory minerThreadFactory;

	public MiningService(
			MeterRegistry registry,
			@Qualifier("masterChainLock") ReentrantLock masterChainLock,
			ApplicationEventPublisher applicationEventPublisher,
			@Qualifier(BLOCK_MINING_EXECUTOR) ExecutorService blockMiningExecutor,
			MiningBlockAssemblerService miningBlockAssemblerService,
			IdentityService identityService,
			MempoolManager mempoolService,
			ChainQuery chainQueryService,
			MiningProperties miningConfig,
			RandomXManager randomXService,
			@Qualifier(MINER_THREAD_FACTORY) ThreadFactory minerThreadFactory) {
		this.registry = registry;
		this.masterChainLock = masterChainLock;
		this.applicationEventPublisher = applicationEventPublisher;
		this.blockMiningExecutor = blockMiningExecutor;
		this.miningBlockAssemblerService = miningBlockAssemblerService;
		this.identityService = identityService;
		this.mempoolService = mempoolService;
		this.chainQueryService = chainQueryService;
		this.miningConfig = miningConfig;
		this.randomXService = randomXService;
		this.minerThreadFactory = minerThreadFactory;
		this.hashingThreads = getHashingThreads();
		log.info("Mining initialized with {} hashing threads", this.hashingThreads);
	}

	@PreDestroy
	public void stopMining() {
		if (isMining.compareAndSet(true, false)) {
			log.info("Stopping mining service...");

			// Interrupt main loop
			if (miningThread != null) {
				if (randomXService.isInitializationInProgress()) {
					log.info("RandomX initialization in progress. Waiting for it to complete to avoid native crash...");
				} else {
					miningThread.interrupt();
				}
			}

			// Shutdown hashing workers
			if (blockHashingWorker != null) {
				blockHashingWorker.shutdownNow();
			}

			// Shutdown orchestration executor
			blockMiningExecutor.shutdownNow();

			try {
				if (!blockMiningExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
					log.warn("Mining executor did not terminate in time.");
				}
				if (blockHashingWorker != null && !blockHashingWorker.awaitTermination(5, TimeUnit.SECONDS)) {
					log.warn("Hashing workers did not terminate in time.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			log.info("Mining service stopped gracefully.");
		}
	}

	/**
	 * Pauses mining (stops the loop) but keeps the main executor alive.
	 * Used during synchronization.
	 */
	public void pauseMining() {
		if (isMining.compareAndSet(true, false)) {
			log.info("Pausing mining service...");

			// Interrupt main loop
			if (miningThread != null) {
				miningThread.interrupt();
			}

			// Shutdown hashing workers to free up CPU for Sync
			if (blockHashingWorker != null) {
				blockHashingWorker.shutdownNow();
			}
			// We do NOT shutdown blockMiningExecutor here, so it can be reused.
		}
	}

	/**
	 * Resumes mining (alias for startMining).
	 * Used during synchronization.
	 */
	public void resumeMining() {
		startMining();
	}

	public void startMining() {
		if (!miningConfig.getEnable()) {
			return;
		}

		if (isMining.compareAndSet(false, true)) {
			log.info("Mining started");
			// Initialize worker pool if needed
			if (blockHashingWorker == null || blockHashingWorker.isShutdown()) {
				blockHashingWorker = Executors.newFixedThreadPool(hashingThreads, minerThreadFactory);
			}

			blockMiningExecutor.submit(() -> {
				try {
					runMiningLoop();
				} catch (Exception e) {
					log.error("Mining loop crashed fatally", e);
				}
			});
		}
	}

	/**
	 * Interrupts the current PoW search when a new block is received via P2P.
	 */
	public void stopCurrentNonceSearch() {
		if (isMining.get() && miningThread != null) {
			log.debug("Interrupting current mining job (New Block / Event)");
			miningThread.interrupt();
		}
	}

	private void runMiningLoop() {
		miningThread = Thread.currentThread();

		while (isMining.get()) {
			try {
				// 0. Check Interrupt Status
				if (Thread.currentThread().isInterrupted()) {
					if (!isMining.get())
						break; // Shutdown
					Thread.interrupted(); // Clear status for new job
				}

				// 1. Get Latest Block (Parent)
				Block parentBlock;
				masterChainLock.lock();
				try {
					parentBlock = chainQueryService.getLatestBlockOrThrow();
				} finally {
					masterChainLock.unlock();
				}

				// 2. Assemble Block Candidate
				MiningBlockAssemblerService.AssembledBlock assembledBlock = miningBlockAssemblerService
						.createBlockTemplate(parentBlock);

				// Cleanup invalid TXs from mempool if assembler found them
				if (assembledBlock.getInvalidTxs() != null && !assembledBlock.getInvalidTxs().isEmpty()) {
					List<Hash> invalidHashes = assembledBlock.getInvalidTxs().stream()
							.map(Tx::getHash)
							.collect(Collectors.toList());
					mempoolService.removeTransactions(invalidHashes);
				}

				MiningBlockAssemblerService.BlockHeaderTemplate template = assembledBlock.getBlockTemplate();

				// 3. Ensure RandomX is ready for this height
				// This might throw InterruptedException if shutdown occurs during dataset init
				randomXService.ensureInitializedForHeight(template.getHeight());

				if (!isMining.get())
					break;

				// 4. Calculate Target
				BigInteger target = DifficultyUtil.calculateTargetFromDifficulty(template.getDifficulty());

				log.debug("Mining block {} | Diff: {} | TargetPrefix: {}...",
						template.getHeight(),
						template.getDifficulty(),
						target.toString(16).substring(0, Math.min(10, target.toString(16).length())));

				// 5. Start Hashing
				long nonceStart = System.currentTimeMillis();
				Long foundNonce = findNonce(template, target);
				long nonceEnd = System.currentTimeMillis();

				// 6. Process Result
				registry.timer("mining.cycle_time").record(Duration.ofMillis(nonceEnd - nonceStart));

				if (foundNonce != null) {
					registry.counter("mining.blocks_found").increment();
					processMinedBlock(template, assembledBlock, foundNonce, nonceEnd - nonceStart);
				} else {
					log.debug("Nonce search stopped (interrupted or no result).");
				}

			} catch (GETxValidationFailedException e) {
				log.warn("Tx validation failed during mining: {}", e.getMessage());
				mempoolService.removeTransaction(e.getFailedTx().getHash());
			} catch (InterruptedException e) {
				log.info("Mining loop interrupted.");
				Thread.currentThread().interrupt();
				if (!isMining.get())
					break;
			} catch (Exception e) {
				if (!isMining.get())
					break;

				log.error("Error in mining loop, pausing 5s:", e);
				try {
					Thread.sleep(5000);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		miningThread = null;
	}

	private Long findNonce(MiningBlockAssemblerService.BlockHeaderTemplate template, BigInteger target) {

		if (blockHashingWorker == null || blockHashingWorker.isShutdown()) {
			blockHashingWorker = Executors.newFixedThreadPool(hashingThreads, minerThreadFactory);
		}

		// Optimization: Convert target to byte array once to avoid BigInteger creation
		// in loop
		final byte[] targetBytes = new byte[32];
		byte[] rawTarget = target.toByteArray();
		if (rawTarget.length > 32) {
			System.arraycopy(rawTarget, rawTarget.length - 32, targetBytes, 0, 32);
		} else {
			System.arraycopy(rawTarget, 0, targetBytes, 32 - rawTarget.length, rawTarget.length);
		}

		AtomicReference<Long> foundNonce = new AtomicReference<>();
		List<Callable<Void>> tasks = new ArrayList<>(hashingThreads);
		// Range splitting
		final long chunkSize = (Long.MAX_VALUE / hashingThreads);

		for (int i = 0; i < hashingThreads; i++) {
			final long start = (long) i * chunkSize;
			final long end = (i == hashingThreads - 1) ? Long.MAX_VALUE : (start + chunkSize);

			// Optimization: Avoid Tuweni Bytes wrapper in inner loop
			final byte[] baseHeader = BlockHeaderUtil.powInput(template.toBlockHeader());

			tasks.add(() -> {
				// Each thread gets its own VM
				try (RandomXVM vm = randomXService.createMiningVM()) {
					// Optimization: Direct buffer manipulation
					byte[] workBuffer = new byte[baseHeader.length];
					System.arraycopy(baseHeader, 0, workBuffer, 0, baseHeader.length);
					int nonceOffset = workBuffer.length - 8;

					long currentNonce = start;
					int batchCounter = 0;

					while (currentNonce < end) {
						// Optimization: Bitwise check is faster than modulo
						if ((++batchCounter & 0xFFF) == 0) {
							if (foundNonce.get() != null || Thread.currentThread().isInterrupted())
								return null;
						}

						// Write Nonce (Big Endian)
						workBuffer[nonceOffset] = (byte) (currentNonce >>> 56);
						workBuffer[nonceOffset + 1] = (byte) (currentNonce >>> 48);
						workBuffer[nonceOffset + 2] = (byte) (currentNonce >>> 40);
						workBuffer[nonceOffset + 3] = (byte) (currentNonce >>> 32);
						workBuffer[nonceOffset + 4] = (byte) (currentNonce >>> 24);
						workBuffer[nonceOffset + 5] = (byte) (currentNonce >>> 16);
						workBuffer[nonceOffset + 6] = (byte) (currentNonce >>> 8);
						workBuffer[nonceOffset + 7] = (byte) (currentNonce);

						byte[] hashBytes = vm.calculateHash(workBuffer);

						// Optimization: Byte comparison instead of BigInteger
						if (isHashLessThanOrEqual(hashBytes, targetBytes)) {
							foundNonce.set(currentNonce);
							return null;
						}

						currentNonce++;
					}
				} catch (Exception e) {
					log.error("Worker error: {}", e.getMessage());
				}
				return null;
			});
		}

		try {
			// Optimization: invokeAll allows threads to finish gracefully via shared flag
			blockHashingWorker.invokeAll(tasks);
		} catch (InterruptedException e) {
			// Main thread interrupted (e.g. new block found)
			return null;
		} catch (java.util.concurrent.RejectedExecutionException e) {
			// Executor shutdown
			return null;
		}

		return foundNonce.get();
	}

	private static boolean isHashLessThanOrEqual(byte[] hash, byte[] target) {
		for (int i = 0; i < 32; i++) {
			int h = hash[i] & 0xFF;
			int t = target[i] & 0xFF;
			if (h < t)
				return true;
			if (h > t)
				return false;
		}
		return true;
	}

	private void processMinedBlock(MiningBlockAssemblerService.BlockHeaderTemplate template,
			MiningBlockAssemblerService.AssembledBlock assembledBlock,
			Long nonce, double durationMs) {

		masterChainLock.lock();
		try {
			Block currentTip = chainQueryService.getLatestBlockOrThrow();

			// Check if we are still on the correct parent
			if (template.getPreviousHash().equals(currentTip.getHash())) {
				log.info("SUCCESS: Block mined #{} in {}s (Nonce: {})",
						template.getHeight(), String.format("%.2f", durationMs / 1000.0), nonce);

				// 1. Construct Header with the FOUND Nonce
				BlockHeaderImpl headerForSigning = BlockHeaderImpl.builder()
						.version(template.getVersion())
						.height(template.getHeight())
						.timestamp(template.getTimestamp())
						.previousHash(template.getPreviousHash())
						.difficulty(template.getDifficulty())
						.coinbase(template.getCoinbase())
						.txRootHash(template.getTxRootHash())
						.stateRootHash(template.getStateRootHash())
						.nonce(nonce)
						.build();

				// 2. Sign
				Hash hashForSigning = BlockHeaderUtil.hashForSigning(headerForSigning);
				Signature signature = identityService.getPrivateKey().sign(hashForSigning);

				// 3. Create Final Block
				BlockImpl foundBlock = BlockImpl.builder()
						.header(headerForSigning.toBuilder().signature(signature).build())
						.txs(assembledBlock.getTxs())
						.build();

				// 4. Publish
				applicationEventPublisher.publishEvent(new BlockMinedEvent(this, foundBlock));
			} else {
				log.warn("STALE: Block mined but chain moved (Target: {} -> Tip: {})",
						template.getPreviousHash().toShortLogString(),
						currentTip.getHash().toShortLogString());
			}
		} finally {
			masterChainLock.unlock();
		}
	}

	@EventListener
	public void onNewBlockConnected(BlockConnectedEvent event) {
		// Stop current job immediately to start working on the new block
		if (!isMining.get())
			return;
		stopCurrentNonceSearch();
	}

	private int getHashingThreads() {
		Integer configured = miningConfig.getHashingThreads();
		if (configured != null && configured > 0)
			return configured;
		return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
	}
}