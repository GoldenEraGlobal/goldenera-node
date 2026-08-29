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
package global.goldenera.node.core.storage.blockchain.mempool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Repository;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalDraft;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalRepository;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleReason;
import global.goldenera.node.shared.exceptions.GEFailedException;

@Repository
public class PersistentMempoolRepository implements PersistentMempoolStore {

	private static final int MAX_SCAN_LIMIT = 4_096;

	private final RocksDBRepository rocks;
	private final RocksDbColumnFamilies columnFamilies;
	private final LifecycleJournalRepository lifecycleJournal;
	private final PersistentMempoolCodec codec = new PersistentMempoolCodec();
	private final PersistentMempoolHeadCodec headCodec = new PersistentMempoolHeadCodec();
	private final MempoolCanonicalProjectionCursorCodec projectionCursorCodec =
			new MempoolCanonicalProjectionCursorCodec();

	public PersistentMempoolRepository(
			RocksDBRepository rocks,
			RocksDbColumnFamilies columnFamilies,
			LifecycleJournalRepository lifecycleJournal) {
		this.rocks = rocks;
		this.columnFamilies = columnFamilies;
		this.lifecycleJournal = lifecycleJournal;
	}

	public synchronized void initializeForEpoch(UUID epoch) {
		if (epoch == null) {
			throw new IllegalArgumentException("Persistent mempool epoch is required");
		}
		Optional<PersistentMempoolHead> existing = findHead();
		if (existing.isPresent() && existing.get().epoch().equals(epoch)) {
			return;
		}
		rocks.executeAtomicBatch(batch -> {
			MempoolStorageLayout.clearState(batch, columnFamilies);
			PersistentMempoolHead initial = new PersistentMempoolHead(
					PersistentMempoolHead.CURRENT_VERSION, epoch, 0L, null);
			batch.put(columnFamilies.metadata(), MempoolStorageLayout.headKey(), headCodec.encode(initial));
		});
	}

	@Override
	public PersistentMempoolHead head() {
		return findHead().orElseThrow(() -> new GEFailedException("Persistent mempool is not initialized"));
	}

	public Optional<PersistentMempoolHead> findHead() {
		try {
			byte[] encoded = rocks.get(columnFamilies.metadata(), MempoolStorageLayout.headKey());
			return encoded == null ? Optional.empty() : Optional.of(headCodec.decode(encoded));
		} catch (RocksDBException | IllegalArgumentException failure) {
			throw new GEFailedException("Cannot read persistent mempool head", failure);
		}
	}

	@Override
	public Optional<MempoolCanonicalProjectionCursor> canonicalProjectionCursor() {
		try {
			byte[] encoded = rocks.get(
					columnFamilies.metadata(), MempoolStorageLayout.canonicalProjectionCursorKey());
			return encoded == null ? Optional.empty() : Optional.of(projectionCursorCodec.decode(encoded));
		} catch (RocksDBException | IllegalArgumentException failure) {
			throw new GEFailedException("Cannot read mempool canonical projection cursor", failure);
		}
	}

	@Override
	public synchronized void initializeCanonicalProjectionCursor(UUID epoch, long sequence) {
		if (epoch == null || sequence < 0L) {
			throw new IllegalArgumentException("Mempool canonical projection anchor is invalid");
		}
		Optional<MempoolCanonicalProjectionCursor> existing = canonicalProjectionCursor();
		if (existing.isPresent()) {
			if (!existing.get().epoch().equals(epoch)) {
				MempoolCanonicalProjectionCursor replacement = new MempoolCanonicalProjectionCursor(
						MempoolCanonicalProjectionCursor.CURRENT_VERSION, epoch, sequence);
				rocks.executeAtomicBatch(batch -> batch.put(
						columnFamilies.metadata(), MempoolStorageLayout.canonicalProjectionCursorKey(),
						projectionCursorCodec.encode(replacement)));
			}
			return;
		}
		MempoolCanonicalProjectionCursor initial = new MempoolCanonicalProjectionCursor(
				MempoolCanonicalProjectionCursor.CURRENT_VERSION, epoch, sequence);
		rocks.executeAtomicBatch(batch -> batch.put(
				columnFamilies.metadata(), MempoolStorageLayout.canonicalProjectionCursorKey(),
				projectionCursorCodec.encode(initial)));
	}

