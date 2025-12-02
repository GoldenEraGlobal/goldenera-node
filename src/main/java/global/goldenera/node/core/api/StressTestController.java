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
package global.goldenera.node.core.api;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.exceptions.CryptoJException;
import global.goldenera.cryptoj.utils.Amounts;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.shared.consensus.state.AccountNonceState;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/core/v1/mempool/stress-test")
@RequiredArgsConstructor
public class StressTestController {

	final WorldStateFactory worldStateFactory;
	final ChainQuery chainQueryService;
	private final MempoolManager mempoolManager;
	private final MiningService miningService;

	final Executor stressTestExecutor = Executors.newFixedThreadPool(4);

	// --- Configuration ---
	private static final int TX_COUNT = 35000;
	private static final StressTestContext context = new StressTestContext();

	private static final String RICH_WALLET_MNEMONIC = "message remember stamp keep domain vacuum brick drift master recycle lion enemy";
	private static PrivateKey RICH_KEY;
	private static Address RICH_ADDRESS;

	static {
		try {
			RICH_KEY = PrivateKey.load(RICH_WALLET_MNEMONIC, null);
			RICH_ADDRESS = RICH_KEY.getAddress();
		} catch (CryptoJException e) {
			e.printStackTrace();
		}
	}

	@PostMapping("/step-1")
	public StressTestResult startStep1() {
		log.info("Starting Stress Test Step 1: Fan-out from {}", RICH_ADDRESS.toHexString());
		context.pendingTxs.clear();
		context.step1Recipients.clear();
		// miningService.stopMining();
		resetStats("STEP_1");

		Block latestBlock = chainQueryService.getLatestBlockOrThrow();
		WorldState latestState = worldStateFactory.createForValidation(latestBlock.getHeader().getStateRootHash());
		AccountNonceState startNonceData = latestState.getNonce(RICH_ADDRESS);
		long startNonce = startNonceData.getNonce() + 1;

		context.step1Recipients = new ArrayList<>(TX_COUNT);

		log.info("Generating keys and transactions...");
		Instant startGen = Instant.now();

		List<KeyTxPair> transactions = IntStream.range(0, TX_COUNT).parallel()
				.mapToObj(i -> {
					try {
						PrivateKey newWallet = PrivateKey.create();
						return new AbstractMap.SimpleEntry<>(newWallet, i);
					} catch (CryptoJException e) {
						e.printStackTrace();
						return null;
					}
				})
				.filter(entry -> entry != null)
				.map(entry -> {
					PrivateKey recipientKey = entry.getKey();
					long offset = entry.getValue();
					long nonce = startNonce + offset;
					try {
						Tx tx = TxBuilder.create()
								.type(TxType.TRANSFER)
								.network(Network.TESTNET)
								.sender(RICH_ADDRESS)
								.recipient(recipientKey.getAddress())
								.amount(Amounts.tokensDecimal("2.0"))
								.fee(Amounts.tokensDecimal("0.001"))
								.nonce(nonce)
								.sign(RICH_KEY);
						return new KeyTxPair(recipientKey, tx);
					} catch (CryptoJException e) {
						e.printStackTrace();
						return null;
					}
				})
				.filter(pair -> pair != null)
				.collect(Collectors.toList());

		context.step1Recipients = transactions.stream().map(KeyTxPair::key).collect(Collectors.toList());

		log.info("Generation finished in {} ms. Submitting to mempool...",
				Duration.between(startGen, Instant.now()).toMillis());

		// --- MEMPOOL MEASUREMENT (Insertion time) ---
		context.startTimeMempool = Instant.now();

		// We can keep parallel here, since it's one sender account,
		// locks will block anyway, but overhead is not as big as with 35k senders.
		transactions.parallelStream().forEach(pair -> {
			Tx tx = pair.tx();
			context.pendingTxs.add(tx.getHash().toHexString());
			mempoolManager.addTx(tx);
			context.mempoolCount.incrementAndGet();
		});

		context.endTimeMempool = Instant.now();
		log.info("Mempool submission finished.");

		// --- BLOCKCHAIN MEASUREMENT (Pure mining time) ---
		// Start timer NOW, when everything is ready in mempool.
		context.startTimeBlockchain = Instant.now();
		/// miningService.startMining();

		return getResults();
	}

