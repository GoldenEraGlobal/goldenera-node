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

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.google.common.collect.Iterators;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.consensus.state.AccountNonceState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe, high-performance in-memory storage for pending transactions.
 * This class implements a hybrid model based on user requirements:
 * 1. "User Txs": (sender != null) are managed in a nonce-ordered pool
 * (SenderAccountPool).
 * 2. "System Txs": (sender == null) are managed in a simple FIFO queue
 * (systemTxs).
 * 3. Governance Sets: Tracks pending governance operations to prevent
 * duplicates.
 */
@Service
@Slf4j
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MempoolStore {

	static Comparator<MempoolEntry> TX_FEE_COMPARATOR = Comparator
			.comparing(MempoolStore::calculateFeePerByte, Comparator.reverseOrder())
			.thenComparing(MempoolEntry::getNonce)
			.thenComparing(MempoolEntry::getHash);

	MeterRegistry registry;
	MempoolProperties mempoolProperties;
	ChainHeadStateCache chainHeadStateService;
	ApplicationEventPublisher applicationEventPublisher;

	ConcurrentSkipListSet<MempoolEntry> executableTxsByFee = new ConcurrentSkipListSet<>(TX_FEE_COMPARATOR);

	/**
	 * Global set of all transactions (executable + future) sorted by fee.
	 * Used for eviction when mempool is full.
	 */
	ConcurrentSkipListSet<MempoolEntry> allTxsByFee = new ConcurrentSkipListSet<>(TX_FEE_COMPARATOR);

	/**
	 * 1. Global lookup for all txs by hash (prevents duplicates).
	 * Key: Tx Hash
	 * Value: The transaction model
	 */
	ConcurrentHashMap<Hash, MempoolEntry> allTxsByHash = new ConcurrentHashMap<>();

	/**
	 * 2. "User Txs" (sender != null): Managed by Sender and Nonce.
	 * For: TRANSFER, BIP_CREATE, BIP_VOTE, TOKEN_BURN
	 * Key: Sender Address
	 * Value: The sender's personal transaction pool
	 */
	ConcurrentHashMap<Address, SenderAccountPool> userTxsBySender = new ConcurrentHashMap<>();

	/**
	 * 3. "System Txs" (sender == null): Simple FIFO queue.
	 * For: TOKEN_MINT
	 */
	ConcurrentLinkedQueue<MempoolEntry> systemTxs = new ConcurrentLinkedQueue<>();

	/**
	 * Tracks addresses of Authorities that are PENDING on add.
	 */
	Set<Address> pendingAuthorityAdds = ConcurrentHashMap.newKeySet();

	/**
	 * Tracks addresses of Authorities that are PENDING on remove.
	 */
	Set<Address> pendingAuthorityRemoves = ConcurrentHashMap.newKeySet();

	/**
	 * Tracks aliases of Address Aliases that are PENDING on add.
	 */
	Set<String> pendingAddressAliasAdds = ConcurrentHashMap.newKeySet();

	/**
	 * Tracks aliases of Address Aliases that are PENDING on remove.
	 */
	Set<String> pendingAddressAliasRemoves = ConcurrentHashMap.newKeySet();

	/**
	 * Tracks which authorities already have a BIP_CONSENSUS_PARAMS_SET
	 * transaction pending in the mempool.
	 * This prevents one authority from spamming governance.
	 */
	private final Set<Address> authoritiesWithPendingParamChange = ConcurrentHashMap.newKeySet();

	/**
	 * Tracks pending BIP votes to prevent duplicate votes in the mempool.
	 * Key: BIP Hash (the one being voted on)
	 * Value: Set of Authority Addresses that have a pending vote for this BIP.
	 */
	ConcurrentHashMap<Hash, Set<Address>> pendingBipVotes = new ConcurrentHashMap<>();

	/**
	 * A global lock for operations that modify the entire pool structure,
	 * such as processing a new block or handling a reorg.
	 */
	ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();

	@PostConstruct
	public void initMetrics() {
		registry.gaugeMapSize("blockchain.mempool.tx_count", Tags.empty(), allTxsByHash);
		registry.gaugeMapSize("blockchain.mempool.senders_count", Tags.empty(), userTxsBySender);
		registry.gaugeCollectionSize("blockchain.mempool.system_tx_count", Tags.empty(), systemTxs);
		registry.gauge("blockchain.mempool.future_tx_count", Tags.empty(), this,
				store -> store.allTxsByFee.size() - store.executableTxsByFee.size());
	}

	// =================================================================================
	// === PUBLIC API (Called by MempoolManager) ===
	// =================================================================================

	/**
	 * Adds a validated transaction to the appropriate pool.
	 *
	 * @param tx
	 *            The validated transaction.
	 * @param currentChainNonce
	 *            The current confirmed nonce (from chain state) for
	 *            the
	 *            sender.
	 *            -1 if the tx is a "system tx" (sender ==
	 *            null).
	 * @return The result of the add operation.
	 */
	public StorageAddResult addTransaction(@NonNull MempoolEntry entry, long currentChainNonce,
			MempoolTxAddEvent.AddReason reason) {
		List<MempoolTxRemoveEvent> eventsToPublish = new ArrayList<>();
		StorageAddResult result = addTransactionInternal(entry, currentChainNonce, eventsToPublish);
		for (MempoolTxRemoveEvent event : eventsToPublish) {
			applicationEventPublisher.publishEvent(event);
		}
		if (result.isSuccess()) {
			applicationEventPublisher
					.publishEvent(new MempoolTxAddEvent(this, entry, reason));
		}
		return result;
	}

	/**
	 * Batch add transactions.
	 * Optimized to acquire global lock only once.
	 */
	public Map<Hash, StorageAddResult> addTransactions(List<MempoolEntry> entries,
			Map<Address, Long> senderChainNonces,
			MempoolTxAddEvent.AddReason reason) {
		Map<Hash, StorageAddResult> results = new HashMap<>();
		List<MempoolEntry> successfulEntries = new ArrayList<>();
		List<MempoolTxRemoveEvent> eventsToPublish = new ArrayList<>();

		globalLock.readLock().lock();
		try {
			Map<Address, List<MempoolEntry>> bySender = new HashMap<>();
			List<MempoolEntry> systemTxsList = new ArrayList<>();

			for (MempoolEntry entry : entries) {
				if (allTxsByHash.containsKey(entry.getHash())) {
					results.put(entry.getHash(), StorageAddResult.DUPLICATE_HASH);
					continue;
				}
				allTxsByHash.put(entry.getHash(), entry);
				allTxsByFee.add(entry);
				if (entry.getTx().getSender() != null) {
					bySender.computeIfAbsent(entry.getTx().getSender(), k -> new ArrayList<>()).add(entry);
				} else {
					systemTxsList.add(entry);
				}
			}

			// 2. Process User Txs
			for (Map.Entry<Address, List<MempoolEntry>> group : bySender.entrySet()) {
				Address sender = group.getKey();
				List<MempoolEntry> senderEntries = group.getValue();
				Long chainNonce = senderChainNonces.getOrDefault(sender, 0L);

				SenderAccountPool pool = userTxsBySender.computeIfAbsent(
						sender,
						k -> new SenderAccountPool(mempoolProperties, sender, chainNonce));

				pool.lock.lock();
				try {
					List<MempoolEntry> txsToAdd = new ArrayList<>();
					List<MempoolEntry> txsToRemove = new ArrayList<>();

					for (MempoolEntry entry : senderEntries) {
						StorageAddResult res = pool.addTransaction(entry, txsToAdd, txsToRemove);
						results.put(entry.getHash(), res);

						if (res.isSuccess()) {
							successfulEntries.add(entry);
							updateGovernanceSets(entry, true);

							// Handle RBF removals
							for (MempoolEntry rbf : txsToRemove) {
								allTxsByFee.remove(rbf);
								executableTxsByFee.remove(rbf);
								eventsToPublish.add(
										new MempoolTxRemoveEvent(this, rbf, MempoolTxRemoveEvent.RemoveReason.RBF));
							}
						} else {
							allTxsByHash.remove(entry.getHash());
							allTxsByFee.remove(entry);
						}
					}
					executableTxsByFee.removeAll(txsToRemove);
					executableTxsByFee.addAll(txsToAdd);

				} finally {
					pool.lock.unlock();
				}
			}

			// 3. Process System Txs
			for (MempoolEntry sysTx : systemTxsList) {
				systemTxs.add(sysTx);
				results.put(sysTx.getHash(), StorageAddResult.ADDED_EXECUTABLE);
				successfulEntries.add(sysTx);
				updateGovernanceSets(sysTx, true);
			}

			// 4. Check Limits (EVICTION LOGIC)
			if (isFull()) {
				while (isFull() && !allTxsByFee.isEmpty()) {
					MempoolEntry victim = allTxsByFee.last();
					evictTransaction(victim, eventsToPublish);
					if (results.containsKey(victim.getHash())) {
						results.put(victim.getHash(), StorageAddResult.MEMPOOL_FULL);
						successfulEntries.remove(victim);
					}
				}
			}

		} finally {
			globalLock.readLock().unlock();
		}

		// 5. Publish Events OUTSIDE LOCK
		for (MempoolTxRemoveEvent event : eventsToPublish) {
			applicationEventPublisher.publishEvent(event);
		}
		for (MempoolEntry entry : successfulEntries) {
			applicationEventPublisher.publishEvent(new MempoolTxAddEvent(this, entry, reason));
		}
		return results;
	}

	/**
	 * Gathers all executable transactions for a miner.
	 * This combines system txs and the head of each user tx pool.
	 *
	 * @return A flat list of all executable transactions.
	 */
	public Iterator<MempoolEntry> getExecutableTransactionsIterator() {
		Iterator<MempoolEntry> userTxs = executableTxsByFee.iterator();

		// System txs have priority, so put them at the beginning
		return Iterators.concat(systemTxs.iterator(), userTxs);
	}

	/**
	 * Processes a new block that has been connected to the chain.
	 * This method evicts mined transactions and promotes future transactions.
	 *
	 * @param minedTxs
	 *            The list of transactions included in the new block.
	 * @param newTipStateRoot
	 *            The new stateRootHash *after* the block was applied.
	 */
	public void processNewBlock(@NonNull List<Tx> txs) {
		globalLock.writeLock().lock();
		try {
			log.debug("Mempool: Processing new block, evicting {} txs.", txs.size());

			Set<Address> affectedSenders = new HashSet<>();

			// 1. Eviction Phase: Remove mined txs from all pools
			for (Tx tx : txs) {
				Hash txHash = tx.getHash();
				MempoolEntry txFromMempool = allTxsByHash.remove(txHash);

				if (tx.getSender() != null) {
					affectedSenders.add(tx.getSender());
				}

				if (txFromMempool == null) {
					continue;
				}

				allTxsByFee.remove(txFromMempool);
				executableTxsByFee.remove(txFromMempool);
				updateGovernanceSets(txFromMempool, false);

				if (txFromMempool.getTx().getSender() != null) {
					SenderAccountPool pool = userTxsBySender.get(txFromMempool.getTx().getSender());
					if (pool != null) {
						pool.lock.lock();
						try {
							pool.removeTxs(Set.of(txFromMempool.getNonce()));
						} finally {
							pool.lock.unlock();
						}
					}
				} else {
					systemTxs.remove(txFromMempool);
				}
				applicationEventPublisher
						.publishEvent(
								new MempoolTxRemoveEvent(this, txFromMempool,
										MempoolTxRemoveEvent.RemoveReason.MINED));
			}

			// 2. Promotion/Re-validation Phase (only for affected user txs)
			WorldState worldstate = chainHeadStateService.getHeadState();
			List<Address> emptyPools = new ArrayList<>();
			List<MempoolEntry> evictedStaleTxs = new ArrayList<>();
			List<MempoolEntry> newlyPromotedTxs = new ArrayList<>();

			for (Address sender : affectedSenders) {
				SenderAccountPool pool = userTxsBySender.get(sender);
				if (pool == null)
					continue;

				pool.lock.lock();
				try {
					// Get the fresh, confirmed nonce from the new chain state
					AccountNonceState newNonceState = worldstate.getNonce(pool.getSenderAddress());
					long newChainNonce = newNonceState.getNonce();

					// This method evicts stale txs and promotes future txs
					List<MempoolEntry> staleTxs = pool.updateChainNonceAndPromote(newChainNonce, newlyPromotedTxs);
					evictedStaleTxs.addAll(staleTxs);

					if (pool.isEmpty()) {
						emptyPools.add(pool.getSenderAddress());
					}
				} finally {
					pool.lock.unlock();
				}
			}

			// 3. Cleanup Phase
			// Remove pools that are now empty
			emptyPools.forEach(userTxsBySender::remove);

			// Remove newly stale txs from master map and governance
			for (MempoolEntry staleTx : evictedStaleTxs) {
				allTxsByHash.remove(staleTx.getHash());
				allTxsByFee.remove(staleTx);
				executableTxsByFee.remove(staleTx);
				updateGovernanceSets(staleTx, false);
				applicationEventPublisher.publishEvent(
						new MempoolTxRemoveEvent(this, staleTx, MempoolTxRemoveEvent.RemoveReason.STALE_NONCE));
			}
			executableTxsByFee.addAll(newlyPromotedTxs);

			log.debug("Mempool: Processed new block. Updated {} pools. {} accounts remaining.",
					affectedSenders.size(), userTxsBySender.size());

		} finally {
			globalLock.writeLock().unlock();
		}
	}

	/**
	 * Adds transactions from disconnected blocks back into the mempool.
	 * This is part of the event-driven reorg logic.
	 *
	 * @param txsToReAdd
	 *            Transactions from disconnected blocks.
	 */

	/**
	 *
	 * Iterates through the entire mempool and removes transactions older than
	 * 'cutoffTime'.
	 * This operation is performance-critical (O(N)) and locks the entire mempool.
	 *
	 * @param cutoffTime
	 *            Time, before which transactions are considered expired.
	 * @return List of transactions that were removed.
	 */
	public List<MempoolEntry> pruneExpiredTransactions(@NonNull Instant cutoffTime) {
		globalLock.writeLock().lock();
		try {
			log.warn("Mempool: Starting pruning of transactions older than {}. This will lock the mempool.",
					cutoffTime);

			List<MempoolEntry> expiredTxs = allTxsByHash.values().stream()
					.filter(tx -> tx.getFirstSeenTime() != null && tx.getFirstSeenTime().isBefore(cutoffTime))
					.toList();

			if (expiredTxs.isEmpty()) {
				log.debug("Mempool: Pruning complete. No expired transactions found.");
				return Collections.emptyList();
			}

			log.warn("Mempool: Found {} expired transactions to prune.", expiredTxs.size());

			for (MempoolEntry tx : expiredTxs) {
				allTxsByHash.remove(tx.getHash());
				allTxsByFee.remove(tx);
				executableTxsByFee.remove(tx);
				updateGovernanceSets(tx, false);

				if (tx.getTx().getSender() != null) {
					SenderAccountPool pool = userTxsBySender.get(tx.getTx().getSender());
					if (pool != null) {
						pool.lock.lock();
						try {
							pool.removeTxs(Set.of(tx.getNonce()));
							if (pool.isEmpty()) {
								userTxsBySender.remove(tx.getTx().getSender());
							}
						} finally {
							pool.lock.unlock();
						}
					}
				} else {
					systemTxs.remove(tx);
				}
				applicationEventPublisher
						.publishEvent(
								new MempoolTxRemoveEvent(this, tx, MempoolTxRemoveEvent.RemoveReason.EXPIRED));
			}

			log.warn("Mempool: Pruning complete. Evicted {} transactions.", expiredTxs.size());
			return expiredTxs;
		} finally {
			globalLock.writeLock().unlock();
		}
	}

	// =================================================================================
	// === PUBLIC GETTERS (For ValidationService) ===
	// =================================================================================

	public boolean isAuthorityAddPending(Address address) {
		return pendingAuthorityAdds.contains(address);
	}

	public boolean isAuthorityRemovePending(Address address) {
		return pendingAuthorityRemoves.contains(address);
	}

	public boolean isAddressAliasAddPending(String alias) {
		return pendingAddressAliasAdds.contains(alias);
	}

	public boolean isAddressAliasRemovePending(String alias) {
		return pendingAddressAliasRemoves.contains(alias);
	}

	public boolean hasAuthorityPendingParamChange(Address authority) {
		return authoritiesWithPendingParamChange.contains(authority);
	}

	public Optional<MempoolEntry> getTxByHash(Hash hash) {
		return Optional.ofNullable(allTxsByHash.get(hash));
	}

	/**
	 * Checks if a specific authority already has a vote for a specific BIP
	 * pending in the mempool.
	 *
	 * @param bipHash
	 *            The hash of the BIP being voted on.
	 * @param voter
	 *            The address of the authority.
	 * @return true if a vote is already pending, false otherwise.
	 */
	public boolean isBipVotePending(Hash bipHash, Address voter) {
		Set<Address> voters = pendingBipVotes.get(bipHash);
		if (voters == null) {
			return false;
		}
		return voters.contains(voter);
	}

	public List<MempoolEntry> getAllTxs() {
		return new ArrayList<>(allTxsByHash.values());
	}

	public List<Hash> getAllTxHashes() {
		return new ArrayList<>(allTxsByHash.keySet());
	}

	public long getCount() {
		return allTxsByHash.size();
	}

	/**
	 * Returns fee statistics from the current mempool.
	 * Used for calculating recommended transaction fees.
	 *
	 * @return FeeStatistics record with median and fast (80th percentile) fee per
	 *         byte.
	 */
	public FeeStatistics getFeeStatistics() {
		// Get all executable transactions sorted by fee (highest first)
		List<MempoolEntry> sortedTxs = new ArrayList<>(executableTxsByFee);

		if (sortedTxs.isEmpty()) {
			// Empty mempool - return zero statistics
			return new FeeStatistics(0.0, 0.0, 0);
		}

		// Calculate fee per byte for each transaction
		List<Double> feesPerByte = sortedTxs.stream()
				.map(MempoolStore::calculateFeePerByte)
				.filter(fee -> fee > 0)
				.sorted(Comparator.reverseOrder()) // Highest first
				.toList();

		if (feesPerByte.isEmpty()) {
			return new FeeStatistics(0.0, 0.0, sortedTxs.size());
		}

		int size = feesPerByte.size();

		// Median (50th percentile)
		double medianFeePerByte;
		int midIndex = size / 2;
		if (size % 2 == 0) {
			medianFeePerByte = (feesPerByte.get(midIndex - 1) + feesPerByte.get(midIndex)) / 2.0;
		} else {
			medianFeePerByte = feesPerByte.get(midIndex);
		}

		// Fast fee (20th percentile from top = 80th percentile overall)
		// This means 20% of transactions have higher fees
		int fastIndex = (int) Math.ceil(size * 0.2) - 1;
		if (fastIndex < 0)
			fastIndex = 0;
		double fastFeePerByte = feesPerByte.get(fastIndex);

		return new FeeStatistics(medianFeePerByte, fastFeePerByte, size);
	}

	/**
	 * Record holding fee statistics from mempool.
	 */
	public record FeeStatistics(
			double medianFeePerByte,
			double fastFeePerByte,
			int txCount) {
	}

	/**
	 * Returns all pending transactions for a specific sender address.
	 */
	public List<MempoolEntry> getTxsBySender(Address sender) {
		SenderAccountPool pool = userTxsBySender.get(sender);
		if (pool == null) {
			return Collections.emptyList();
		}
		pool.lock.lock();
		try {
			List<MempoolEntry> result = new ArrayList<>();
			result.addAll(pool.getExecutableTxs().values());
			result.addAll(pool.getFutureTxs().values());
			return result;
		} finally {
			pool.lock.unlock();
		}
	}

	/**
	 * Returns the count of pending transactions for a specific sender address.
	 */
	public int getPendingTxCount(Address sender) {
		SenderAccountPool pool = userTxsBySender.get(sender);
		if (pool == null) {
			return 0;
		}
		pool.lock.lock();
		try {
			return pool.getExecutableTxs().size() + pool.getFutureTxs().size();
		} finally {
			pool.lock.unlock();
		}
	}

	public boolean isFull() {
		return allTxsByHash.size() >= mempoolProperties.getMaxSize();
	}

	/**
	 * Forcibly removes a single transaction from all pools.
	 * This is called when a tx is found to be invalid *before* being mined.
	 */
	public void removeTransaction(@NonNull Hash txHash) {
		globalLock.writeLock().lock(); // ZAMKNUTIE
		try {
			// EVERYTHING must be inside try block
			MempoolEntry tx = allTxsByHash.get(txHash);

			if (tx == null) {
				return; // Now it's safe, finally block will execute
			}

			allTxsByHash.remove(tx.getHash());
			allTxsByFee.remove(tx);
			executableTxsByFee.remove(tx);
			updateGovernanceSets(tx, false);

			if (tx.getTx().getSender() != null) {
				SenderAccountPool pool = userTxsBySender.get(tx.getTx().getSender());
				if (pool != null) {
					pool.lock.lock();
					try {
						pool.removeTxs(Set.of(tx.getNonce()));
						if (pool.isEmpty()) {
							userTxsBySender.remove(tx.getTx().getSender());
						}
					} finally {
						pool.lock.unlock();
					}
				}
			} else {
				systemTxs.remove(tx);
			}
			applicationEventPublisher.publishEvent(
					new MempoolTxRemoveEvent(this, tx, MempoolTxRemoveEvent.RemoveReason.INVALID));

		} finally {
			globalLock.writeLock().unlock();
		}
	}

	/**
	 * Batch removal of transactions.
	 * Highly optimized to acquire locks minimally.
	 */
	public void removeTransactions(@NonNull List<Hash> txHashes) {
		if (txHashes.isEmpty()) {
			return;
		}
		List<MempoolEntry> removedEntries = new ArrayList<>(txHashes.size());
		globalLock.writeLock().lock();
		try {
			Map<Address, Set<Long>> noncesToRemoveBySender = new HashMap<>();
			for (Hash hash : txHashes) {
				MempoolEntry tx = allTxsByHash.remove(hash);
				if (tx == null) {
					continue;
				}

				allTxsByFee.remove(tx);
				executableTxsByFee.remove(tx);
				updateGovernanceSets(tx, false);
				removedEntries.add(tx);
				if (tx.getTx().getSender() != null) {
					noncesToRemoveBySender
							.computeIfAbsent(tx.getTx().getSender(), k -> new HashSet<>())
							.add(tx.getNonce());
				} else {
					systemTxs.remove(tx);
				}
			}
			for (Map.Entry<Address, Set<Long>> entry : noncesToRemoveBySender.entrySet()) {
				Address sender = entry.getKey();
				Set<Long> nonces = entry.getValue();

				SenderAccountPool pool = userTxsBySender.get(sender);
				if (pool != null) {
					pool.lock.lock();
					try {
						pool.removeTxs(nonces);
						if (pool.isEmpty()) {
							userTxsBySender.remove(sender);
						}
					} finally {
						pool.lock.unlock();
					}
				}
			}
			log.debug("Batch removed {} transactions from mempool.", removedEntries.size());

		} finally {
			globalLock.writeLock().unlock();
		}
		for (MempoolEntry tx : removedEntries) {
			try {
				applicationEventPublisher.publishEvent(
						new MempoolTxRemoveEvent(this, tx, MempoolTxRemoveEvent.RemoveReason.INVALID));
			} catch (Exception e) {
				log.error("Failed to publish remove event for tx {}", tx.getHash(), e);
			}
		}
	}

	public void clear() {
		globalLock.writeLock().lock();
		try {
			allTxsByHash.clear();
			allTxsByFee.clear();
			executableTxsByFee.clear();
			userTxsBySender.clear();
			systemTxs.clear();

			// Clear governance sets as well
			pendingAuthorityAdds.clear();
			pendingAuthorityRemoves.clear();
			pendingAddressAliasAdds.clear();
			pendingAddressAliasRemoves.clear();
			authoritiesWithPendingParamChange.clear();
			pendingBipVotes.clear();

			log.warn("Mempool cleared manually.");
		} finally {
			globalLock.writeLock().unlock();
		}
	}

	// =================================================================================
	// === PRIVATE HELPERS ===
	// =================================================================================

	/**
	 * Helper to safely evict a victim transaction when the pool is full.
	 */
	private void evictTransaction(MempoolEntry victim, List<MempoolTxRemoveEvent> eventsCollector) {
		allTxsByHash.remove(victim.getHash());
		allTxsByFee.remove(victim);
		executableTxsByFee.remove(victim);
		updateGovernanceSets(victim, false);

		if (victim.getTx().getSender() != null) {
			SenderAccountPool pool = userTxsBySender.get(victim.getTx().getSender());
			if (pool != null) {
				pool.lock.lock();
				try {
					pool.removeTxs(Set.of(victim.getNonce()));
					// We don't remove the pool here to avoid concurrency issues inside
					// addTransaction
				} finally {
					pool.lock.unlock();
				}
			}
		} else {
			systemTxs.remove(victim);
		}

		eventsCollector.add(new MempoolTxRemoveEvent(this, victim, MempoolTxRemoveEvent.RemoveReason.EVICTED_FULL));
	}

	/**
	 * Adds a validated transaction to the appropriate pool.
	 *
	 * @param tx
	 *            The validated transaction.
	 * @param currentChainNonce
	 *            The current confirmed nonce (from chain state) for
	 *            the
	 *            sender.
	 *            -1 if the tx is a "system tx" (sender ==
	 *            null).
	 * @return The result of the add operation.
	 */
	private StorageAddResult addTransactionInternal(@NonNull MempoolEntry tx, long currentChainNonce,
			List<MempoolTxRemoveEvent> eventsToPublish) {
		globalLock.readLock().lock();
		try {
			if (allTxsByHash.putIfAbsent(tx.getHash(), tx) != null) {
				return StorageAddResult.DUPLICATE_HASH;
			}
			allTxsByFee.add(tx);
			StorageAddResult result;
			List<MempoolEntry> txsToAdd = new ArrayList<>();
			List<MempoolEntry> txsToRemove = new ArrayList<>();
			if (tx.getTx().getSender() != null) {
				SenderAccountPool pool = userTxsBySender.computeIfAbsent(
						tx.getTx().getSender(),
						k -> new SenderAccountPool(mempoolProperties, tx.getTx().getSender(), currentChainNonce));

				pool.lock.lock();
				try {
					result = pool.addTransaction(tx, txsToAdd, txsToRemove);
				} finally {
					pool.lock.unlock();
				}
			} else {
				systemTxs.add(tx);
				result = StorageAddResult.ADDED_EXECUTABLE;
			}

			executableTxsByFee.removeAll(txsToRemove);
			executableTxsByFee.addAll(txsToAdd);
			txsToRemove.forEach(allTxsByFee::remove);

			for (MempoolEntry rbfTx : txsToRemove) {
				eventsToPublish.add(new MempoolTxRemoveEvent(this, rbfTx, MempoolTxRemoveEvent.RemoveReason.RBF));
			}

			if (result.isSuccess()) {
				updateGovernanceSets(tx, true);

				// --- LIMIT CHECK ---
				if (isFull()) {
					if (result == StorageAddResult.ADDED_FUTURE) {
						evictTransaction(tx, eventsToPublish);
						return StorageAddResult.MEMPOOL_FULL;
					}
					MempoolEntry victim = allTxsByFee.last();
					if (victim.equals(tx)) {
						evictTransaction(tx, eventsToPublish);
						return StorageAddResult.MEMPOOL_FULL;
					}
					evictTransaction(victim, eventsToPublish);
				}
			} else {
				allTxsByHash.remove(tx.getHash());
				allTxsByFee.remove(tx);
				executableTxsByFee.remove(tx);
			}
			return result;
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public void addTransactionsBack(@NonNull List<Tx> txs, Block block) {
		List<MempoolTxRemoveEvent> eventsToPublishRemove = new ArrayList<>();
		List<MempoolTxAddEvent> eventsToPublishAdd = new ArrayList<>();

		globalLock.writeLock().lock();
		try {
			log.warn("Mempool: Re-adding {} txs from disconnected blocks.", txs.size());
			WorldState worldstate = chainHeadStateService.getHeadState();
			Map<Address, Long> newNonces = new ConcurrentHashMap<>();

			for (Tx tx : txs) {
				MempoolEntry entry = new MempoolEntry(tx);
				entry.setFirstSeenHeight(block.getHeight());
				entry.setFirstSeenTime(block.getHeader().getTimestamp());

				long chainNonce = -1;
				if (tx.getSender() != null) {
					chainNonce = newNonces.computeIfAbsent(
							tx.getSender(),
							k -> worldstate.getNonce(k).getNonce());
				}
				StorageAddResult result = addTransactionInternal(entry, chainNonce, eventsToPublishRemove);
				if (result.isSuccess()) {
					eventsToPublishAdd.add(new MempoolTxAddEvent(this, entry, MempoolTxAddEvent.AddReason.REORG));
				}
			}
		} finally {
			globalLock.writeLock().unlock();
		}
		for (MempoolTxRemoveEvent event : eventsToPublishRemove) {
			applicationEventPublisher.publishEvent(event);
		}
		for (MempoolTxAddEvent event : eventsToPublishAdd) {
			applicationEventPublisher.publishEvent(event);
		}
	}

	/**
	 * Helper to add/remove a tx from the governance sets.
	 */
	private void updateGovernanceSets(MempoolEntry tx, boolean isAdding) {
		// --- BIP Creation ---
		if (tx.getTx().getType() == TxType.BIP_CREATE && tx.getTx().getPayload() != null) {
			TxPayload payload = tx.getTx().getPayload();

			if (payload instanceof TxBipAuthorityAddPayload) {
				Address addr = ((TxBipAuthorityAddPayload) tx.getTx().getPayload()).getAddress();
				if (isAdding) {
					pendingAuthorityAdds.add(addr);
				} else {
					pendingAuthorityAdds.remove(addr);
				}
			} else if (payload instanceof TxBipAuthorityRemovePayload) {
				Address addr = ((TxBipAuthorityRemovePayload) payload).getAddress();
				if (isAdding) {
					pendingAuthorityRemoves.add(addr);
				} else {
					pendingAuthorityRemoves.remove(addr);
				}
			} else if (payload instanceof TxBipNetworkParamsSetPayload) {
				Address sender = tx.getTx().getSender();
				if (isAdding) {
					authoritiesWithPendingParamChange.add(sender);
				} else {
					authoritiesWithPendingParamChange.remove(sender);
				}
			} else if (payload instanceof TxBipAddressAliasAddPayload) {
				String alias = ((TxBipAddressAliasAddPayload) payload).getAlias();
				if (isAdding) {
					pendingAddressAliasAdds.add(alias);
				} else {
					pendingAddressAliasRemoves.remove(alias);
				}
			} else if (payload instanceof TxBipAddressAliasRemovePayload) {
				String alias = ((TxBipAddressAliasRemovePayload) payload).getAlias();
				if (isAdding) {
					pendingAddressAliasRemoves.add(alias);
				} else {
					pendingAddressAliasRemoves.remove(alias);
				}
			}
			return;
		}

		if (tx.getTx().getType() == TxType.BIP_VOTE && tx.getTx().getReferenceHash() != null
				&& tx.getTx().getSender() != null) {
			Hash bipHash = tx.getTx().getReferenceHash();
			Address voter = tx.getTx().getSender();

			if (isAdding) {
				// Get or create the set for this BIP Hash, then add the voter
				// We must use a thread-safe set inside the map
				pendingBipVotes.computeIfAbsent(bipHash, k -> ConcurrentHashMap.newKeySet()).add(voter);
			} else {
				// Remove the voter from the set
				Set<Address> voters = pendingBipVotes.get(bipHash);
				if (voters != null) {
					voters.remove(voter);
					// Clean up the map if the set is now empty
					if (voters.isEmpty()) {
						pendingBipVotes.remove(bipHash);
					}
				}
			}
		}
	}

	// =================================================================================
	// === INNER CLASS: SenderAccountPool ===
	// =================================================================================

	/**
	 * Represents the pool of transactions for a *single* sender.
	 * This class is NOT thread-safe on its own; it *must* be protected
	 * by its external 'lock' field.
	 */
	@Getter
	private static class SenderAccountPool {

		final MempoolProperties mempoolProperties;
		final ReentrantLock lock = new ReentrantLock();
		final Address senderAddress;
		long chainNonce; // The last-known *confirmed* nonce

		/**
		 * Transactions that are ready to be included in a block.
		 * Sorted by nonce (key).
		 */
		final TreeMap<Long, MempoolEntry> executableTxs = new TreeMap<>();

		/**
		 * Transactions from this sender with a nonce-gap.
		 * Sorted by nonce (key).
		 */
		final TreeMap<Long, MempoolEntry> futureTxs = new TreeMap<>();

		public SenderAccountPool(MempoolProperties mempoolProperties, Address senderAddress,
				long chainNonce) {
			this.mempoolProperties = mempoolProperties;
			this.senderAddress = senderAddress;
			this.chainNonce = chainNonce;
		}

		/**
		 * Adds a transaction to this pool.
		 * Assumes the caller is holding the pool's lock.
		 *
		 * @param tx
		 *            The transaction to add.
		 * @return The result of the add operation.
		 */
		public StorageAddResult addTransaction(
				MempoolEntry tx,
				List<MempoolEntry> outTxsToAdd,
				List<MempoolEntry> outTxsToRemove) {
			long txNonce = tx.getNonce();

			// 1. Check for stale nonce
			if (txNonce <= this.chainNonce) {
				log.debug("Sender {}: Stale tx {} (nonce {} <= chainNonce {})",
						senderAddress.toChecksumAddress(), tx.getHash().toHexString(), txNonce, this.chainNonce);
				return StorageAddResult.STALE;
			}

			// 2. Check for RBF (Replace-by-Fee)
			MempoolEntry existingTx = executableTxs.get(txNonce);
			if (existingTx == null) {
				existingTx = futureTxs.get(txNonce);
			}

			if (existingTx != null) {
				BigInteger oldFee = existingTx.getTx().getFee().toBigInteger();
				BigInteger newFee = tx.getTx().getFee().toBigInteger();

				// Create the 10% threshold
				BigInteger requiredFee = oldFee.multiply(BigInteger.valueOf(110))
						.divide(BigInteger.valueOf(100));

				// The new fee must be strictly greater than the old fee,
				// AND greater than or equal to the 10% threshold.
				if (newFee.compareTo(oldFee) <= 0 || newFee.compareTo(requiredFee) < 0) {
					log.warn("Sender {}: Rejecting RBF tx {}. New fee {} is not > 10% higher than old fee {}.",
							senderAddress.toChecksumAddress(), tx.getHash().toHexString(), newFee, oldFee);
					return StorageAddResult.FAILED_FEE_TOO_LOW;
				}

				log.debug("Sender {}: RBF tx {} replacing {}",
						senderAddress.toChecksumAddress(), tx.getHash().toShortLogString(),
						existingTx.getHash().toShortLogString());

				// RBF passed: remove existing from wherever it is
				outTxsToRemove.add(existingTx);
				executableTxs.remove(txNonce);
				futureTxs.remove(txNonce);
			}

			// 3. Decide where to place it: executable or future?
			long nextExecutableNonce = getNextExecutableNonce();

			if (txNonce == nextExecutableNonce) {
				// This is the next tx in sequence
				executableTxs.put(txNonce, tx);
				outTxsToAdd.add(tx);
				// 4. CRITICAL: Promote txs from 'future'
				promoteExecutableTxs(outTxsToAdd);
				return StorageAddResult.ADDED_EXECUTABLE;

			} else if (txNonce > nextExecutableNonce) {
				final long MAX_NONCE_GAP = this.mempoolProperties.getMaxNonceGap();

				// Check the gap against the *last confirmed chain nonce*
				if (txNonce > this.chainNonce + MAX_NONCE_GAP) {
					log.warn("Sender {}: Tx nonce {} is too far in the future (max allowed: {}). Rejecting tx {}.",
							senderAddress.toChecksumAddress(), txNonce, this.chainNonce + MAX_NONCE_GAP,
							tx.getHash().toShortLogString());
					return StorageAddResult.NONCE_TOO_FAR_FUTURE;
				}
				// --- ADD THIS CHECK (END) ---
				// This tx has a gap, put it in the 'future' queue
				futureTxs.put(txNonce, tx);
				return StorageAddResult.ADDED_FUTURE;
			} else {
				// txNonce < nextExecutableNonce
				// This means it's stale (e.g., chainNonce=5, executable=7, new=6)
				return StorageAddResult.STALE;
			}
		}

		/**
		 * Moves transactions from 'futureTxs' to 'executableTxs'
		 * as long as they form a continuous sequence.
		 */
		private void promoteExecutableTxs(List<MempoolEntry> outTxsToAdd) {
			long nextNonce = getNextExecutableNonce();
			while (futureTxs.containsKey(nextNonce)) {
				MempoolEntry txToPromote = futureTxs.remove(nextNonce);
				executableTxs.put(nextNonce, txToPromote);
				outTxsToAdd.add(txToPromote);
				nextNonce++;
			}
		}

		/**
		 * Updates the pool based on a new confirmed chain nonce.
		 * Evicts any txs that are now stale.
		 * Returns a list of txs that were evicted.
		 */
		public List<MempoolEntry> updateChainNonceAndPromote(long newChainNonce, List<MempoolEntry> outPromotedTxs) {
			this.chainNonce = newChainNonce;
			List<MempoolEntry> evictedTxs = new ArrayList<>();

			// 1. Evict stale txs from both maps
			evictStale(executableTxs, newChainNonce, evictedTxs);
			evictStale(futureTxs, newChainNonce, evictedTxs);

			// 2. Try to promote
			promoteExecutableTxs(outPromotedTxs);

			return evictedTxs;
		}

		private void evictStale(TreeMap<Long, MempoolEntry> map, long newChainNonce, List<MempoolEntry> evictedTxs) {
			// Get all entries with nonce <= newChainNonce
			SortedMap<Long, MempoolEntry> staleEntries = map.headMap(newChainNonce, true);
			evictedTxs.addAll(staleEntries.values());
			// Use iterator to remove them
			staleEntries.clear();
		}

		/**
		 * Removes a set of transactions by nonce (e.g., they were mined).
		 */
		public void removeTxs(Set<Long> nonces) {
			for (Long nonce : nonces) {
				executableTxs.remove(nonce);
				futureTxs.remove(nonce);
			}
		}

		private long getNextExecutableNonce() {
			if (executableTxs.isEmpty()) {
				return this.chainNonce + 1;
			} else {
				return executableTxs.lastKey() + 1;
			}
		}

		public boolean isEmpty() {
			return executableTxs.isEmpty() && futureTxs.isEmpty();
		}
	}

	// =================================================================================
	// === PUBLIC ENUM: StorageAddResult ===
	// =================================================================================

	/**
	 * Enum to report the result of adding a tx to storage.
	 */
	public enum StorageAddResult {
		ADDED_EXECUTABLE, ADDED_FUTURE, STALE, DUPLICATE_HASH, FAILED_FEE_TOO_LOW, // For RBF
		MEMPOOL_FULL, // Mempool full and tx fee too low
		NONCE_TOO_FAR_FUTURE;

		public boolean isSuccess() {
			return this == ADDED_EXECUTABLE || this == ADDED_FUTURE;
		}
	}

	/**
	 * Calculates the fee-per-byte for sorting transactions.
	 * Uses double for sorting performance.
	 */
	private static double calculateFeePerByte(MempoolEntry tx) {
		if (tx.getSizeInBytes() == 0)
			return 0.0;
		return tx.getFeeAsDouble() / tx.getSizeInBytes();
	}
}