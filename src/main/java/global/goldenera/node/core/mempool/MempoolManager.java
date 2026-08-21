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
package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_SCHEDULER;
import static global.goldenera.node.core.config.CoreAsyncConfig.MEMPOOL_EVENT_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockReorgEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Main service facade for the Mempool.
 * Orchestrates validation and storage.
 * Provides transactions to the miner.
 * Reacts to blockchain events (connect/disconnect).
 */
@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MempoolManager {

	static final long MEMPOOL_PRUNE_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes
	static final long MEMPOOL_REVALIDATE_INTERVAL_MS = 30 * 1000; // 30 seconds
	static final int MEMPOOL_EVENT_MAX_ATTEMPTS = 3;

	MeterRegistry registry;
	MempoolStore mempoolStore;
	MempoolValidator mempoolValidator;
	MempoolProperties mempoolProperties;
	ChainHeadStateCache chainHeadStateService;
	Executor mempoolEventExecutor;
	ThreadPoolTaskScheduler coreScheduler;

	public MempoolManager(MeterRegistry registry, MempoolStore mempoolStore, MempoolValidator mempoolValidator,
			MempoolProperties mempoolProperties, ChainHeadStateCache chainHeadStateService,
			@Qualifier(MEMPOOL_EVENT_EXECUTOR) Executor mempoolEventExecutor,
			@Qualifier(CORE_SCHEDULER) ThreadPoolTaskScheduler coreScheduler) {
		this.registry = registry;
		this.mempoolStore = mempoolStore;
		this.mempoolValidator = mempoolValidator;
		this.mempoolProperties = mempoolProperties;
		this.chainHeadStateService = chainHeadStateService;
		this.mempoolEventExecutor = mempoolEventExecutor;
		this.coreScheduler = coreScheduler;
	}

	@PostConstruct
	public void init() {
		coreScheduler.scheduleAtFixedRate(this::pruneExpired, Duration.ofMillis(MEMPOOL_PRUNE_INTERVAL_MS));
		coreScheduler.scheduleAtFixedRate(this::revalidateMempool, Duration.ofMillis(MEMPOOL_REVALIDATE_INTERVAL_MS));
		log.info("MempoolManager: Scheduled pruneExpired every {}ms and revalidateMempool every {}ms",
				MEMPOOL_PRUNE_INTERVAL_MS, MEMPOOL_REVALIDATE_INTERVAL_MS);
	}

	/**
	 * Scheduled task to prune expired transactions from the mempool.
	 */
	private void pruneExpired() {
		long expireTimeMs = mempoolProperties.getTxExpireTimeInMinutes() * 60 * 1000L;
		Instant cutoffTime = Instant.now().minusMillis(expireTimeMs);
		mempoolStore.pruneExpiredTransactions(cutoffTime);
	}

	public MempoolResult addTx(@NonNull Tx tx) {
		return addTx(tx, null, MempoolTxAddEvent.AddReason.NEW, false);
	}

	public MempoolResult addTx(@NonNull Tx tx, Address receivedFrom) {
		return addTx(tx, receivedFrom, MempoolTxAddEvent.AddReason.NEW, false);
	}

	public MempoolResult addTx(@NonNull Tx tx, boolean skipValidation) {
		return addTx(tx, null, MempoolTxAddEvent.AddReason.NEW, skipValidation);
	}

	public MempoolResult addTx(@NonNull Tx tx, Address receivedFrom, boolean skipValidation) {
		return addTx(tx, receivedFrom, MempoolTxAddEvent.AddReason.NEW, skipValidation);
	}

	/**
	 * Main entry point for adding a new transaction to the mempool.
	 * This is called by the P2P layer or local API.
	 *
	 * @param consensusTx
	 *            The new transaction.
	 * @return A result indicating success or the reason for failure.
	 */
	public MempoolResult addTx(@NonNull Tx tx, Address receivedFrom, @NonNull MempoolTxAddEvent.AddReason reason,
			boolean skipValidation) {
		log.debug("[MANAGER-DEBUG] addTx START: hash={}, sender={}, nonce={}, fee={}, reason={}",
				tx.getHash().toShortLogString(),
				tx.getSender() != null ? tx.getSender().toChecksumAddress() : "null",
				tx.getNonce(), tx.getFee().toBigInteger(), reason);

		// 1. Validate the tx against the *confirmed state* AND *mempool state*
		MempoolEntry entry = new MempoolEntry(tx);
		entry.setReceivedFrom(receivedFrom);
		Hash txHash = entry.getHash();
		MempoolValidator.MempoolValidationResult validationResult = mempoolValidator
				.validateAgainstChainAndMempool(entry, reason, skipValidation);

		if (!validationResult.isValid()) {
			log.warn("[MANAGER-DEBUG] Mempool: Rejecting tx {}: {} (status={})", txHash.toShortLogString(),
					validationResult.getErrorMessage(), validationResult.getStatus());
			return new MempoolResult(MempoolAddResult.fromValidation(validationResult.getStatus()),
					validationResult.getReasonCode() == null
							? MempoolReasonCode.fromValidation(validationResult.getStatus())
							: validationResult.getReasonCode(),
					validationResult.getErrorMessage());
		}

		log.debug("[MANAGER-DEBUG] Validation passed, chainNonce={}", validationResult.getCurrentChainNonce());

		// 2. Add the tx to the internal storage
		MempoolStore.StorageAddResult storageResult = mempoolStore.addTransaction(
				entry,
				validationResult.getCurrentChainNonce(),
				reason,
				validationResult.getAdmissionConstraints());

		log.debug("[MANAGER-DEBUG] Storage result: {} for tx {}", storageResult, txHash.toShortLogString());

		// 3. Translate storage result to API result
		MempoolAddResult result;
		MempoolReasonCode reasonCode;
		String message;
		switch (storageResult) {
			case ADDED_EXECUTABLE:
				log.debug("Mempool: Added executable tx {}", txHash.toShortLogString());
				result = MempoolAddResult.SUCCESS;
				reasonCode = MempoolReasonCode.ACCEPTED_EXECUTABLE;
				message = "Transaction added to mempool.";
				break;
			case ADDED_FUTURE:
				log.debug("Mempool: Added future tx {}", txHash.toShortLogString());
				result = MempoolAddResult.QUEUED;
				reasonCode = MempoolReasonCode.ACCEPTED_FUTURE_NONCE;
				message = "Transaction queued (future nonce).";
				break;
			case FAILED_FEE_TOO_LOW:
				log.warn("Mempool: Rejecting tx {}: Fee too low to replace existing (RBF).",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_RBF;
				reasonCode = MempoolReasonCode.REPLACEMENT_FEE_TOO_LOW;
				message = "Fee too low to replace existing transaction (RBF).";
				break;
			case DUPLICATE_HASH:
				log.warn("Mempool: Rejecting tx {}: Duplicate hash.", txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_DUPLICATE;
				reasonCode = MempoolReasonCode.DUPLICATE_HASH;
				message = "Transaction already exists in mempool.";
				break;
			case GOVERNANCE_CONFLICT:
				log.warn("Mempool: Rejecting tx {}: Conflicting governance transaction is pending.",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_DUPLICATE;
				reasonCode = MempoolReasonCode.GOVERNANCE_CONFLICT;
				message = "A conflicting governance transaction is already pending.";
				break;
			case INSUFFICIENT_FUNDS:
				log.warn("Mempool: Rejecting tx {}: Atomic balance reservation failed.",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_STATE;
				reasonCode = MempoolReasonCode.INSUFFICIENT_FUNDS;
				message = "Insufficient funds after accounting for pending transactions.";
				break;
			case TOKEN_SUPPLY_CONFLICT:
				log.warn("Mempool: Rejecting tx {}: Pending token mints exceed maxSupply.",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_STATE;
				reasonCode = MempoolReasonCode.TOKEN_SUPPLY_CONFLICT;
				message = "Pending token mint proposals would exceed maxSupply.";
				break;
			case STALE:
				log.warn("Mempool: Rejecting tx {}: Stale (nonce mismatch in storage).",
						txHash.toShortLogString());
				result = MempoolAddResult.STALE;
				reasonCode = MempoolReasonCode.NONCE_STALE;
				message = "Transaction is stale (nonce mismatch).";
				break;
			case NONCE_TOO_FAR_FUTURE:
				log.warn("Mempool: Rejecting tx {}: Nonce too far in the future.",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_NONCE_TOO_FAR_FUTURE;
				reasonCode = MempoolReasonCode.NONCE_TOO_FAR_FUTURE;
				message = "Nonce is too far in the future.";
				break;
			case MEMPOOL_FULL:
				log.warn("Mempool: Rejecting tx {}: Mempool full and fee insufficient to evict.",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_MEMPOOL_FULL;
				reasonCode = MempoolReasonCode.MEMPOOL_CAPACITY;
				message = "Mempool is full and fee is insufficient to evict existing transactions.";
				break;
			default:
				result = MempoolAddResult.REJECTED_OTHER;
				reasonCode = MempoolReasonCode.STORAGE_FAILURE;
				message = "Unknown error during storage addition.";
				break;
		}
		registry.counter("blockchain.mempool.add_result", "status", result.name())
				.increment();
		return new MempoolResult(result, reasonCode, message);
	}

	public void addTxs(@NonNull List<Tx> txs, Address receivedFrom, @NonNull MempoolTxAddEvent.AddReason reason,
			boolean skipValidation) {
		log.debug("addTxs called: {} txs, reason={}, skipValidation={}", txs.size(), reason, skipValidation);
		int added = 0;
		for (Tx tx : txs) {
			MempoolResult result = addTx(tx, receivedFrom, reason, skipValidation);
			if (result.status().isSuccess()) {
				added++;
			}
		}
		log.debug("Mempool batch added {}/{} tx(s)", added, txs.size());
	}

	public Iterator<MempoolEntry> getTxIterator() {
		return mempoolStore.getExecutableTransactionsIterator();
	}

	public boolean isFull() {
		return mempoolStore.isFull();
	}

	public long getTransactionCount() {
		return mempoolStore.getCount();
	}

	public void clear() {
		mempoolStore.clear();
	}

	public void removeTransaction(@NonNull Hash txHash) {
		log.warn("Evicting transaction {} from mempool.",
				txHash.toShortLogString());
		// Pass the call to the storage, which holds all the logic.
		mempoolStore.removeTransaction(txHash);
	}

	public void removeTransactions(@NonNull List<Hash> txHashes) {
		if (txHashes == null || txHashes.isEmpty())
			return;
		log.debug("Evicting {} tx(s) from mempool", txHashes.size());
		mempoolStore.removeTransactions(txHashes);
	}

	/**
	 * Maintenance task: Periodically re-validates ALL transactions in the mempool
	 * against the current Chain Head.
	 * This catches transactions that became invalid (e.g., insufficient funds)
	 * due to other transactions being mined, which might be missed by simple event
	 * processing (especially on non-mining nodes).
	 */
	public synchronized void revalidateMempool() {
		if (mempoolStore.getCount() == 0) {
			return;
		}

		log.debug("Revalidating mempool ({} txs)...", mempoolStore.getCount());
		List<Hash> toRemove = new ArrayList<>();
		Map<Address, Long> chainNonces = new HashMap<>();
		Set<Address> successfullyValidatedSenders = new HashSet<>();
		Map<Address, Wei> projectedNativeBalances = new HashMap<>();

		// Iterate over all transactions (snapshot iterator)
		Iterator<MempoolEntry> it = mempoolStore.getAllTxs().iterator();
		while (it.hasNext()) {
			MempoolEntry entry = it.next();
			try {
				// Validate against CURRENT head state
				MempoolValidator.MempoolValidationResult result = mempoolValidator.revalidateAgainstChain(entry);
				if (entry.getTx().getSender() != null && result.getCurrentChainNonce() >= 0) {
					chainNonces.put(entry.getTx().getSender(), result.getCurrentChainNonce());
					if (result.isValid()) {
						successfullyValidatedSenders.add(entry.getTx().getSender());
						if (result.getAdmissionConstraints() != null) {
							projectedNativeBalances.put(entry.getTx().getSender(),
									result.getAdmissionConstraints().nativeBalance());
						}
					}
				}

				if (result.isPermanentlyInvalid()) {
					log.debug("Found invalid tx during revalidation: {} (Reason: {})",
							entry.getHash().toShortLogString(), result.getErrorMessage());
					toRemove.add(entry.getHash());
				} else if (result.getStatus() == MempoolValidator.ValidationStatus.TRANSIENT_ERROR) {
					log.warn("Keeping tx {} after transient revalidation failure: {}",
							entry.getHash().toShortLogString(), result.getErrorMessage());
				}
			} catch (Exception e) {
				log.warn("Error revalidating tx {}: {}", entry.getHash(), e.getMessage());
			}
		}

		if (!toRemove.isEmpty()) {
			log.info("Mempool revalidation: Evicting {} invalid transactions.", toRemove.size());
			mempoolStore.removeTransactions(toRemove);
		}
		if (!chainNonces.isEmpty()) {
			mempoolStore.resynchronizeSenders(chainNonces);
		}
		if (!successfullyValidatedSenders.isEmpty()) {
			Map<Address, MempoolStore.SenderBalances> senderBalances;
			try {
				senderBalances = loadSenderBalances(successfullyValidatedSenders, projectedNativeBalances);
			} catch (RuntimeException exception) {
				log.warn("Skipping mempool balance reconciliation because chain state is unavailable", exception);
				return;
			}
			mempoolStore.reconcileSenderBalances(senderBalances);
		}
	}

	private Map<Address, MempoolStore.SenderBalances> loadSenderBalances(Set<Address> senders,
			Map<Address, Wei> projectedNativeBalances) {
		WorldState worldState = chainHeadStateService.getHeadState();
		Map<Address, MempoolStore.SenderBalances> result = new HashMap<>();
		for (Address sender : senders) {
			Map<Address, Wei> tokenBalances = new HashMap<>();
			for (MempoolEntry entry : mempoolStore.getTxsBySender(sender)) {
				Tx tx = entry.getTx();
				if (tx.getType() == TxType.TRANSFER && !Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
					tokenBalances.computeIfAbsent(tx.getTokenAddress(),
							token -> worldState.getBalance(sender, token).getBalance());
				}
			}
			Wei nativeBalance = projectedNativeBalances.containsKey(sender)
					? projectedNativeBalances.get(sender)
					: worldState.getBalance(sender, Address.NATIVE_TOKEN).getSpendableBalance();
			result.put(sender, new MempoolStore.SenderBalances(nativeBalance, tokenBalances));
		}
		return result;
	}

	// =================================================================================
	// === EVENT LISTENERS (Event-Driven Architecture) ===
	// =================================================================================

	/**
	 * Listens for a new block being connected *after* the DB commit.
	 * This is the crucial cleanup step (eviction).
	 *
	 * @param event
	 *            The event containing the connected block and its txs.
	 */
	@EventListener
	public void onBlockConnected(BlockConnectedEvent event) {
		Block newBlock = event.getBlock();
		if (newBlock.getHeight() == 0) { // Genesis block
			return;
		}
		mempoolEventExecutor.execute(() -> processMempoolBlockEvent(
				"connect", newBlock, () -> processBlockConnected(newBlock)));
	}

	private synchronized void processBlockConnected(Block newBlock) {
		List<Tx> txsInBlock = newBlock.getTxs();
		log.debug("Mempool: Processing connected block {} evictions/promotions.", newBlock.getHeight());
		mempoolStore.processNewBlock(txsInBlock);
	}

	/**
	 * Listens for a block being disconnected (during a reorg) *after* the DB
	 * commit.
	 *
	 * @param event
	 *            The event containing the *disconnected* block and its txs.
	 */
	@EventListener
	public void onBlockDisconnected(BlockDisconnectedEvent event) {
		Block oldBlock = event.getBlock();
		if (oldBlock.getHeight() == 0) { // Genesis block
			return;
		}
		mempoolEventExecutor.execute(() -> processMempoolBlockEvent(
				"disconnect", oldBlock, () -> processBlockDisconnected(oldBlock)));
	}

	/**
	 * Revalidates the final mempool projection after every disconnected and
	 * connected block from a reorg has been processed by the ordered executor.
	 */
	@EventListener
	public void onBlockReorg(BlockReorgEvent event) {
		mempoolEventExecutor.execute(this::revalidateMempool);
	}

	private synchronized void processBlockDisconnected(Block oldBlock) {
		List<Tx> txsToReAdd = oldBlock.getTxs();
		log.debug("Mempool: Processing disconnected block {}. Revalidating {} txs for re-admission.",
				oldBlock.getHeight(), txsToReAdd.size());
		for (Tx tx : txsToReAdd) {
			addTx(tx, null, MempoolTxAddEvent.AddReason.REORG, false);
		}
	}

	private void processMempoolBlockEvent(String eventType, Block block, Runnable action) {
		Timer.Sample sample = Timer.start(registry);
		try {
			for (int attempt = 1; attempt <= MEMPOOL_EVENT_MAX_ATTEMPTS; attempt++) {
				try {
					action.run();
					registry.counter("blockchain.mempool.event.processed_total", "type", eventType).increment();
					return;
				} catch (RuntimeException exception) {
					if (attempt == MEMPOOL_EVENT_MAX_ATTEMPTS) {
						registry.counter("blockchain.mempool.event.failures_total", "type", eventType).increment();
						log.error("Mempool: Failed to process {} block {} after {} attempts",
								eventType, block.getHeight(), attempt, exception);
						return;
					}
					registry.counter("blockchain.mempool.event.retries_total", "type", eventType).increment();
					log.warn("Mempool: Retrying {} block {} after attempt {} failed: {}",
							eventType, block.getHeight(), attempt, exception.getMessage());
					try {
						Thread.sleep(50L * attempt);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						registry.counter("blockchain.mempool.event.failures_total", "type", eventType).increment();
						return;
					}
				}
			}
		} finally {
			sample.stop(registry.timer("blockchain.mempool.event.processing_time", "type", eventType));
		}
	}

	// =================================================================================
	// === HELPERS ===
	// =================================================================================

	/**
	 * Public-facing result enum for addTransaction.
	 */
	public record MempoolResult(MempoolAddResult status, MempoolReasonCode reasonCode, String message) {
		public MempoolResult(MempoolAddResult status, String message) {
			this(status, MempoolReasonCode.fromStatus(status), message);
		}
	}

	public enum MempoolReasonCode {
		ACCEPTED_EXECUTABLE,
		ACCEPTED_FUTURE_NONCE,
		VALIDATION_STALE,
		VALIDATION_FEE_TOO_LOW,
		VALIDATION_GOVERNANCE_DUPLICATE,
		VALIDATION_STATE_INVALID,
		VALIDATION_STATELESS_INVALID,
		VALIDATION_TRANSIENT_ERROR,
		LAST_UNLIMITED_REQUIRED,
		LIMITED_QUOTA_ZERO,
			MINING_WINDOW_OUT_OF_RANGE,
			MINING_REWARD_VESTING_OUT_OF_RANGE,
		INVALID_POLICY_TRANSITION,
		EXECUTION_REVALIDATION_FAILED,
		REPLACEMENT_FEE_TOO_LOW,
		DUPLICATE_HASH,
		GOVERNANCE_CONFLICT,
		INSUFFICIENT_FUNDS,
		INSUFFICIENT_SPENDABLE_BALANCE,
		TOKEN_SUPPLY_CONFLICT,
		NONCE_STALE,
		NONCE_TOO_FAR_FUTURE,
		MEMPOOL_CAPACITY,
		STORAGE_FAILURE;

		static MempoolReasonCode fromValidation(MempoolValidator.ValidationStatus status) {
			return switch (status) {
				case VALID -> ACCEPTED_EXECUTABLE;
				case STALE -> VALIDATION_STALE;
				case FEE_TOO_LOW -> VALIDATION_FEE_TOO_LOW;
				case GOVERNANCE_DUPLICATE -> VALIDATION_GOVERNANCE_DUPLICATE;
				case STATE_INVALID -> VALIDATION_STATE_INVALID;
				case STATELESS_INVALID -> VALIDATION_STATELESS_INVALID;
				case TRANSIENT_ERROR -> VALIDATION_TRANSIENT_ERROR;
			};
		}

		static MempoolReasonCode fromStatus(MempoolAddResult status) {
			return switch (status) {
				case SUCCESS -> ACCEPTED_EXECUTABLE;
				case QUEUED -> ACCEPTED_FUTURE_NONCE;
				case STALE -> VALIDATION_STALE;
				case REJECTED_FEE -> VALIDATION_FEE_TOO_LOW;
				case REJECTED_RBF -> REPLACEMENT_FEE_TOO_LOW;
				case REJECTED_STATE -> VALIDATION_STATE_INVALID;
				case REJECTED_DUPLICATE -> DUPLICATE_HASH;
				case REJECTED_NONCE_TOO_FAR_FUTURE -> NONCE_TOO_FAR_FUTURE;
				case REJECTED_MEMPOOL_FULL -> MEMPOOL_CAPACITY;
				case REJECTED_OTHER -> STORAGE_FAILURE;
			};
		}
	}

	public enum MempoolAddResult {
		SUCCESS, // Added as executable
		QUEUED, // Added as future
		STALE, // Rejected: Nonce too low
		REJECTED_FEE, // Rejected: Fee too low (for spam)
		REJECTED_RBF, // Rejected: Fee too low for replacement
		REJECTED_STATE, // Rejected: Insufficient funds, not authority, etc.
		REJECTED_DUPLICATE, // Rejected: Duplicate hash or governance
		REJECTED_NONCE_TOO_FAR_FUTURE, // Rejected: Nonce too far in the future
		REJECTED_MEMPOOL_FULL, // Rejected: Mempool full and fee too low
		REJECTED_OTHER; // Rejected: Bad signature, invalid format, etc.

		public static MempoolAddResult fromValidation(MempoolValidator.ValidationStatus status) {
			switch (status) {
				case STALE:
					return STALE;
				case FEE_TOO_LOW:
					return REJECTED_FEE;
				case GOVERNANCE_DUPLICATE:
					return REJECTED_DUPLICATE;
				case STATE_INVALID:
					return REJECTED_STATE;
				case STATELESS_INVALID:
				case TRANSIENT_ERROR:
				default:
					return REJECTED_OTHER;
			}
		}

		public boolean isSuccess() {
			return this == SUCCESS || this == QUEUED;
		}
	}
}