	@PostMapping("/step-2")
	public StressTestResult startStep2() {
		if (context.step1Recipients == null || context.step1Recipients.isEmpty()) {
			throw new RuntimeException("Run Step 1 first to generate wallets.");
		}

		// miningService.stopMining();
		mempoolManager.clear();

		log.info("Starting Stress Test Step 2: 1-to-1 Transfers");
		resetStats("STEP_2");

		Address sampleAddress = context.step1Recipients.get(0).getAddress();
		Block latestBlock = chainQueryService.getLatestBlockOrThrow();
		WorldState state = worldStateFactory.createForValidation(latestBlock.getHeader().getStateRootHash());
		if (state.getBalance(sampleAddress, Address.NATIVE_TOKEN).getBalance().isZero()) {
			throw new RuntimeException("Step 1 funds are not mined yet! Wait for blocks to be confirmed.");
		}

		log.info("Building transactions from {} wallets...", context.step1Recipients.size());

		// 1. Build & Warm-up
		List<Tx> transactions = context.step1Recipients.parallelStream()
				.map(senderKey -> {
					try {
						Address randomRecipient = PrivateKey.create().getAddress();
						Tx tx = TxBuilder.create()
								.type(TxType.TRANSFER)
								.network(Network.TESTNET)
								.sender(senderKey.getAddress())
								.recipient(randomRecipient)
								.amount(Amounts.tokensDecimal("1.0"))
								.fee(Amounts.tokensDecimal("0.001"))
								.nonce(0L)
								.sign(senderKey);
						tx.getSender(); // warm-up
						tx.getHash(); // warm-up
						return tx;
					} catch (Exception e) {
						return null;
					}
				})
				.filter(tx -> tx != null)
				.collect(Collectors.toList());

		log.info("Submitting {} transactions to mempool (Async)...", transactions.size());

		// 2. Async Submission with logging
		CompletableFuture.runAsync(() -> {
			try {
				context.startTimeMempool = Instant.now();
				AtomicInteger counter = new AtomicInteger(0); // Counter

				transactions.parallelStream().forEach(tx -> {
					try {
						mempoolManager.addTx(tx);
						context.pendingTxs.add(tx.getHash().toHexString());
						context.mempoolCount.incrementAndGet();

						// LOG EVERY 2000 TX
						int current = counter.incrementAndGet();
						if (current % 2000 == 0) {
							log.info("Step 2 Progress: Submitted {}/{}", current, TX_COUNT);
						}
					} catch (Exception e) {
						log.error("Failed to add tx in Step 2", e);
					}
				});

				context.endTimeMempool = Instant.now();
				log.info("Mempool submission finished. Starting Mining...");

				context.startTimeBlockchain = Instant.now();
				// miningService.startMining();
			} catch (Exception e) {
				log.error("Async submission failed", e);
			}
		});

		return getResults();
	}

