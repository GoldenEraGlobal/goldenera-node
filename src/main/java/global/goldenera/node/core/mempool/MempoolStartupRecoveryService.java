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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolStore.RecoveryAddResult;
import global.goldenera.node.core.mempool.MempoolStore.StorageAddResult;
import global.goldenera.node.core.mempool.MempoolValidator.MempoolValidationResult;
import global.goldenera.node.core.mempool.MempoolValidator.ValidationStatus;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleJournalWriter;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolMutationBatch;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolBoundedScanner;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolTransaction;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MempoolStartupRecoveryService {

	/* A tombstone journal entry contains the full raw transaction. Keep recovery
	 * cleanup safely below the RocksDB mutation byte ceiling even when every
	 * persisted record is at the storage format's maximum size. */
	private static final int TOMBSTONE_BATCH_SIZE = Math.toIntExact(
			MempoolMutationBatch.MAX_BATCH_BYTES / (2L * StoredMempoolTransaction.MAX_RAW_TX_BYTES));

	private final PersistentMempoolStore persistentStore;
	private final MempoolLifecycleJournalWriter lifecycleWriter;
	private final MempoolStore mempoolStore;
	private final MempoolValidator validator;
	private final MempoolProperties properties;
	private final ChainQuery chainQuery;

	@Autowired
	public MempoolStartupRecoveryService(
			PersistentMempoolStore persistentStore,
			MempoolLifecycleJournalWriter lifecycleWriter,
			MempoolStore mempoolStore,
			MempoolValidator validator,
			MempoolProperties properties,
			ChainQuery chainQuery) {
		this.persistentStore = persistentStore;
		this.lifecycleWriter = lifecycleWriter;
		this.mempoolStore = mempoolStore;
		this.validator = validator;
		this.properties = properties;
		this.chainQuery = chainQuery;
	}

	public RecoveryResult recover() {
		mempoolStore.resetDerivedStateForRecovery();
		Instant expirationCutoff = Instant.now().minusSeconds(
				Math.multiplyExact(properties.getTxExpireTimeInMinutes(), 60L));
		RecoveryAccumulator recovery = new RecoveryAccumulator(expirationCutoff);
		int scanned = PersistentMempoolBoundedScanner.scanOrdered(
				persistentStore, properties, recovery::accept);
		recovery.flushTombstones();
		int restored = Math.toIntExact(mempoolStore.getCount());
		log.info("Recovered {} active mempool transactions; tombstoned {} stale/invalid records",
				restored, recovery.tombstoned);
		return new RecoveryResult(scanned, restored, recovery.tombstoned);
	}

	private final class RecoveryAccumulator {
		private final Instant expirationCutoff;
		private final Map<Address, Long> chainNonces = new HashMap<>();
		private final Set<Hash> tombstoneHashes = new HashSet<>();
		private final List<MempoolTxRemoveEvent> tombstones = new ArrayList<>(TOMBSTONE_BATCH_SIZE);
		private int tombstoned;

		private RecoveryAccumulator(Instant expirationCutoff) {
			this.expirationCutoff = expirationCutoff;
		}

		private void accept(StoredMempoolTransaction record) {
			MempoolEntry entry = entry(record);
			MempoolTxRemoveEvent.RemoveReason precheck = removalReason(entry, expirationCutoff);
			if (precheck != null) {
				addTombstone(new MempoolTxRemoveEvent(this, entry, precheck));
				return;
			}

			MempoolValidationResult validation = validator.revalidateAgainstChain(entry);
			if (validation.getStatus() == ValidationStatus.TRANSIENT_ERROR) {
				throw new IllegalStateException(
						"Cannot safely revalidate persisted mempool transaction " + entry.getHash()
								+ ": " + validation.getErrorMessage());
			}
			if (!validation.isValid()) {
				addTombstone(new MempoolTxRemoveEvent(
						this,
						entry,
						validation.getStatus() == ValidationStatus.STALE
								? MempoolTxRemoveEvent.RemoveReason.STALE_NONCE
								: MempoolTxRemoveEvent.RemoveReason.INVALID));
				return;
			}

			Tx tx = entry.getTx();
			long chainNonce = tx.getSender() == null ? -1L : chainNonces.computeIfAbsent(
					tx.getSender(), ignored -> validation.getCurrentChainNonce());
			RecoveryAddResult recovered = mempoolStore.restoreTransaction(
					entry, chainNonce, validation.getAdmissionConstraints());
			recovered.removals().forEach(this::addTombstone);
			if (recovered.result().isSuccess()
					&& mempoolStore.getTxByHash(entry.getHash()).isPresent()) {
			} else {
				addTombstone(new MempoolTxRemoveEvent(
						this, entry, removalReason(recovered.result())));
			}
		}

		private void addTombstone(MempoolTxRemoveEvent tombstone) {
			if (!tombstoneHashes.add(tombstone.getEntry().getHash())) {
				return;
			}
			tombstones.add(tombstone);
			tombstoned++;
			if (tombstones.size() == TOMBSTONE_BATCH_SIZE) {
				flushTombstones();
			}
		}

		private void flushTombstones() {
			if (tombstones.isEmpty()) {
				return;
			}
			lifecycleWriter.commitBeforeWake(UUID.randomUUID(), List.copyOf(tombstones), List.of());
			tombstones.clear();
		}
	}

	private MempoolEntry entry(StoredMempoolTransaction record) {
		Tx tx = TxDecoder.INSTANCE.decode(Bytes.wrap(record.rawSignedTx()));
		if (!record.txHash().equals(tx.getHash())) {
			throw new IllegalStateException("Persisted mempool transaction hash changed during recovery");
		}
		return new MempoolEntry(tx, record.firstSeenTime(), record.firstSeenHeight(), null);
	}

	private MempoolTxRemoveEvent.RemoveReason removalReason(
			MempoolEntry entry,
			Instant expirationCutoff) {
		if (chainQuery.getTransactionBlock(entry.getHash()).isPresent()) {
			return MempoolTxRemoveEvent.RemoveReason.MINED;
		}
		if (entry.getFirstSeenTime().isBefore(expirationCutoff)) {
			return MempoolTxRemoveEvent.RemoveReason.EXPIRED;
		}
		return null;
	}

	private MempoolTxRemoveEvent.RemoveReason removalReason(StorageAddResult result) {
		return switch (result) {
			case STALE -> MempoolTxRemoveEvent.RemoveReason.STALE_NONCE;
			case INSUFFICIENT_FUNDS -> MempoolTxRemoveEvent.RemoveReason.INSUFFICIENT_FUNDS;
			case MEMPOOL_FULL -> MempoolTxRemoveEvent.RemoveReason.EVICTED_FULL;
			default -> MempoolTxRemoveEvent.RemoveReason.INVALID;
		};
	}

	public record RecoveryResult(int scanned, int restored, int tombstoned) {
	}
}
