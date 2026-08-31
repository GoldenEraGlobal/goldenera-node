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
package global.goldenera.node.core.storage.blockchain.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolAdmissionReason;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolCanonicalProjectionAdvance;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolMutationBatch;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolMutationResult;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolStateMutation;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolStatus;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolTransaction;

/**
 * Persists the authoritative mempool state mutation and its lifecycle journal
 * entries in one RocksDB batch before MempoolStore publishes Spring wake hints.
 */
@Component
public final class MempoolLifecycleJournalWriter {

	private static final int COMMIT_ATTEMPTS = 2;

	private final PersistentMempoolStore persistentStore;
	private final boolean enabled;

	@Autowired
	public MempoolLifecycleJournalWriter(PersistentMempoolStore persistentStore) {
		this(persistentStore, true);
	}

	private MempoolLifecycleJournalWriter(PersistentMempoolStore persistentStore, boolean enabled) {
		this.persistentStore = persistentStore;
		this.enabled = enabled;
	}

	public static MempoolLifecycleJournalWriter disabled() {
		return new MempoolLifecycleJournalWriter(null, false);
	}

	/** Compatibility entry point for focused tests without an externally supplied batch id. */
	public void appendBeforeWake(
			List<MempoolTxRemoveEvent> removals,
			List<MempoolTxAddEvent> additions) {
		commitBeforeWake(UUID.randomUUID(), removals, additions);
	}

	public MempoolMutationResult commitBeforeWake(
			UUID batchId,
			List<MempoolTxRemoveEvent> removals,
			List<MempoolTxAddEvent> additions) {
		return commitBeforeWake(batchId, removals, additions, null);
	}

	public MempoolMutationResult commitBeforeWake(
			UUID batchId,
			List<MempoolTxRemoveEvent> removals,
			List<MempoolTxAddEvent> additions,
			MempoolCanonicalProjectionAdvance canonicalProjectionAdvance) {
		if (!enabled) {
			return new MempoolMutationResult(false, 0L, List.of());
		}
		if (batchId == null || removals == null || additions == null) {
			throw new IllegalArgumentException("Mempool lifecycle event lists are required");
		}
		List<MempoolStateMutation> mutations = new ArrayList<>(removals.size() + additions.size());
		for (MempoolTxRemoveEvent removal : removals) {
			mutations.add(MempoolStateMutation.delete(removal.getEntry().getHash(), removalDraft(removal)));
		}
		for (MempoolTxAddEvent addition : additions) {
			mutations.add(MempoolStateMutation.upsert(
					stored(addition, replacedHash(addition, removals)), additionDraft(addition)));
		}
		if (mutations.isEmpty() && canonicalProjectionAdvance == null) {
			return new MempoolMutationResult(false, persistentStore.head().batchSequence(), List.of());
		}
		MempoolMutationBatch batch = new MempoolMutationBatch(batchId, mutations, canonicalProjectionAdvance);
		RuntimeException firstFailure = null;
		for (int attempt = 1; attempt <= COMMIT_ATTEMPTS; attempt++) {
			try {
				return persistentStore.commit(batch);
			} catch (RuntimeException failure) {
				if (firstFailure == null) {
					firstFailure = failure;
				} else {
					firstFailure.addSuppressed(failure);
				}
			}
		}
		throw firstFailure;
	}

	private StoredMempoolTransaction stored(
			MempoolTxAddEvent event,
			Hash replacesTxHash) {
		MempoolEntry entry = event.getEntry();
		return new StoredMempoolTransaction(
				StoredMempoolTransaction.CURRENT_VERSION,
				StoredMempoolStatus.ACTIVE,
				entry.getHash(),
				payload(entry),
				occurredAt(entry),
				entry.getFirstSeenHeight(),
				admissionReason(event.getReason()),
				replacesTxHash);
	}

	private MempoolAdmissionReason admissionReason(MempoolTxAddEvent.AddReason reason) {
		return switch (reason) {
			case NEW -> MempoolAdmissionReason.NEW;
			case REORG -> MempoolAdmissionReason.REORG;
			case SYNC -> MempoolAdmissionReason.SYNC;
		};
	}

	private Hash replacedHash(
			MempoolTxAddEvent addition,
			List<MempoolTxRemoveEvent> removals) {
		return removals.stream()
				.filter(removal -> removal.getReason() == MempoolTxRemoveEvent.RemoveReason.RBF)
				.filter(removal -> addition.getEntry().getHash().equals(removal.getReplacementTxHash()))
				.map(removal -> removal.getEntry().getHash())
				.findFirst()
				.orElse(null);
	}

	private LifecycleJournalDraft additionDraft(MempoolTxAddEvent event) {
		MempoolEntry entry = event.getEntry();
		Instant occurredAt = occurredAt(entry);
		byte[] payload = payload(entry);
		return switch (event.getReason()) {
			case REORG -> LifecycleJournalDraft.reorgReadd(
					entry.getHash(), entry.getFirstSeenHeight(), occurredAt,
					MempoolLifecycleReason.REORG.code(), payload);
			case NEW -> LifecycleJournalDraft.pending(
					entry.getHash(), entry.getFirstSeenHeight(), occurredAt,
					MempoolLifecycleReason.NEW.code(), payload);
			case SYNC -> LifecycleJournalDraft.pending(
					entry.getHash(), entry.getFirstSeenHeight(), occurredAt,
					MempoolLifecycleReason.SYNC.code(), payload);
		};
	}

	private LifecycleJournalDraft removalDraft(MempoolTxRemoveEvent event) {
		if (event.getReason() == MempoolTxRemoveEvent.RemoveReason.MINED) {
			return null;
		}
		MempoolEntry entry = event.getEntry();
		Instant occurredAt = Instant.now();
		byte[] payload = payload(entry);
		return switch (event.getReason()) {
			case RBF -> LifecycleJournalDraft.replaced(
					entry.getHash(), event.getReplacementTxHash(), entry.getFirstSeenHeight(), occurredAt,
					MempoolLifecycleReason.RBF.code(), payload);
			case STALE_NONCE -> dropped(entry, occurredAt, MempoolLifecycleReason.STALE_NONCE, payload);
			case EXPIRED -> dropped(entry, occurredAt, MempoolLifecycleReason.EXPIRED, payload);
			case INVALID -> dropped(entry, occurredAt, MempoolLifecycleReason.INVALID, payload);
			case EVICTED_FULL -> dropped(entry, occurredAt, MempoolLifecycleReason.EVICTED_FULL, payload);
			case INSUFFICIENT_FUNDS -> dropped(
					entry, occurredAt, MempoolLifecycleReason.INSUFFICIENT_FUNDS, payload);
			case MINED -> throw new IllegalStateException("MINED is filtered before journal mapping");
		};
	}

	private LifecycleJournalDraft dropped(
			MempoolEntry entry, Instant occurredAt, MempoolLifecycleReason reason, byte[] payload) {
		return LifecycleJournalDraft.dropped(
				entry.getHash(), entry.getFirstSeenHeight(), occurredAt, reason.code(), payload);
	}

	private byte[] payload(MempoolEntry entry) {
		return TxEncoder.INSTANCE.encode(entry.getTx(), true).toArray();
	}

	private Instant occurredAt(MempoolEntry entry) {
		return entry.getFirstSeenTime() == null ? Instant.now() : entry.getFirstSeenTime();
	}
}