	@GetMapping("/results")
	public StressTestResult getResults() {
		long mempoolTotal = context.mempoolCount.get();
		long confirmedTotal = context.confirmedCount.get();
		int pendingSize = context.pendingTxs.size();

		Instant blockchainEndTime;

		// If everything is done, stop time at 'lastBlockTime'
		if (pendingSize == 0 && confirmedTotal > 0 && context.lastBlockTime != null) {
			blockchainEndTime = context.lastBlockTime;
		} else {
			// Otherwise time is running
			blockchainEndTime = Instant.now();
		}

		// --- Mempool Stats ---
		double mempoolTps = 0;
		if (context.startTimeMempool != null && context.endTimeMempool != null) {
			long millis = Duration.between(context.startTimeMempool, context.endTimeMempool).toMillis();
			if (millis > 0) {
				mempoolTps = (double) mempoolTotal / (millis / 1000.0);
			}
		}

		// --- Blockchain Stats ---
		double blockchainTps = 0;
		long elapsedTimeSeconds = 0;

		// Validation: Calculate time only if we have StartTime
		if (context.startTimeBlockchain != null) {
			// Handle case when blockchainEndTime is LESS than startTime (very fast test)
			if (blockchainEndTime.isBefore(context.startTimeBlockchain)) {
				blockchainEndTime = context.startTimeBlockchain;
			}

			elapsedTimeSeconds = Duration.between(context.startTimeBlockchain, blockchainEndTime).getSeconds();
			long millis = Duration.between(context.startTimeBlockchain, blockchainEndTime).toMillis();

			if (millis > 0) {
				blockchainTps = (double) confirmedTotal / (millis / 1000.0);
			}
		}

		return StressTestResult.builder()
				.stepName(context.currentStepName)
				.totalTxSubmitted(mempoolTotal)
				.totalTxConfirmed(confirmedTotal)
				.pendingTx(pendingSize)
				.mempoolTps(Math.round(mempoolTps * 100.0) / 100.0)
				.blockchainTps(Math.round(blockchainTps * 100.0) / 100.0)
				.elapsedTimeSeconds(elapsedTimeSeconds)
				.build();
	}

	// @Async(STRESS_TEST_EXECUTOR) - Removed to handle rejection manually
	@EventListener
	public void onBlockConnected(BlockConnectedEvent event) {
		try {
			stressTestExecutor.execute(() -> {
				try {
					if (context.pendingTxs.isEmpty())
						return;

					int confirmedInThisBlock = 0;

					for (Tx tx : event.getBlock().getTxs()) {
						String hash = tx.getHash().toHexString();
						if (context.pendingTxs.contains(hash)) {
							context.pendingTxs.remove(hash);
							confirmedInThisBlock++;
						}
					}

					if (confirmedInThisBlock > 0) {
						context.confirmedCount.addAndGet(confirmedInThisBlock);
						context.lastBlockTime = Instant.now();
						log.info("StressTest: Confirmed {} txs in block #{}", confirmedInThisBlock,
								event.getBlock().getHeight());

						if (context.pendingTxs.isEmpty()) {
							log.info("StressTest: All transactions confirmed! STOPPING MINING & CLEANING UP.");

							try {
								Thread.sleep(100);
							} catch (InterruptedException e) {
							}

							mempoolManager.clear();
							log.info("StressTest: Cleanup complete. Ready for Step 2.");
						}
					}
				} catch (Exception e) {
					log.error("Error in StressTest onBlockConnected", e);
				}
			});
		} catch (RejectedExecutionException e) {
			// Gracefully ignore - application is shutting down
			log.debug("StressTest event listener rejected during shutdown: {}", e.getMessage());
		}
	}

	private void resetStats(String stepName) {
		context.currentStepName = stepName;
		context.mempoolCount.set(0);
		context.confirmedCount.set(0);
		context.pendingTxs.clear();
		context.startTimeMempool = null;
		context.endTimeMempool = null;
		context.startTimeBlockchain = null;
		context.lastBlockTime = null;
	}

	@Data
	@Builder
	public static class StressTestResult {
		String stepName;
		long totalTxSubmitted;
		long totalTxConfirmed;
		int pendingTx;
		double mempoolTps;
		double blockchainTps;
		long elapsedTimeSeconds;
	}

	private static class StressTestContext {
		String currentStepName = "IDLE";
		List<PrivateKey> step1Recipients = new ArrayList<>();
		AtomicLong mempoolCount = new AtomicLong(0);
		AtomicLong confirmedCount = new AtomicLong(0);
		Set<String> pendingTxs = ConcurrentHashMap.newKeySet();
		Instant startTimeMempool;
		Instant endTimeMempool;
		Instant startTimeBlockchain;
		Instant lastBlockTime;
	}

	private record KeyTxPair(PrivateKey key, Tx tx) {
	}
}