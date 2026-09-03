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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleJournalWriter;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolBoundedScanner;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolCanonicalProjectionAdvance;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Atomic in-memory transaction store. Every mutation updates the hash, fee,
 * sender, executable and governance indexes in one write-locked operation.
 * Authoritative mempool state and lifecycle entries share one RocksDB batch. RAM
 * mutations stay write-locked until that batch commits and before any Spring wake
 * event is published.
 */
@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MempoolStore {

	static Comparator<MempoolEntry> TX_FEE_COMPARATOR = MempoolStore::compareByFeeDensity;

	MeterRegistry registry;
	MempoolProperties mempoolProperties;
	ChainHeadStateCache chainHeadStateService;
	ApplicationEventPublisher applicationEventPublisher;
	MempoolLifecycleJournalWriter lifecycleJournalWriter;
	PersistentMempoolStore persistentMempoolStore;
	MempoolIntegrityGuard integrityGuard;

	ConcurrentSkipListSet<MempoolEntry> executableTxsByFee = new ConcurrentSkipListSet<>(TX_FEE_COMPARATOR);
	ConcurrentSkipListSet<MempoolEntry> allTxsByFee = new ConcurrentSkipListSet<>(TX_FEE_COMPARATOR);
	ConcurrentHashMap<Hash, MempoolEntry> allTxsByHash = new ConcurrentHashMap<>();
	ConcurrentHashMap<Address, SenderAccountPool> userTxsBySender = new ConcurrentHashMap<>();
	ConcurrentLinkedQueue<MempoolEntry> systemTxs = new ConcurrentLinkedQueue<>();

	/* Values identify the owning transaction, so removing one duplicate cannot
	 * accidentally clear another transaction's reservation. */
	ConcurrentHashMap<Address, Hash> pendingAuthorityChanges = new ConcurrentHashMap<>();
	ConcurrentHashMap<Address, Hash> pendingValidatorChanges = new ConcurrentHashMap<>();
	ConcurrentHashMap<String, Hash> pendingAddressAliasChanges = new ConcurrentHashMap<>();
	AtomicReference<Hash> pendingNetworkParamsChange = new AtomicReference<>();
	ConcurrentHashMap<Address, Hash> pendingTokenUpdates = new ConcurrentHashMap<>();
	ConcurrentHashMap<Hash, ConcurrentHashMap<Address, Hash>> pendingBipVotes = new ConcurrentHashMap<>();
	ConcurrentHashMap<Address, BigInteger> pendingTokenMintAmounts = new ConcurrentHashMap<>();
	ConcurrentHashMap<Hash, TokenMintReservation> tokenMintsByHash = new ConcurrentHashMap<>();

	ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock(true);
	ReentrantLock lifecycleMutationLock = new ReentrantLock(true);
	ThreadLocal<LifecycleEventBatch> lifecycleEventBatch = new ThreadLocal<>();

	@Autowired
	public MempoolStore(
			MeterRegistry registry,
			MempoolProperties mempoolProperties,
			ChainHeadStateCache chainHeadStateService,
			ApplicationEventPublisher applicationEventPublisher,
			MempoolLifecycleJournalWriter lifecycleJournalWriter,
			PersistentMempoolStore persistentMempoolStore,
			MempoolIntegrityGuard integrityGuard) {
		this.registry = registry;
		this.mempoolProperties = mempoolProperties;
		this.chainHeadStateService = chainHeadStateService;
		this.applicationEventPublisher = applicationEventPublisher;
		this.lifecycleJournalWriter = lifecycleJournalWriter;
		this.persistentMempoolStore = persistentMempoolStore;
		this.integrityGuard = integrityGuard;
	}

	MempoolStore(
			MeterRegistry registry,
			MempoolProperties mempoolProperties,
			ChainHeadStateCache chainHeadStateService,
			ApplicationEventPublisher applicationEventPublisher,
			MempoolLifecycleJournalWriter lifecycleJournalWriter,
			PersistentMempoolStore persistentMempoolStore) {
		this(registry, mempoolProperties, chainHeadStateService, applicationEventPublisher,
				lifecycleJournalWriter, persistentMempoolStore, new MempoolIntegrityGuard());
	}

	MempoolStore(
			MeterRegistry registry,
			MempoolProperties mempoolProperties,
			ChainHeadStateCache chainHeadStateService,
			ApplicationEventPublisher applicationEventPublisher,
			MempoolLifecycleJournalWriter lifecycleJournalWriter) {
		this(registry, mempoolProperties, chainHeadStateService, applicationEventPublisher,
				lifecycleJournalWriter, null, new MempoolIntegrityGuard());
	}

	MempoolStore(
			MeterRegistry registry,
			MempoolProperties mempoolProperties,
			ChainHeadStateCache chainHeadStateService,
			ApplicationEventPublisher applicationEventPublisher) {
		this(registry, mempoolProperties, chainHeadStateService, applicationEventPublisher,
				MempoolLifecycleJournalWriter.disabled(), null, new MempoolIntegrityGuard());
	}

	@PostConstruct
	public void initMetrics() {
		registry.gaugeMapSize("blockchain.mempool.tx_count", Tags.empty(), allTxsByHash);
		registry.gaugeMapSize("blockchain.mempool.senders_count", Tags.empty(), userTxsBySender);
		registry.gaugeCollectionSize("blockchain.mempool.system_tx_count", Tags.empty(), systemTxs);
		registry.gauge("blockchain.mempool.future_tx_count", Tags.empty(), this,
				store -> store.countFutureTransactions());
	}

	public StorageAddResult addTransaction(@NonNull MempoolEntry entry, long currentChainNonce,
			MempoolTxAddEvent.AddReason reason) {
		return addTransaction(entry, currentChainNonce, reason, null);
	}

	public StorageAddResult addTransaction(@NonNull MempoolEntry entry, long currentChainNonce,
			MempoolTxAddEvent.AddReason reason, AdmissionConstraints constraints) {
		return withLifecycleMutationLock(
				() -> addTransactionOrdered(entry, currentChainNonce, reason, constraints));
	}

	private StorageAddResult addTransactionOrdered(MempoolEntry entry, long currentChainNonce,
			MempoolTxAddEvent.AddReason reason, AdmissionConstraints constraints) {
		List<MempoolTxRemoveEvent> removeEvents = new ArrayList<>();
		StorageAddResult result = addTransactionInternal(entry, currentChainNonce, constraints, removeEvents);
		List<MempoolTxAddEvent> addEvents = result.isSuccess()
				? List.of(new MempoolTxAddEvent(this, entry, reason))
				: List.of();
		publishLifecycleEvents(removeEvents, addEvents);
		return result;
	}

	/**
	 * Batch admission deliberately uses the same implementation as single
	 * admission. The enclosing write lock makes duplicate checks and inserts
	 * atomic while preserving input order for nonce chains and RBF.
	 */
	public Map<Hash, StorageAddResult> addTransactions(List<MempoolEntry> entries,
			Map<Address, Long> senderChainNonces, MempoolTxAddEvent.AddReason reason) {
		return withLifecycleMutationLock(() -> addTransactionsOrdered(entries, senderChainNonces, reason));
	}

	private Map<Hash, StorageAddResult> addTransactionsOrdered(List<MempoolEntry> entries,
			Map<Address, Long> senderChainNonces, MempoolTxAddEvent.AddReason reason) {
		Map<Hash, StorageAddResult> results = new LinkedHashMap<>();
		Map<Hash, MempoolEntry> successful = new LinkedHashMap<>();
		List<MempoolTxRemoveEvent> removeEvents = new ArrayList<>();
		Set<Hash> transientEntries = new HashSet<>();

		globalLock.writeLock().lock();
		try {
			for (MempoolEntry entry : entries) {
				long chainNonce = entry.getTx().getSender() == null
						? -1L
						: senderChainNonces.getOrDefault(entry.getTx().getSender(), 0L);
				int eventStart = removeEvents.size();
				StorageAddResult result = addTransactionInternal(entry, chainNonce, null, removeEvents);
				results.put(entry.getHash(), result);
				if (result.isSuccess()) {
					successful.put(entry.getHash(), entry);
				}
				for (int i = eventStart; i < removeEvents.size(); i++) {
					MempoolTxRemoveEvent event = removeEvents.get(i);
					if (successful.remove(event.getEntry().getHash()) != null) {
						transientEntries.add(event.getEntry().getHash());
						if (event.getReason() == MempoolTxRemoveEvent.RemoveReason.RBF) {
							results.put(event.getEntry().getHash(), StorageAddResult.REPLACED);
						}
					}
					if (event.getReason() == MempoolTxRemoveEvent.RemoveReason.EVICTED_FULL) {
						if (results.containsKey(event.getEntry().getHash())) {
							results.put(event.getEntry().getHash(), StorageAddResult.MEMPOOL_FULL);
						}
					}
				}
			}
		} finally {
			globalLock.writeLock().unlock();
		}

		removeEvents.removeIf(event -> transientEntries.contains(event.getEntry().getHash()));
		List<MempoolTxAddEvent> addEvents = successful.values().stream()
				.map(entry -> new MempoolTxAddEvent(this, entry, reason))
				.toList();
		publishLifecycleEvents(removeEvents, addEvents);
		return results;
	}

	public Iterator<MempoolEntry> getExecutableTransactionsIterator() {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			List<MempoolEntry> snapshot = new ArrayList<>(systemTxs.size() + executableTxsByFee.size());
			snapshot.addAll(systemTxs);
			snapshot.addAll(executableTxsByFee);
			return snapshot.iterator();
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public void processNewBlock(@NonNull List<Tx> txs) {
		withLifecycleMutationLock(() -> processNewBlockOrdered(txs));
	}

	private void processNewBlockOrdered(List<Tx> txs) {
		List<MempoolTxRemoveEvent> events = new ArrayList<>();
		Set<Address> affectedSenders = new HashSet<>();
		globalLock.writeLock().lock();
		try {
			for (Tx tx : txs) {
				if (tx.getSender() != null) {
					affectedSenders.add(tx.getSender());
				}
				MempoolEntry entry = allTxsByHash.get(tx.getHash());
				if (entry != null) {
					removeEntry(entry, MempoolTxRemoveEvent.RemoveReason.MINED, events, false);
				}
			}

			WorldState worldstate = chainHeadStateService.getHeadState();
			for (Address sender : affectedSenders) {
				resynchronizeSenderInternal(sender, worldstate.getNonce(sender).getNonce(), events);
			}
		} finally {
			globalLock.writeLock().unlock();
		}
		publishLifecycleEvents(events, List.of());
	}

	public void addTransactionsBack(@NonNull List<Tx> txs, Block block) {
		withLifecycleMutationLock(() -> addTransactionsBackOrdered(txs, block));
	}

	public void executePersistenceBatch(@NonNull Runnable operation) {
		executePersistenceBatch(null, operation);
	}

	/** Internal atomic boundary for the durable canonical-to-mempool projector. */
	public void executeCanonicalPersistenceBatch(
			@NonNull MempoolCanonicalProjectionAdvance projectionAdvance,
			@NonNull Runnable operation) {
		executePersistenceBatch(projectionAdvance, operation);
	}

	private void executePersistenceBatch(
			MempoolCanonicalProjectionAdvance projectionAdvance,
			Runnable operation) {
		withLifecycleMutationLock(() -> {
			if (lifecycleEventBatch.get() != null) {
				if (projectionAdvance != null) {
					throw new IllegalStateException("Canonical projection batches cannot be nested");
				}
				operation.run();
				return;
			}
			LifecycleEventBatch batch = new LifecycleEventBatch();
			lifecycleEventBatch.set(batch);
			try {
				operation.run();
			} catch (RuntimeException failure) {
				lifecycleEventBatch.remove();
				restoreRamFromPersistent(failure);
				throw failure;
			} finally {
				lifecycleEventBatch.remove();
			}
			publishLifecycleEvents(batch.removals(), batch.additions(), projectionAdvance);
		});
	}

	<T> T executeRecovery(Supplier<T> recovery) {
		return withLifecycleMutationLock(recovery);
	}

	private void addTransactionsBackOrdered(List<Tx> txs, Block block) {
		List<MempoolTxRemoveEvent> removeEvents = new ArrayList<>();
		Map<Hash, MempoolEntry> added = new LinkedHashMap<>();
		globalLock.writeLock().lock();
		try {
			WorldState worldstate = chainHeadStateService.getHeadState();
			Map<Address, Long> chainNonces = new HashMap<>();
			for (Tx tx : txs) {
				MempoolEntry entry = new MempoolEntry(tx);
				entry.setFirstSeenHeight(block.getHeight());
				entry.setFirstSeenTime(block.getHeader().getTimestamp());
				long chainNonce = tx.getSender() == null ? -1L : chainNonces.computeIfAbsent(
						tx.getSender(), sender -> worldstate.getNonce(sender).getNonce());
				if (addTransactionInternal(entry, chainNonce, null, removeEvents).isSuccess()) {
					added.put(entry.getHash(), entry);
				}
			}
		} finally {
			globalLock.writeLock().unlock();
		}
		Set<Hash> transientEntries = removeEvents.stream()
				.map(event -> event.getEntry().getHash())
				.filter(added::containsKey)
				.collect(Collectors.toSet());
		removeEvents.removeIf(event -> transientEntries.contains(event.getEntry().getHash()));
		transientEntries.forEach(added::remove);
		List<MempoolTxAddEvent> addEvents = added.values().stream()
				.map(entry -> new MempoolTxAddEvent(this, entry, MempoolTxAddEvent.AddReason.REORG))
				.toList();
		publishLifecycleEvents(removeEvents, addEvents);
	}

	public List<MempoolEntry> pruneExpiredTransactions(@NonNull Instant cutoffTime) {
		return withLifecycleMutationLock(() -> pruneExpiredTransactionsOrdered(cutoffTime));
	}

	private List<MempoolEntry> pruneExpiredTransactionsOrdered(Instant cutoffTime) {
		List<MempoolTxRemoveEvent> events = new ArrayList<>();
		List<MempoolEntry> expired;
		globalLock.writeLock().lock();
		try {
			expired = allTxsByHash.values().stream()
					.filter(entry -> entry.getFirstSeenTime() != null
							&& entry.getFirstSeenTime().isBefore(cutoffTime))
					.toList();
			for (MempoolEntry entry : expired) {
				removeEntry(entry, MempoolTxRemoveEvent.RemoveReason.EXPIRED, events, false);
			}
		} finally {
			globalLock.writeLock().unlock();
		}
		publishLifecycleEvents(events, List.of());
		return expired.isEmpty() ? Collections.emptyList() : expired;
	}

	public boolean isAuthorityAddPending(Address address) {
		return withReadLock(() -> pendingAuthorityChanges.containsKey(address));
	}

	public boolean isAuthorityRemovePending(Address address) {
		return withReadLock(() -> pendingAuthorityChanges.containsKey(address));
	}

	public boolean isValidatorAddPending(Address address) {
		return withReadLock(() -> pendingValidatorChanges.containsKey(address));
	}

	public boolean isValidatorRemovePending(Address address) {
		return withReadLock(() -> pendingValidatorChanges.containsKey(address));
	}

	public boolean isValidatorMiningPolicyChangePending(Address address) {
		return withReadLock(() -> pendingValidatorChanges.containsKey(address));
	}

	public boolean isAddressAliasAddPending(String alias) {
		return withReadLock(() -> pendingAddressAliasChanges.containsKey(alias));
	}

	public boolean isAddressAliasRemovePending(String alias) {
		return withReadLock(() -> pendingAddressAliasChanges.containsKey(alias));
	}

	public boolean isNetworkParamsChangePending() {
		return withReadLock(() -> pendingNetworkParamsChange.get() != null);
	}

	public boolean isTokenUpdatePending(Address tokenAddress) {
		return withReadLock(() -> pendingTokenUpdates.containsKey(tokenAddress));
	}

	public boolean isBipVotePending(Hash bipHash, Address voter) {
		return withReadLock(() -> {
			Map<Address, Hash> voters = pendingBipVotes.get(bipHash);
			return voters != null && voters.containsKey(voter);
		});
	}

	public BigInteger getPendingTokenMintAmount(Address tokenAddress, Hash ignoredHash) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			BigInteger total = pendingTokenMintAmounts.getOrDefault(tokenAddress, BigInteger.ZERO);
			if (ignoredHash == null) {
				return total;
			}
			TokenMintReservation ignored = tokenMintsByHash.get(ignoredHash);
			return ignored != null && ignored.tokenAddress().equals(tokenAddress)
					? total.subtract(ignored.amount())
					: total;
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public ReservationSnapshot nativeReservation(Address sender, Tx candidate, Wei available) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			SenderAccountPool pool = userTxsBySender.get(sender);
			BigInteger reserved = BigInteger.ZERO;
			BigInteger replacing = BigInteger.ZERO;
			if (pool != null) {
				pool.lock.lock();
				try {
					reserved = pool.reservedNative;
					MempoolEntry existing = pool.get(candidate.getNonce());
					if (existing != null) {
						replacing = nativeCost(existing.getTx());
					}
				} finally {
					pool.lock.unlock();
				}
			}
			return reservationSnapshot(reserved, replacing, nativeCost(candidate), available);
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public ReservationSnapshot tokenReservation(Address sender, Address tokenAddress, Tx candidate, Wei available) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			SenderAccountPool pool = userTxsBySender.get(sender);
			BigInteger reserved = BigInteger.ZERO;
			BigInteger replacing = BigInteger.ZERO;
			if (pool != null) {
				pool.lock.lock();
				try {
					reserved = pool.reservedTokens.getOrDefault(tokenAddress, BigInteger.ZERO);
					MempoolEntry existing = pool.get(candidate.getNonce());
					if (existing != null) {
						replacing = tokenCost(existing.getTx(), tokenAddress);
					}
				} finally {
					pool.lock.unlock();
				}
			}
			return reservationSnapshot(reserved, replacing, tokenCost(candidate, tokenAddress), available);
		} finally {
			globalLock.readLock().unlock();
		}
	}

	/**
	 * Keeps the lowest-nonce prefix affordable by the supplied confirmed balances.
	 * A missing custom-token balance means that token was not loaded and is not
	 * reconciled by this call.
	 */
	public void reconcileSenderBalances(@NonNull Map<Address, SenderBalances> balancesBySender) {
		withLifecycleMutationLock(() -> reconcileSenderBalancesOrdered(balancesBySender));
	}

	private void reconcileSenderBalancesOrdered(Map<Address, SenderBalances> balancesBySender) {
		List<MempoolTxRemoveEvent> events = new ArrayList<>();
		globalLock.writeLock().lock();
		try {
			for (Map.Entry<Address, SenderBalances> item : balancesBySender.entrySet()) {
				SenderAccountPool pool = userTxsBySender.get(item.getKey());
				if (pool == null) {
					continue;
				}
				pool.lock.lock();
				try {
					SenderBalances balances = item.getValue();
					BigInteger nativeUsed = BigInteger.ZERO;
					Map<Address, BigInteger> tokenUsed = new HashMap<>();
					Long unaffordableNonce = null;
					for (Map.Entry<Long, MempoolEntry> pending : pool.allTransactions().entrySet()) {
						Tx tx = pending.getValue().getTx();
						nativeUsed = nativeUsed.add(nativeCost(tx));
						boolean affordable = nativeUsed.compareTo(balances.nativeBalance().toBigInteger()) <= 0;
						if (tx.getType() == TxType.TRANSFER && tx.getTokenAddress() != null
								&& !Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
							BigInteger used = tokenUsed.merge(tx.getTokenAddress(), amount(tx), BigInteger::add);
							Wei tokenBalance = balances.tokenBalances().get(tx.getTokenAddress());
							affordable &= tokenBalance == null || used.compareTo(tokenBalance.toBigInteger()) <= 0;
						}
						if (!affordable) {
							unaffordableNonce = pending.getKey();
							break;
						}
					}
					if (unaffordableNonce != null) {
						for (MempoolEntry removed : pool.removeFromNonce(unaffordableNonce)) {
							removeGlobalIndexes(removed);
							events.add(new MempoolTxRemoveEvent(
									this, removed, MempoolTxRemoveEvent.RemoveReason.INSUFFICIENT_FUNDS));
						}
						refreshExecutableIndex(pool);
					}
					if (pool.isEmpty()) {
						userTxsBySender.remove(item.getKey(), pool);
					}
				} finally {
					pool.lock.unlock();
				}
			}
		} finally {
			globalLock.writeLock().unlock();
		}
		publishLifecycleEvents(events, List.of());
	}

	public record ReservationSnapshot(
			Wei reserved,
			Wei replacing,
			Wei candidate,
			Wei required,
			Wei available) {
		public boolean affordable() {
			return required.compareTo(available) <= 0;
		}
	}

	public record SenderBalances(Wei nativeBalance, Map<Address, Wei> tokenBalances) {
		public SenderBalances {
			tokenBalances = Map.copyOf(tokenBalances);
		}
	}

	public record AdmissionConstraints(
			Wei nativeBalance,
			Map<Address, Wei> tokenBalances,
			MintSupplyConstraint mintSupply) {
		public AdmissionConstraints {
			tokenBalances = Map.copyOf(tokenBalances);
		}
	}

	public record MintSupplyConstraint(Address tokenAddress, BigInteger maxPendingAmount) {
	}

	/** Returns whether entry conflicts with a different pending governance tx. */
	public boolean hasGovernanceConflict(@NonNull MempoolEntry entry, Hash ignoredHash) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			return hasGovernanceConflictInternal(entry, ignoredHash);
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public Optional<MempoolEntry> getTransactionBySenderAndNonce(Address sender, long nonce) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			SenderAccountPool pool = userTxsBySender.get(sender);
			if (pool == null) {
				return Optional.empty();
			}
			pool.lock.lock();
			try {
				return Optional.ofNullable(pool.get(nonce));
			} finally {
				pool.lock.unlock();
			}
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public Optional<MempoolEntry> getTxByHash(Hash hash) {
		return withReadLock(() -> Optional.ofNullable(allTxsByHash.get(hash)));
	}

	public List<MempoolEntry> getAllTxs() {
		return withReadLock(() -> new ArrayList<>(allTxsByHash.values()));
	}

	public List<Hash> getAllTxHashes() {
		return withReadLock(() -> new ArrayList<>(allTxsByHash.keySet()));
	}

	public long getCount() {
		return withReadLock(allTxsByHash::size);
	}

	public FeeStatistics getFeeStatistics() {
		return withReadLock(() -> {
			List<Double> feesPerByte = executableTxsByFee.stream()
					.map(MempoolStore::calculateFeePerByte)
					.filter(fee -> fee > 0)
					.sorted(Comparator.reverseOrder())
					.toList();
			if (feesPerByte.isEmpty()) {
				return new FeeStatistics(0.0, 0.0, 0);
			}
			int size = feesPerByte.size();
			int middle = size / 2;
			double median = size % 2 == 0
					? (feesPerByte.get(middle - 1) + feesPerByte.get(middle)) / 2.0
					: feesPerByte.get(middle);
			int fastIndex = Math.max(0, (int) Math.ceil(size * 0.2) - 1);
			return new FeeStatistics(median, feesPerByte.get(fastIndex), size);
		});
	}

	public record FeeStatistics(double medianFeePerByte, double fastFeePerByte, int txCount) {
	}

	public List<MempoolEntry> getTxsBySender(Address sender) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			SenderAccountPool pool = userTxsBySender.get(sender);
			if (pool == null) {
				return Collections.emptyList();
			}
			pool.lock.lock();
			try {
				return new ArrayList<>(pool.allTransactions().values());
			} finally {
				pool.lock.unlock();
			}
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public int getPendingTxCount(Address sender) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			SenderAccountPool pool = userTxsBySender.get(sender);
			if (pool == null) {
				return 0;
			}
			pool.lock.lock();
			try {
				return pool.size();
			} finally {
				pool.lock.unlock();
			}
		} finally {
			globalLock.readLock().unlock();
		}
	}

	public long getNextAvailableNonce(Address sender, long confirmedNonce) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			SenderAccountPool pool = userTxsBySender.get(sender);
			if (pool == null) {
				return confirmedNonce + 1;
			}
			pool.lock.lock();
			try {
				long nonce = confirmedNonce + 1;
				while (pool.get(nonce) != null) {
					nonce++;
				}
				return nonce;
			} finally {
				pool.lock.unlock();
			}
		} finally {
			globalLock.readLock().unlock();
		}
	}

	/** Full means no free slot remains; insertion evicts only when size exceeds max. */
	public boolean isFull() {
		return withReadLock(() -> allTxsByHash.size() >= mempoolProperties.getMaxSize());
	}

	public void removeTransaction(@NonNull Hash txHash) {
		withLifecycleMutationLock(() -> removeTransactionOrdered(txHash));
	}

	private void removeTransactionOrdered(Hash txHash) {
		List<MempoolTxRemoveEvent> events = new ArrayList<>();
		globalLock.writeLock().lock();
		try {
			MempoolEntry entry = allTxsByHash.get(txHash);
			if (entry != null) {
				removeEntry(entry, MempoolTxRemoveEvent.RemoveReason.INVALID, events, false);
			}
		} finally {
			globalLock.writeLock().unlock();
		}
		publishLifecycleEvents(events, List.of());
	}

	public void removeTransactions(@NonNull List<Hash> txHashes) {
		withLifecycleMutationLock(() -> removeTransactionsOrdered(txHashes));
	}

	private void removeTransactionsOrdered(List<Hash> txHashes) {
		List<MempoolTxRemoveEvent> events = new ArrayList<>();
		globalLock.writeLock().lock();
		try {
			for (Hash hash : new LinkedHashSet<>(txHashes)) {
				MempoolEntry entry = allTxsByHash.get(hash);
				if (entry != null) {
					removeEntry(entry, MempoolTxRemoveEvent.RemoveReason.INVALID, events, false);
				}
			}
		} finally {
			globalLock.writeLock().unlock();
		}
		publishLifecycleEvents(events, List.of());
	}

	/**
	 * Synchronizes a sender with chain state in either direction. Stale entries are
	 * removed, gaps are repaired, and the global executable index is rebuilt.
	 */
	public void resynchronizeSender(@NonNull Address sender, long currentChainNonce) {
		withLifecycleMutationLock(() -> resynchronizeSenderOrdered(sender, currentChainNonce));
	}

	private void resynchronizeSenderOrdered(Address sender, long currentChainNonce) {
		List<MempoolTxRemoveEvent> events = new ArrayList<>();
		globalLock.writeLock().lock();
		try {
			resynchronizeSenderInternal(sender, currentChainNonce, events);
		} finally {
			globalLock.writeLock().unlock();
		}
		publishLifecycleEvents(events, List.of());
	}

	public void resynchronizeSenders(@NonNull Map<Address, Long> chainNonces) {
		withLifecycleMutationLock(() -> resynchronizeSendersOrdered(chainNonces));
	}

	private void resynchronizeSendersOrdered(Map<Address, Long> chainNonces) {
		List<MempoolTxRemoveEvent> events = new ArrayList<>();
		globalLock.writeLock().lock();
		try {
			chainNonces.forEach((sender, nonce) -> resynchronizeSenderInternal(sender, nonce, events));
		} finally {
			globalLock.writeLock().unlock();
		}
		publishLifecycleEvents(events, List.of());
	}

	public void clear() {
		withLifecycleMutationLock(this::clearOrdered);
	}

	void resetDerivedStateForRecovery() {
		withLifecycleMutationLock(this::clearDerivedState);
	}

	RecoveryAddResult restoreTransaction(
			MempoolEntry entry,
			long currentChainNonce,
			AdmissionConstraints constraints) {
		return withLifecycleMutationLock(() -> {
			List<MempoolTxRemoveEvent> removals = new ArrayList<>();
			StorageAddResult result = addTransactionInternal(
					entry, currentChainNonce, constraints, removals);
			return new RecoveryAddResult(result, removals);
		});
	}

	record RecoveryAddResult(StorageAddResult result, List<MempoolTxRemoveEvent> removals) {
		RecoveryAddResult {
			removals = List.copyOf(removals);
		}
	}

	private void clearOrdered() {
		List<MempoolEntry> removed;
		globalLock.writeLock().lock();
		try {
			removed = new ArrayList<>(allTxsByHash.values());
			clearDerivedState();
		} finally {
			globalLock.writeLock().unlock();
		}
		List<MempoolTxRemoveEvent> events = removed.stream()
				.map(entry -> new MempoolTxRemoveEvent(
						this, entry, MempoolTxRemoveEvent.RemoveReason.INVALID))
				.toList();
		publishLifecycleEvents(events, List.of());
	}

	private StorageAddResult addTransactionInternal(MempoolEntry entry, long currentChainNonce,
			AdmissionConstraints constraints, List<MempoolTxRemoveEvent> events) {
		globalLock.writeLock().lock();
		try {
			if (allTxsByHash.containsKey(entry.getHash())) {
				return StorageAddResult.DUPLICATE_HASH;
			}

			Address sender = entry.getTx().getSender();
			SenderAccountPool pool = null;
			MempoolEntry replacement = null;
			if (sender != null) {
				pool = userTxsBySender.computeIfAbsent(sender,
						ignored -> new SenderAccountPool(mempoolProperties, sender, currentChainNonce));
				pool.lock.lock();
				try {
					resynchronizePool(pool, currentChainNonce, events);
					replacement = pool.get(entry.getNonce());
				} finally {
					pool.lock.unlock();
				}
			}

			Hash ignoredGovernanceHash = replacement == null ? null : replacement.getHash();
			StorageAddResult constraintResult = validateAdmissionConstraints(entry, constraints, ignoredGovernanceHash);
			if (constraintResult != null) {
				removeEmptyPool(sender, pool);
				return constraintResult;
			}
			if (hasGovernanceConflictInternal(entry, ignoredGovernanceHash)) {
				removeEmptyPool(sender, pool);
				return StorageAddResult.GOVERNANCE_CONFLICT;
			}

			/* putIfAbsent remains intentional: it protects this invariant even if a
			 * future caller weakens the outer locking discipline. */
			if (allTxsByHash.putIfAbsent(entry.getHash(), entry) != null) {
				return StorageAddResult.DUPLICATE_HASH;
			}
			allTxsByFee.add(entry);

			StorageAddResult result;
			if (sender == null) {
				systemTxs.add(entry);
				result = StorageAddResult.ADDED_EXECUTABLE;
			} else {
				pool.lock.lock();
				try {
					result = pool.addTransaction(entry);
					if (result.isSuccess()) {
						if (replacement != null) {
							removeGlobalIndexes(replacement);
							events.add(new MempoolTxRemoveEvent(
									this, replacement, MempoolTxRemoveEvent.RemoveReason.RBF, entry.getHash()));
						}
						refreshExecutableIndex(pool);
					}
				} finally {
					pool.lock.unlock();
				}
			}

			if (!result.isSuccess()) {
				allTxsByHash.remove(entry.getHash(), entry);
				allTxsByFee.remove(entry);
				removeEmptyPool(sender, pool);
				return result;
			}

			addGovernanceReservation(entry);
			while (exceedsCapacity()) {
				Set<MempoolEntry> packageToEvict = findLowestFeeDependencyPackage();
				if (packageToEvict.isEmpty()) {
					break;
				}
				for (MempoolEntry victim : packageToEvict) {
					removeEntry(victim, MempoolTxRemoveEvent.RemoveReason.EVICTED_FULL, events, false);
				}
			}

			return allTxsByHash.get(entry.getHash()) == entry ? result : StorageAddResult.MEMPOOL_FULL;
		} finally {
			globalLock.writeLock().unlock();
		}
	}

	private StorageAddResult validateAdmissionConstraints(MempoolEntry entry, AdmissionConstraints constraints,
			Hash replacedHash) {
		if (constraints == null || entry.getTx().getSender() == null) {
			return null;
		}
		Tx tx = entry.getTx();
		if (!nativeReservation(tx.getSender(), tx, constraints.nativeBalance()).affordable()) {
			return StorageAddResult.INSUFFICIENT_FUNDS;
		}
		if (tx.getType() == TxType.TRANSFER && !Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
			Wei available = constraints.tokenBalances().get(tx.getTokenAddress());
			if (available != null && !tokenReservation(tx.getSender(), tx.getTokenAddress(), tx, available).affordable()) {
				return StorageAddResult.INSUFFICIENT_FUNDS;
			}
		}
		MintSupplyConstraint mintConstraint = constraints.mintSupply();
		if (mintConstraint != null && tx.getType() == TxType.BIP_CREATE
				&& tx.getPayload() instanceof TxBipTokenMintPayload mint
				&& mintConstraint.tokenAddress().equals(mint.getTokenAddress())) {
			BigInteger required = getPendingTokenMintAmount(mint.getTokenAddress(), replacedHash)
					.add(mint.getAmount().toBigInteger());
			if (required.compareTo(mintConstraint.maxPendingAmount()) > 0) {
				return StorageAddResult.TOKEN_SUPPLY_CONFLICT;
			}
		}
		return null;
	}

	private void resynchronizeSenderInternal(Address sender, long chainNonce,
			List<MempoolTxRemoveEvent> events) {
		SenderAccountPool pool = userTxsBySender.get(sender);
		if (pool == null) {
			return;
		}
		pool.lock.lock();
		try {
			resynchronizePool(pool, chainNonce, events);
			if (pool.isEmpty()) {
				userTxsBySender.remove(sender, pool);
			}
		} finally {
			pool.lock.unlock();
		}
	}

	private void resynchronizePool(SenderAccountPool pool, long chainNonce,
			List<MempoolTxRemoveEvent> events) {
		List<MempoolEntry> stale = pool.resynchronize(chainNonce);
		for (MempoolEntry entry : stale) {
			removeGlobalIndexes(entry);
			events.add(new MempoolTxRemoveEvent(
					this, entry, MempoolTxRemoveEvent.RemoveReason.STALE_NONCE));
		}
		refreshExecutableIndex(pool);
	}

	private void removeEntry(MempoolEntry entry, MempoolTxRemoveEvent.RemoveReason reason,
			List<MempoolTxRemoveEvent> events, boolean removeDescendants) {
		if (allTxsByHash.get(entry.getHash()) != entry) {
			return;
		}
		Address sender = entry.getTx().getSender();
		if (sender == null) {
			systemTxs.remove(entry);
			removeGlobalIndexes(entry);
			events.add(new MempoolTxRemoveEvent(this, entry, reason));
			return;
		}

		SenderAccountPool pool = userTxsBySender.get(sender);
		if (pool == null) {
			removeGlobalIndexes(entry);
			events.add(new MempoolTxRemoveEvent(this, entry, reason));
			return;
		}
		pool.lock.lock();
		try {
			List<MempoolEntry> removed = removeDescendants
					? pool.removeFromNonce(entry.getNonce())
					: pool.remove(Set.of(entry.getNonce()));
			for (MempoolEntry removedEntry : removed) {
				removeGlobalIndexes(removedEntry);
				events.add(new MempoolTxRemoveEvent(this, removedEntry, reason));
			}
			refreshExecutableIndex(pool);
			if (pool.isEmpty()) {
				userTxsBySender.remove(sender, pool);
			}
		} finally {
			pool.lock.unlock();
		}
	}

	private void removeGlobalIndexes(MempoolEntry entry) {
		allTxsByHash.remove(entry.getHash(), entry);
		allTxsByFee.remove(entry);
		executableTxsByFee.remove(entry);
		removeGovernanceReservation(entry);
	}

	private void refreshExecutableIndex(SenderAccountPool pool) {
		executableTxsByFee.removeAll(pool.executableRemoves);
		executableTxsByFee.addAll(pool.executableAdds);
		pool.resetExecutableChanges();
	}

	private Set<MempoolEntry> findLowestFeeDependencyPackage() {
		MempoolEntry bestStart = null;
		double bestFeeRate = Double.POSITIVE_INFINITY;

		for (MempoolEntry systemTx : systemTxs) {
			double feeRate = calculateFeePerByte(systemTx);
			if (feeRate < bestFeeRate) {
				bestFeeRate = feeRate;
				bestStart = systemTx;
			}
		}

		for (SenderAccountPool pool : userTxsBySender.values()) {
			pool.lock.lock();
			try {
				double suffixFee = 0.0;
				long suffixSize = 0L;
				for (MempoolEntry entry : pool.allTransactions().descendingMap().values()) {
					suffixFee += entry.getFeeAsDouble();
					suffixSize += entry.getSizeInBytes();
					double feeRate = suffixSize == 0L ? 0.0 : suffixFee / suffixSize;
					if (feeRate < bestFeeRate) {
						bestFeeRate = feeRate;
						bestStart = entry;
					}
				}
			} finally {
				pool.lock.unlock();
			}
		}

		return bestStart == null ? Collections.emptySet() : dependencyPackage(bestStart);
	}

	private Set<MempoolEntry> dependencyPackage(MempoolEntry candidate) {
		Address sender = candidate.getTx().getSender();
		if (sender == null) {
			return Set.of(candidate);
		}
		SenderAccountPool pool = userTxsBySender.get(sender);
		if (pool == null) {
			return Set.of(candidate);
		}
		pool.lock.lock();
		try {
			return new LinkedHashSet<>(pool.allTransactions().tailMap(candidate.getNonce(), true).values());
		} finally {
			pool.lock.unlock();
		}
	}

	private boolean exceedsCapacity() {
		return allTxsByHash.size() > mempoolProperties.getMaxSize();
	}

	private long countFutureTransactions() {
		return userTxsBySender.values().stream().mapToLong(pool -> {
			pool.lock.lock();
			try {
				return pool.futureTxs.size();
			} finally {
				pool.lock.unlock();
			}
		}).sum();
	}

	private void removeEmptyPool(Address sender, SenderAccountPool pool) {
		if (sender != null && pool != null && pool.isEmpty()) {
			userTxsBySender.remove(sender, pool);
		}
	}

	private boolean hasGovernanceConflictInternal(MempoolEntry entry, Hash ignoredHash) {
		GovernanceReservation reservation = governanceReservation(entry);
		if (reservation == null) {
			return false;
		}
		Hash owner = reservation.owner();
		return owner != null && !owner.equals(entry.getHash()) && !owner.equals(ignoredHash);
	}

	private void addGovernanceReservation(MempoolEntry entry) {
		GovernanceReservation reservation = governanceReservation(entry);
		if (reservation != null) {
			reservation.add(entry.getHash());
		}
		if (entry.getTx().getType() == TxType.BIP_CREATE
				&& entry.getTx().getPayload() instanceof TxBipTokenMintPayload mint) {
			TokenMintReservation tokenMint = new TokenMintReservation(
					mint.getTokenAddress(), mint.getAmount().toBigInteger());
			tokenMintsByHash.put(entry.getHash(), tokenMint);
			pendingTokenMintAmounts.merge(tokenMint.tokenAddress(), tokenMint.amount(), BigInteger::add);
		}
	}

	private void removeGovernanceReservation(MempoolEntry entry) {
		GovernanceReservation reservation = governanceReservation(entry);
		if (reservation != null) {
			reservation.remove(entry.getHash());
		}
		TokenMintReservation mint = tokenMintsByHash.remove(entry.getHash());
		if (mint != null) {
			pendingTokenMintAmounts.computeIfPresent(mint.tokenAddress(), (token, total) -> {
				BigInteger remaining = total.subtract(mint.amount());
				return remaining.signum() == 0 ? null : remaining;
			});
		}
	}

	private GovernanceReservation governanceReservation(MempoolEntry entry) {
		Tx tx = entry.getTx();
		TxPayload payload = tx.getPayload();
		if (tx.getType() == TxType.BIP_CREATE && payload != null) {
			if (payload instanceof TxBipAuthorityAddPayload value) {
				return mapReservation(pendingAuthorityChanges, value.getAddress());
			}
			if (payload instanceof TxBipAuthorityRemovePayload value) {
				return mapReservation(pendingAuthorityChanges, value.getAddress());
			}
			if (payload instanceof TxBipValidatorAddPayload value) {
				return mapReservation(pendingValidatorChanges, value.getAddress());
			}
			if (payload instanceof TxBipValidatorRemovePayload value) {
				return mapReservation(pendingValidatorChanges, value.getAddress());
			}
			if (payload instanceof TxBipValidatorMiningPolicySetPayload value) {
				return mapReservation(pendingValidatorChanges, value.getValidatorAddress());
			}
			if (payload instanceof TxBipNetworkParamsSetPayload) {
				return referenceReservation(pendingNetworkParamsChange);
			}
			if (payload instanceof TxBipAddressAliasAddPayload value) {
				return mapReservation(pendingAddressAliasChanges, value.getAlias());
			}
			if (payload instanceof TxBipAddressAliasRemovePayload value) {
				return mapReservation(pendingAddressAliasChanges, value.getAlias());
			}
			if (payload instanceof TxBipTokenUpdatePayload value) {
				return mapReservation(pendingTokenUpdates, value.getTokenAddress());
			}
		}
		if (tx.getType() == TxType.BIP_VOTE && tx.getReferenceHash() != null && tx.getSender() != null) {
			Hash bipHash = tx.getReferenceHash();
			Address voter = tx.getSender();
			return new GovernanceReservation() {
				@Override
				public Hash owner() {
					Map<Address, Hash> votes = pendingBipVotes.get(bipHash);
					return votes == null ? null : votes.get(voter);
				}

				@Override
				public void add(Hash hash) {
					pendingBipVotes.computeIfAbsent(bipHash, ignored -> new ConcurrentHashMap<>())
							.put(voter, hash);
				}

				@Override
				public void remove(Hash hash) {
					ConcurrentHashMap<Address, Hash> votes = pendingBipVotes.get(bipHash);
					if (votes != null) {
						votes.remove(voter, hash);
						if (votes.isEmpty()) {
							pendingBipVotes.remove(bipHash, votes);
						}
					}
				}
			};
		}
		return null;
	}

	private <K> GovernanceReservation mapReservation(ConcurrentHashMap<K, Hash> map, K key) {
		return new GovernanceReservation() {
			@Override
			public Hash owner() {
				return map.get(key);
			}

			@Override
			public void add(Hash hash) {
				map.put(key, hash);
			}

			@Override
			public void remove(Hash hash) {
				map.remove(key, hash);
			}
		};
	}

	private GovernanceReservation referenceReservation(AtomicReference<Hash> reference) {
		return new GovernanceReservation() {
			@Override
			public Hash owner() {
				return reference.get();
			}

			@Override
			public void add(Hash hash) {
				reference.set(hash);
			}

			@Override
			public void remove(Hash hash) {
				reference.compareAndSet(hash, null);
			}
		};
	}

	private void publishLifecycleEvents(
			List<MempoolTxRemoveEvent> removals,
			List<MempoolTxAddEvent> additions) {
		publishLifecycleEvents(removals, additions, null);
	}

	private void publishLifecycleEvents(
			List<MempoolTxRemoveEvent> removals,
			List<MempoolTxAddEvent> additions,
			MempoolCanonicalProjectionAdvance projectionAdvance) {
		LifecycleEventBatch activeBatch = lifecycleEventBatch.get();
		if (activeBatch != null) {
			activeBatch.add(removals, additions);
			return;
		}
		try {
			if (projectionAdvance == null) {
				lifecycleJournalWriter.commitBeforeWake(UUID.randomUUID(), removals, additions);
			} else {
				lifecycleJournalWriter.commitBeforeWake(
						UUID.randomUUID(), removals, additions, projectionAdvance);
			}
		} catch (RuntimeException failure) {
			restoreRamFromPersistent(failure);
			throw failure;
		}
		removals.forEach(this::publishRemoveEvent);
		additions.forEach(this::publishAddEvent);
	}

	private void restoreRamFromPersistent(RuntimeException commitFailure) {
		if (persistentMempoolStore == null) {
			IllegalStateException terminal = new IllegalStateException(
					"Persistent mempool commit failed without an authoritative restore source", commitFailure);
			integrityGuard.fail(terminal);
			throw terminal;
		}
		try {
			rebuildDerivedIndexesExact();
		} catch (RuntimeException restoreFailure) {
			commitFailure.addSuppressed(restoreFailure);
			IllegalStateException terminal = new IllegalStateException(
					"Persistent mempool commit failed and derived RAM state could not be restored",
					commitFailure);
			integrityGuard.fail(terminal);
			throw terminal;
		}
	}

	private void rebuildDerivedIndexesExact() {
		clearDerivedState();
		Set<Hash> expectedHashes = new LinkedHashSet<>();
		Map<Address, List<MempoolEntry>> bySender = new LinkedHashMap<>();
		int scanned = PersistentMempoolBoundedScanner.scanOrdered(
				persistentMempoolStore, mempoolProperties, record -> {
			MempoolEntry entry = new MempoolEntry(
					TxDecoder.INSTANCE.decode(Bytes.wrap(record.rawSignedTx())),
					record.firstSeenTime(), record.firstSeenHeight(), null);
			if (!record.txHash().equals(entry.getHash()) || !expectedHashes.add(entry.getHash())
					|| allTxsByHash.putIfAbsent(entry.getHash(), entry) != null) {
				throw new IllegalStateException("Persistent mempool record hash mismatch during RAM rebuild");
			}
			allTxsByFee.add(entry);
			Address sender = entry.getTx().getSender();
			if (sender == null) {
				systemTxs.add(entry);
			} else {
				bySender.computeIfAbsent(sender, ignored -> new ArrayList<>()).add(entry);
			}
		});
		if (scanned != expectedHashes.size()) {
			throw new IllegalStateException("Persistent mempool scan returned duplicate transaction hashes");
		}

		for (Map.Entry<Address, List<MempoolEntry>> senderEntries : bySender.entrySet()) {
			Address sender = senderEntries.getKey();
			long chainNonce = chainHeadStateService.getHeadState().getNonce(sender).getNonce();
			SenderAccountPool pool = new SenderAccountPool(mempoolProperties, sender, chainNonce);
			List<MempoolEntry> ordered = senderEntries.getValue().stream()
					.sorted(Comparator.comparing(MempoolEntry::getNonce).thenComparing(MempoolEntry::getHash))
					.toList();
			long executableNonce = Math.addExact(chainNonce, 1L);
			boolean gap = false;
			for (MempoolEntry entry : ordered) {
				long nonce = entry.getNonce();
				if (pool.executableTxs.containsKey(nonce) || pool.futureTxs.containsKey(nonce)) {
					throw new IllegalStateException(
							"Persistent mempool contains two active transactions for sender/nonce");
				}
				pool.addCost(entry.getTx());
				if (!gap && nonce == executableNonce) {
					pool.executableTxs.put(nonce, entry);
					executableTxsByFee.add(entry);
					executableNonce = Math.incrementExact(executableNonce);
				} else {
					gap = true;
					pool.futureTxs.put(nonce, entry);
				}
			}
			userTxsBySender.put(sender, pool);
		}

		for (MempoolEntry entry : allTxsByHash.values()) {
			if (hasGovernanceConflictInternal(entry, null)) {
				throw new IllegalStateException("Persistent mempool contains conflicting governance reservations");
			}
			addGovernanceReservation(entry);
		}
		if (!allTxsByHash.keySet().equals(expectedHashes)) {
			throw new IllegalStateException("Derived mempool hash set differs from persistent ACTIVE records");
		}
	}

	private void clearDerivedState() {
		allTxsByHash.clear();
		allTxsByFee.clear();
		executableTxsByFee.clear();
		userTxsBySender.clear();
		systemTxs.clear();
		pendingAuthorityChanges.clear();
		pendingValidatorChanges.clear();
		pendingAddressAliasChanges.clear();
		pendingNetworkParamsChange.set(null);
		pendingTokenUpdates.clear();
		pendingBipVotes.clear();
		pendingTokenMintAmounts.clear();
		tokenMintsByHash.clear();
	}

	private <T> T withLifecycleMutationLock(Supplier<T> operation) {
		integrityGuard.requireHealthy();
		lifecycleMutationLock.lock();
		globalLock.writeLock().lock();
		try {
			return operation.get();
		} finally {
			globalLock.writeLock().unlock();
			lifecycleMutationLock.unlock();
		}
	}

	private void withLifecycleMutationLock(Runnable operation) {
		integrityGuard.requireHealthy();
		lifecycleMutationLock.lock();
		globalLock.writeLock().lock();
		try {
			operation.run();
		} finally {
			globalLock.writeLock().unlock();
			lifecycleMutationLock.unlock();
		}
	}

	private <T> T withReadLock(Supplier<T> operation) {
		integrityGuard.requireHealthy();
		globalLock.readLock().lock();
		try {
			return operation.get();
		} finally {
			globalLock.readLock().unlock();
		}
	}

	private void publishRemoveEvent(MempoolTxRemoveEvent event) {
		try {
			applicationEventPublisher.publishEvent(event);
		} catch (RuntimeException exception) {
			log.error("Failed to publish mempool removal for {}", event.getEntry().getHash(), exception);
		}
	}

	private void publishAddEvent(MempoolTxAddEvent event) {
		try {
			applicationEventPublisher.publishEvent(event);
		} catch (RuntimeException exception) {
			log.error("Failed to publish mempool addition for {}", event.getEntry().getHash(), exception);
		}
	}

	private interface GovernanceReservation {
		Hash owner();

		void add(Hash hash);

		void remove(Hash hash);
	}

	private static final class LifecycleEventBatch {
		private final List<MempoolTxRemoveEvent> removals = new ArrayList<>();
		private final LinkedHashMap<Hash, MempoolTxAddEvent> additions = new LinkedHashMap<>();

		void add(List<MempoolTxRemoveEvent> newRemovals, List<MempoolTxAddEvent> newAdditions) {
			for (MempoolTxRemoveEvent removal : newRemovals) {
				if (additions.remove(removal.getEntry().getHash()) == null) {
					removals.add(removal);
				}
			}
			for (MempoolTxAddEvent addition : newAdditions) {
				additions.put(addition.getEntry().getHash(), addition);
			}
		}

		List<MempoolTxRemoveEvent> removals() {
			return List.copyOf(removals);
		}

		List<MempoolTxAddEvent> additions() {
			return List.copyOf(additions.values());
		}
	}

	@Getter
	private static class SenderAccountPool {
		final MempoolProperties mempoolProperties;
		final ReentrantLock lock = new ReentrantLock();
		final Address senderAddress;
		long chainNonce;
		final TreeMap<Long, MempoolEntry> executableTxs = new TreeMap<>();
		final TreeMap<Long, MempoolEntry> futureTxs = new TreeMap<>();
		final Set<MempoolEntry> executableAdds = new HashSet<>();
		final Set<MempoolEntry> executableRemoves = new HashSet<>();
		BigInteger reservedNative = BigInteger.ZERO;
		final Map<Address, BigInteger> reservedTokens = new HashMap<>();

		SenderAccountPool(MempoolProperties mempoolProperties, Address senderAddress, long chainNonce) {
			this.mempoolProperties = mempoolProperties;
			this.senderAddress = senderAddress;
			this.chainNonce = chainNonce;
		}

		StorageAddResult addTransaction(MempoolEntry entry) {
			resetExecutableChanges();
			long nonce = entry.getNonce();
			if (nonce <= chainNonce) {
				return StorageAddResult.STALE;
			}
			if (nonce > chainNonce + mempoolProperties.getMaxNonceGap()) {
				return StorageAddResult.NONCE_TOO_FAR_FUTURE;
			}
			MempoolEntry existing = get(nonce);
			if (existing != null) {
				BigInteger oldFee = existing.getTx().getFee().toBigInteger();
				BigInteger newFee = entry.getTx().getFee().toBigInteger();
				BigInteger requiredFee = oldFee.multiply(BigInteger.valueOf(110))
						.add(BigInteger.valueOf(99))
						.divide(BigInteger.valueOf(100));
				if (newFee.compareTo(oldFee) <= 0 || newFee.compareTo(requiredFee) < 0) {
					return StorageAddResult.FAILED_FEE_TOO_LOW;
				}
				removeCost(existing.getTx());
				addCost(entry.getTx());
				if (executableTxs.get(nonce) == existing) {
					executableTxs.put(nonce, entry);
					executableRemoves.add(existing);
					executableAdds.add(entry);
					return StorageAddResult.ADDED_EXECUTABLE;
				}
				futureTxs.put(nonce, entry);
				return StorageAddResult.ADDED_FUTURE;
			}

			addCost(entry.getTx());
			long expectedNonce = chainNonce + executableTxs.size() + 1L;
			if (nonce != expectedNonce) {
				futureTxs.put(nonce, entry);
				return StorageAddResult.ADDED_FUTURE;
			}

			executableTxs.put(nonce, entry);
			executableAdds.add(entry);
			long promotableNonce = nonce + 1L;
			MempoolEntry promotable;
			while ((promotable = futureTxs.remove(promotableNonce)) != null) {
				executableTxs.put(promotableNonce, promotable);
				executableAdds.add(promotable);
				promotableNonce++;
			}
			return StorageAddResult.ADDED_EXECUTABLE;
		}

		List<MempoolEntry> resynchronize(long newChainNonce) {
			TreeMap<Long, MempoolEntry> all = allTransactions();
			List<MempoolEntry> stale = new ArrayList<>(all.headMap(newChainNonce, true).values());
			all.headMap(newChainNonce, true).clear();
			chainNonce = newChainNonce;
			rebuild(all);
			return stale;
		}

		List<MempoolEntry> remove(Set<Long> nonces) {
			TreeMap<Long, MempoolEntry> all = allTransactions();
			List<MempoolEntry> removed = new ArrayList<>();
			for (Long nonce : nonces) {
				MempoolEntry entry = all.remove(nonce);
				if (entry != null) {
					removed.add(entry);
				}
			}
			rebuild(all);
			return removed;
		}

		List<MempoolEntry> removeFromNonce(long nonce) {
			TreeMap<Long, MempoolEntry> all = allTransactions();
			List<MempoolEntry> removed = new ArrayList<>(all.tailMap(nonce, true).values());
			all.tailMap(nonce, true).clear();
			rebuild(all);
			return removed;
		}

		MempoolEntry get(long nonce) {
			MempoolEntry entry = executableTxs.get(nonce);
			return entry == null ? futureTxs.get(nonce) : entry;
		}

		TreeMap<Long, MempoolEntry> allTransactions() {
			TreeMap<Long, MempoolEntry> all = new TreeMap<>(executableTxs);
			all.putAll(futureTxs);
			return all;
		}

		private void rebuild(TreeMap<Long, MempoolEntry> all) {
			Set<MempoolEntry> previouslyExecutable = new HashSet<>(executableTxs.values());
			executableTxs.clear();
			futureTxs.clear();
			reservedNative = BigInteger.ZERO;
			reservedTokens.clear();
			long expected = chainNonce + 1;
			for (Map.Entry<Long, MempoolEntry> item : all.entrySet()) {
				Tx tx = item.getValue().getTx();
				addCost(tx);
				if (item.getKey() == expected) {
					executableTxs.put(item.getKey(), item.getValue());
					expected++;
				} else {
					futureTxs.put(item.getKey(), item.getValue());
				}
			}
			Set<MempoolEntry> currentlyExecutable = new HashSet<>(executableTxs.values());
			executableRemoves.clear();
			executableRemoves.addAll(previouslyExecutable);
			executableRemoves.removeAll(currentlyExecutable);
			executableAdds.clear();
			executableAdds.addAll(currentlyExecutable);
			executableAdds.removeAll(previouslyExecutable);
		}

		private void resetExecutableChanges() {
			executableAdds.clear();
			executableRemoves.clear();
		}

		private void addCost(Tx tx) {
			reservedNative = reservedNative.add(nativeCost(tx));
			if (tx.getType() == TxType.TRANSFER && tx.getTokenAddress() != null
					&& !Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
				reservedTokens.merge(tx.getTokenAddress(), amount(tx), BigInteger::add);
			}
		}

		private void removeCost(Tx tx) {
			reservedNative = reservedNative.subtract(nativeCost(tx));
			if (tx.getType() == TxType.TRANSFER && tx.getTokenAddress() != null
					&& !Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
				reservedTokens.computeIfPresent(tx.getTokenAddress(), (token, reserved) -> {
					BigInteger remaining = reserved.subtract(amount(tx));
					return remaining.signum() == 0 ? null : remaining;
				});
			}
		}

		int size() {
			return executableTxs.size() + futureTxs.size();
		}

		boolean isEmpty() {
			return executableTxs.isEmpty() && futureTxs.isEmpty();
		}
	}

	public enum StorageAddResult {
		ADDED_EXECUTABLE,
		ADDED_FUTURE,
		STALE,
		DUPLICATE_HASH,
		FAILED_FEE_TOO_LOW,
		MEMPOOL_FULL,
		NONCE_TOO_FAR_FUTURE,
		GOVERNANCE_CONFLICT,
		INSUFFICIENT_FUNDS,
		TOKEN_SUPPLY_CONFLICT,
		REPLACED;

		public boolean isSuccess() {
			return this == ADDED_EXECUTABLE || this == ADDED_FUTURE;
		}
	}

	private static ReservationSnapshot reservationSnapshot(BigInteger reserved, BigInteger replacing,
			BigInteger candidate, Wei available) {
		BigInteger required = reserved.subtract(replacing).add(candidate);
		return new ReservationSnapshot(
				Wei.valueOf(reserved), Wei.valueOf(replacing), Wei.valueOf(candidate), Wei.valueOf(required), available);
	}

	private static BigInteger nativeCost(Tx tx) {
		BigInteger cost = tx.getFee().toBigInteger();
		if (tx.getType() == TxType.TRANSFER && Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
			cost = cost.add(amount(tx));
		}
		return cost;
	}

	private static BigInteger tokenCost(Tx tx, Address tokenAddress) {
		return tx.getType() == TxType.TRANSFER && tokenAddress.equals(tx.getTokenAddress())
				? amount(tx)
				: BigInteger.ZERO;
	}

	private static BigInteger amount(Tx tx) {
		return tx.getAmount() == null ? BigInteger.ZERO : tx.getAmount().toBigInteger();
	}

	private static double calculateFeePerByte(MempoolEntry entry) {
		return entry.getSizeInBytes() == 0 ? 0.0 : entry.getFeeAsDouble() / entry.getSizeInBytes();
	}

	private static int compareByFeeDensity(MempoolEntry first, MempoolEntry second) {
		int feeComparison = compareFeeDensity(second, first);
		if (feeComparison != 0) {
			return feeComparison;
		}
		int nonceComparison = first.getNonce().compareTo(second.getNonce());
		return nonceComparison != 0 ? nonceComparison : first.getHash().compareTo(second.getHash());
	}

	private static int compareFeeDensity(MempoolEntry first, MempoolEntry second) {
		BigInteger firstFee = first.getTx().getFee().toBigInteger();
		BigInteger secondFee = second.getTx().getFee().toBigInteger();
		return firstFee.multiply(BigInteger.valueOf(second.getSizeInBytes()))
				.compareTo(secondFee.multiply(BigInteger.valueOf(first.getSizeInBytes())));
	}

	private record TokenMintReservation(Address tokenAddress, BigInteger amount) {
	}
}