	@Override
	public synchronized MempoolMutationResult commit(MempoolMutationBatch mutationBatch) {
		if (mutationBatch == null) {
			throw new IllegalArgumentException("Persistent mempool mutation batch is required");
		}
		PersistentMempoolHead current = head();
		if (mutationBatch.batchId().equals(current.lastBatchId())) {
			return new MempoolMutationResult(false, current.batchSequence(), List.of());
		}
		UUID journalEpoch = lifecycleJournal.head(LifecycleJournalStream.MEMPOOL).epoch();
		if (!current.epoch().equals(journalEpoch)) {
			throw new GEFailedException("Persistent mempool and lifecycle journal epochs differ");
		}
		MempoolCanonicalProjectionAdvance projectionAdvance = mutationBatch.canonicalProjectionAdvance();
		if (projectionAdvance != null) {
			MempoolCanonicalProjectionCursor cursor = canonicalProjectionCursor()
					.orElseThrow(() -> new GEFailedException("Mempool canonical projection cursor is not initialized"));
			if (!cursor.epoch().equals(projectionAdvance.epoch())
					|| cursor.sequence() != projectionAdvance.expectedSequence()) {
				throw new GEFailedException("Mempool canonical projection cursor changed concurrently");
			}
		}

		List<LifecycleJournalDraft> drafts = new ArrayList<>(mutationBatch.mutations().size());
		List<LifecycleJournalEntry> entries = new ArrayList<>();
		PersistentMempoolHead advanced = current.advance(mutationBatch.batchId());
		rocks.executeAtomicBatch(batch -> {
			for (MempoolStateMutation mutation : mutationBatch.mutations()) {
				validateMutation(mutation);
				if (mutation instanceof MempoolStateMutation.UpsertActive upsert) {
					batch.put(columnFamilies.mempoolState(),
							upsert.txHash().toArray(), codec.encode(upsert.record()));
				} else if (mutation instanceof MempoolStateMutation.DeleteActive delete) {
					batch.delete(columnFamilies.mempoolState(), delete.txHash().toArray());
				}
				if (mutation.journalDraft() != null) {
					drafts.add(mutation.journalDraft());
				}
			}
			if (!drafts.isEmpty()) {
				entries.addAll(lifecycleJournal.appendToBatch(
						LifecycleJournalStream.MEMPOOL, batch, drafts));
			}
			if (projectionAdvance != null) {
				MempoolCanonicalProjectionCursor advancedCursor = new MempoolCanonicalProjectionCursor(
						MempoolCanonicalProjectionCursor.CURRENT_VERSION,
						projectionAdvance.epoch(), projectionAdvance.newSequence());
				batch.put(columnFamilies.metadata(), MempoolStorageLayout.canonicalProjectionCursorKey(),
						projectionCursorCodec.encode(advancedCursor));
			}
			batch.put(columnFamilies.metadata(),
					MempoolStorageLayout.headKey(), headCodec.encode(advanced));
		});
		return new MempoolMutationResult(true, advanced.batchSequence(), entries);
	}

	@Override
	public Optional<StoredMempoolTransaction> findActive(Hash txHash) {
		if (txHash == null) {
			return Optional.empty();
		}
		try {
			byte[] encoded = rocks.get(columnFamilies.mempoolState(), txHash.toArray());
			return encoded == null ? Optional.empty() : Optional.of(codec.decode(encoded));
		} catch (RocksDBException | IllegalArgumentException failure) {
			throw new GEFailedException("Cannot read persistent mempool transaction " + txHash, failure);
		}
	}

	@Override
	public MempoolRecoveryPage scanActive(Hash afterExclusive, int limit) {
		if (limit < 1 || limit > MAX_SCAN_LIMIT) {
			throw new IllegalArgumentException("Persistent mempool scan limit is invalid");
		}
		List<StoredMempoolTransaction> records = new ArrayList<>(Math.min(limit, 256));
		try (RocksIterator iterator = rocks.newIterator(columnFamilies.mempoolState())) {
			if (afterExclusive == null) {
				iterator.seekToFirst();
			} else {
				iterator.seek(afterExclusive.toArray());
				if (iterator.isValid() && Arrays.equals(iterator.key(), afterExclusive.toArray())) {
					iterator.next();
				}
			}
			while (iterator.isValid() && records.size() < limit) {
				StoredMempoolTransaction record = codec.decode(iterator.value());
				if (!Arrays.equals(iterator.key(), record.txHash().toArray())) {
					throw new GEFailedException("Persistent mempool key/value hash mismatch");
				}
				records.add(record);
				iterator.next();
			}
			boolean hasMore = iterator.isValid();
			iterator.status();
			Hash nextCursor = records.isEmpty() ? null : records.getLast().txHash();
			return new MempoolRecoveryPage(records, nextCursor, hasMore);
		} catch (RocksDBException | IllegalArgumentException failure) {
			throw new GEFailedException("Cannot scan persistent mempool", failure);
		}
	}

	private void validateMutation(MempoolStateMutation mutation) {
		LifecycleJournalDraft draft = mutation.journalDraft();
		if (draft != null && (draft.stream() != LifecycleJournalStream.MEMPOOL
				|| !draft.primaryHash().equals(mutation.txHash()))) {
			throw new IllegalArgumentException("Persistent mempool mutation/journal hash mismatch");
		}
		if (mutation instanceof MempoolStateMutation.UpsertActive && (draft == null
				|| (draft.operation() != LifecycleJournalOperation.PENDING
						&& draft.operation() != LifecycleJournalOperation.REORG_READD))) {
			throw new IllegalArgumentException("Persistent mempool upsert requires an admission journal draft");
		}
		if (mutation instanceof MempoolStateMutation.UpsertActive upsert) {
			MempoolAdmissionReason admission = upsert.record().admissionReason();
			LifecycleJournalOperation expectedOperation = admission == MempoolAdmissionReason.REORG
					? LifecycleJournalOperation.REORG_READD
					: LifecycleJournalOperation.PENDING;
			int expectedReason = switch (admission) {
				case NEW -> MempoolLifecycleReason.NEW.code();
				case REORG -> MempoolLifecycleReason.REORG.code();
				case SYNC -> MempoolLifecycleReason.SYNC.code();
			};
			if (draft.operation() != expectedOperation || draft.reasonCode() != expectedReason
					|| !Arrays.equals(draft.payload(), upsert.record().rawSignedTx())) {
				throw new IllegalArgumentException("Persistent mempool admission state/journal mismatch");
			}
		}
		if (mutation instanceof MempoolStateMutation.DeleteActive && draft != null
				&& draft.operation() != LifecycleJournalOperation.REPLACED
				&& draft.operation() != LifecycleJournalOperation.DROPPED) {
			throw new IllegalArgumentException("Persistent mempool delete has an invalid journal operation");
		}
	}
}
