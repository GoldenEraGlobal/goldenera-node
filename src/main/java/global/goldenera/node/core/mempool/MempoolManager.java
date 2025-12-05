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
import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_TASK_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import io.micrometer.core.instrument.MeterRegistry;
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

	static final long MEMPOOL_PRUNE_INTERVAL_MS = 20 * 60 * 1000; // 20 minutes

	MeterRegistry registry;
	MempoolStore mempoolStore;
	MempoolValidator mempoolValidator;
	MempoolProperties mempoolProperties;
	ThreadPoolTaskScheduler coreScheduler;

	public MempoolManager(MeterRegistry registry, MempoolStore mempoolStore, MempoolValidator mempoolValidator,
			MempoolProperties mempoolProperties, @Qualifier(CORE_SCHEDULER) ThreadPoolTaskScheduler coreScheduler) {
		this.registry = registry;
		this.mempoolStore = mempoolStore;
		this.mempoolValidator = mempoolValidator;
		this.mempoolProperties = mempoolProperties;
		this.coreScheduler = coreScheduler;
	}

	@PostConstruct
	public void init() {
		coreScheduler.scheduleAtFixedRate(this::pruneExpired, Duration.ofMillis(MEMPOOL_PRUNE_INTERVAL_MS));
		log.info("MempoolManager: Scheduled pruneExpired every {}ms (txExpireTime={}min)",
				MEMPOOL_PRUNE_INTERVAL_MS, mempoolProperties.getTxExpireTimeInMinutes());
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
		// 1. Validate the tx against the *confirmed state* AND *mempool state*
		MempoolEntry entry = new MempoolEntry(tx);
		entry.setReceivedFrom(receivedFrom);
		Hash txHash = entry.getHash();
		MempoolValidator.MempoolValidationResult validationResult = mempoolValidator
				.validateAgainstChainAndMempool(entry, reason, skipValidation);

		if (!validationResult.isValid()) {
			log.warn("Mempool: Rejecting tx {}: {}", txHash.toShortLogString(),
					validationResult.getErrorMessage());
			return new MempoolResult(MempoolAddResult.fromValidation(validationResult.getStatus()),
					validationResult.getErrorMessage());
		}

		// 2. Add the tx to the internal storage
		MempoolStore.StorageAddResult storageResult = mempoolStore.addTransaction(
				entry,
				validationResult.getCurrentChainNonce(),
				reason);

		// 3. Translate storage result to API result
		MempoolAddResult result;
		String message;
		switch (storageResult) {
			case ADDED_EXECUTABLE:
				log.debug("Mempool: Added executable tx {}", txHash.toShortLogString());
				result = MempoolAddResult.SUCCESS;
				message = "Transaction added to mempool.";
				break;
			case ADDED_FUTURE:
				log.debug("Mempool: Added future tx {}", txHash.toShortLogString());
				result = MempoolAddResult.QUEUED;
				message = "Transaction queued (future nonce).";
				break;
			case FAILED_FEE_TOO_LOW:
				log.warn("Mempool: Rejecting tx {}: Fee too low to replace existing (RBF).",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_RBF;
				message = "Fee too low to replace existing transaction (RBF).";
				break;
			case DUPLICATE_HASH:
				log.warn("Mempool: Rejecting tx {}: Duplicate hash.", txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_DUPLICATE;
				message = "Transaction already exists in mempool.";
				break;
			case STALE:
				log.warn("Mempool: Rejecting tx {}: Stale (nonce mismatch in storage).",
						txHash.toShortLogString());
				result = MempoolAddResult.STALE;
				message = "Transaction is stale (nonce mismatch).";
				break;
			case NONCE_TOO_FAR_FUTURE:
				log.warn("Mempool: Rejecting tx {}: Nonce too far in the future.",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_NONCE_TOO_FAR_FUTURE;
				message = "Nonce is too far in the future.";
				break;
			case MEMPOOL_FULL:
				log.warn("Mempool: Rejecting tx {}: Mempool full and fee insufficient to evict.",
						txHash.toShortLogString());
				result = MempoolAddResult.REJECTED_MEMPOOL_FULL;
				message = "Mempool is full and fee is insufficient to evict existing transactions.";
				break;
			default:
				result = MempoolAddResult.REJECTED_OTHER;
				message = "Unknown error during storage addition.";
				break;
		}
		registry.counter("blockchain.mempool.add_result", "status", result.name())
				.increment();
		return new MempoolResult(result, message);
	}

	public void addTxs(@NonNull List<Tx> txs, Address receivedFrom, @NonNull MempoolTxAddEvent.AddReason reason,
			boolean skipValidation) {
		List<MempoolEntry> validEntries = new java.util.ArrayList<>();
		java.util.Map<Address, Long> chainNonces = new java.util.HashMap<>();

		for (Tx tx : txs) {
			MempoolEntry entry = new MempoolEntry(tx);
			entry.setReceivedFrom(receivedFrom);

			MempoolValidator.MempoolValidationResult validationResult = mempoolValidator
					.validateAgainstChainAndMempool(entry, reason, skipValidation);

			if (validationResult.isValid()) {
				validEntries.add(entry);
				if (tx.getSender() != null) {
					chainNonces.put(tx.getSender(), validationResult.getCurrentChainNonce());
				}
			}
		}

		if (!validEntries.isEmpty()) {
			mempoolStore.addTransactions(validEntries, chainNonces, reason);
			if (validEntries.size() > 50) {
				log.debug("Mempool batch added {}/{} tx(s)", validEntries.size(), txs.size());
			}
		}
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
	@Async(CORE_TASK_EXECUTOR)
	public void onBlockConnected(BlockConnectedEvent event) {
		Block newBlock = event.getBlock();
		if (newBlock.getHeight() == 0) { // Genesis block
			return;
		}
		List<Tx> txsInBlock = newBlock.getTxs();
		log.debug("Mempool: Notified of new block {}. Processing evictions/promotions.", newBlock.getHeight());
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
	@Async(CORE_TASK_EXECUTOR)
	public void onBlockDisconnected(BlockDisconnectedEvent event) {
		Block oldBlock = event.getBlock();
		if (oldBlock.getHeight() == 0) { // Genesis block
			return;
		}
		List<Tx> txsToReAdd = oldBlock.getTxs().stream()
				// .filter(tx -> tx.getType() != TxType.COINBASE) // COINBASE might be removed
				// or handled differently
				.toList();

		log.debug("Mempool: Notified of disconnected block {}. Re-adding {} txs.",
				oldBlock.getHeight(), txsToReAdd.size());

		mempoolStore.addTransactionsBack(txsToReAdd, oldBlock);
	}

	// =================================================================================
	// === HELPERS ===
	// =================================================================================

	/**
	 * Public-facing result enum for addTransaction.
	 */
	public record MempoolResult(MempoolAddResult status, String message) {
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
				case INVALID:
					// This is generic. "REJECTED_STATE" covers most L4 invalidations.
					// "REJECTED_DUPLICATE" might be more specific if the message indicates it.
					return REJECTED_STATE;
				default:
					return REJECTED_OTHER;
			}
		}

		public boolean isSuccess() {
			return this == SUCCESS || this == QUEUED;
		}
	}
}