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
package global.goldenera.node.core.storage.blockchain;

import static com.google.common.base.Preconditions.checkArgument;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.properties.EquivocationProperties;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence;
import global.goldenera.node.core.storage.blockchain.serialization.EquivocationEvidenceCodec;
import global.goldenera.node.core.storage.blockchain.serialization.EquivocationStorageMetadataCodec;
import global.goldenera.node.core.storage.blockchain.serialization.EquivocationStorageMetadataCodec.EquivocationStorageMetadata;
import lombok.extern.slf4j.Slf4j;

/**
 * Persistent node-local repository keyed by block height and validator identity.
 * The metadata/index schema is a one-way storage upgrade: a deterministic codec
 * barrier prevents older binaries from reopening and mutating an upgraded database.
 */
@Repository
@Slf4j
public class EquivocationEvidenceRepository {

	private static final int EVIDENCE_KEY_BYTES = Long.BYTES + Address.SIZE;
	private static final byte[] METADATA_KEY = "EQUIVOCATION_STORAGE_METADATA_V1".getBytes(StandardCharsets.UTF_8);
	private static final byte[] CONFLICT_INDEX_PREFIX = "EQUIVOCATION_CONFLICT_INDEX_V1/".getBytes(StandardCharsets.UTF_8);
	private static final byte[] TRACKED_INDEX_PREFIX = "EQUIVOCATION_TRACKED_INDEX_V1/".getBytes(StandardCharsets.UTF_8);
	private static final byte[] STORAGE_BARRIER_KEY = "\uffffEQUIVOCATION_STORAGE_BARRIER_V1"
			.getBytes(StandardCharsets.UTF_8);
	private static final byte[] STORAGE_BARRIER_VALUE = storageBarrierValue();
	private static final byte[] SINGLE_STATE = { 0 };
	private static final byte[] CONFLICT_STATE = { 1 };

	private final RocksDBRepository rocksDBRepository;
	private final RocksDbColumnFamilies columnFamilies;
	private final EquivocationEvidenceCodec codec;
	private final EquivocationStorageMetadataCodec metadataCodec;
	private final EquivocationProperties properties;

	@Autowired
	public EquivocationEvidenceRepository(
			RocksDBRepository rocksDBRepository,
			RocksDbColumnFamilies columnFamilies,
			EquivocationEvidenceCodec codec,
			EquivocationProperties properties) {
		this.rocksDBRepository = rocksDBRepository;
		this.columnFamilies = columnFamilies;
		this.codec = codec;
		this.properties = properties;
		this.metadataCodec = new EquivocationStorageMetadataCodec();
	}

	public EquivocationEvidenceRepository(
			RocksDBRepository rocksDBRepository,
			RocksDbColumnFamilies columnFamilies,
			EquivocationEvidenceCodec codec) {
		this(rocksDBRepository, columnFamilies, codec, new EquivocationProperties());
	}

	public Optional<EquivocationEvidence> find(long height, Address identity) {
		try {
			byte[] value = rocksDBRepository.get(columnFamilies.equivocations(), key(height, identity));
			return value == null ? Optional.empty() : Optional.of(codec.decode(value));
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to read equivocation evidence", e);
		}
	}

	/**
	 * Stores validated evidence and all derived counters atomically. Once a conflict
	 * exists it cannot be replaced by a singleton. New singleton observations older
	 * than an explicitly configured rolling height window are not persisted.
	 */
	public synchronized SaveResult save(EquivocationEvidence evidence) {
		byte[] evidenceKey = key(evidence.height(), evidence.identity());
		try {
			EquivocationStorageMetadata metadata = loadOrRebuildMetadata();
			byte[] previous = rocksDBRepository.get(columnFamilies.equivocations(), evidenceKey);
			boolean previousConflict = previous != null && codec.isEncodedConflict(previous);
			byte[] trackedState = rocksDBRepository.get(columnFamilies.metadata(), trackedIndexKey(evidenceKey));
			if (!isTrackedStateConsistent(previous, previousConflict, trackedState)) {
				log.warn("Detected evidence written outside the current equivocation storage schema; rebuilding indexes");
				metadata = rebuildMetadata();
				previous = rocksDBRepository.get(columnFamilies.equivocations(), evidenceKey);
				previousConflict = previous != null && codec.isEncodedConflict(previous);
			}
			if (previousConflict && !evidence.isConflict()) {
				throw new IllegalArgumentException("Persisted equivocation conflict cannot be downgraded");
			}

			long highWatermark = Math.max(metadata.highWatermark(), evidence.height());
			long cutoff = retentionCutoff(highWatermark);
			boolean staleSingle = !evidence.isConflict() && cutoff >= 0 && evidence.height() < cutoff;
			byte[] encoded = staleSingle ? null : codec.encode(evidence);
			long conflictCount = metadata.conflictCount();
			long singleCount = metadata.singleCount();
			if (staleSingle) {
				if (previous != null) {
					singleCount = Math.decrementExact(singleCount);
				}
			} else {
				if (previous == null) {
					if (evidence.isConflict()) {
						conflictCount = Math.incrementExact(conflictCount);
					} else {
						singleCount = Math.incrementExact(singleCount);
					}
				} else if (!previousConflict && evidence.isConflict()) {
					conflictCount = Math.incrementExact(conflictCount);
					singleCount = Math.decrementExact(singleCount);
				}
			}

			MutableMetadata updated = new MutableMetadata(
					conflictCount, singleCount, highWatermark,
					metadata.retentionBlocks(), metadata.retentionGeneration(), cutoff, metadata.pruneCursor());
			boolean wasConflict = previousConflict;
			boolean hadPrevious = previous != null;
			rocksDBRepository.executeAtomicBatch(batch -> {
				if (staleSingle && hadPrevious) {
					batch.delete(columnFamilies.equivocations(), evidenceKey);
					batch.delete(columnFamilies.metadata(), trackedIndexKey(evidenceKey));
				} else if (!staleSingle) {
					batch.put(columnFamilies.equivocations(), evidenceKey, encoded);
					batch.put(columnFamilies.metadata(), trackedIndexKey(evidenceKey),
							evidence.isConflict() ? CONFLICT_STATE : SINGLE_STATE);
					if (!wasConflict && evidence.isConflict()) {
						batch.put(columnFamilies.metadata(), conflictIndexKey(evidenceKey), new byte[0]);
					}
				}
				pruneStaleSingles(batch, updated, cutoff, evidenceKey);
				batch.put(columnFamilies.metadata(), METADATA_KEY, metadataCodec.encode(updated.freeze()));
			});
			return new SaveResult(
					!staleSingle,
					!previousConflict && evidence.isConflict(),
					updated.statistics());
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to store equivocation evidence", e);
		}
	}

	public synchronized List<EquivocationEvidence> findConflicts(int limit) {
		return findConflictPage(null, limit).evidence();
	}

	/** Reads a deterministic indexed page after an opaque evidence-key cursor. */
	public synchronized ConflictPage findConflictPage(byte[] exclusiveCursor, int limit) {
		checkArgument(limit >= 1 && limit <= 1000, "Limit must be in range 1..1000");
		checkArgument(exclusiveCursor == null || exclusiveCursor.length == EVIDENCE_KEY_BYTES,
				"Invalid equivocation conflict cursor");
		loadOrRebuildMetadata();
		List<EquivocationEvidence> result = new ArrayList<>(Math.min(limit, 16));
		byte[] lastEvidenceKey = null;
		try (RocksIterator iterator = rocksDBRepository.newIterator(columnFamilies.metadata())) {
			byte[] seekKey = exclusiveCursor == null
					? CONFLICT_INDEX_PREFIX : conflictIndexKey(exclusiveCursor);
			iterator.seek(seekKey);
			if (exclusiveCursor != null && iterator.isValid() && Arrays.equals(iterator.key(), seekKey)) {
				iterator.next();
			}
			while (iterator.isValid() && result.size() < limit) {
				byte[] indexKey = iterator.key();
				if (!startsWith(indexKey, CONFLICT_INDEX_PREFIX)) {
					break;
				}
				byte[] evidenceKey = Arrays.copyOfRange(
						indexKey, CONFLICT_INDEX_PREFIX.length, indexKey.length);
				byte[] encoded = rocksDBRepository.get(columnFamilies.equivocations(), evidenceKey);
				if (encoded == null || !codec.isEncodedConflict(encoded)) {
					throw new IllegalStateException("Equivocation conflict index is inconsistent");
				}
				result.add(codec.decode(encoded));
				lastEvidenceKey = evidenceKey;
				iterator.next();
			}
			iterator.status();
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to scan equivocation evidence", e);
		}
		byte[] nextCursor = result.size() == limit ? lastEvidenceKey : null;
		return new ConflictPage(List.copyOf(result), nextCursor);
	}

	/** Returns the persisted count after at most one legacy/corruption backfill scan. */
	public synchronized long countConflicts() {
		return loadOrRebuildMetadata().conflictCount();
	}

	public synchronized StorageStatistics statistics() {
		EquivocationStorageMetadata metadata = loadOrRebuildMetadata();
		return statistics(metadata);
	}

	private EquivocationStorageMetadata loadOrRebuildMetadata() {
		try {
			byte[] encoded = rocksDBRepository.get(columnFamilies.metadata(), METADATA_KEY);
			if (encoded != null) {
				try {
					EquivocationStorageMetadata metadata = metadataCodec.decode(encoded);
					return reconcileStoragePolicy(metadata);
				} catch (IllegalArgumentException corrupted) {
					log.warn("Equivocation storage metadata is corrupt; rebuilding it from evidence", corrupted);
				}
			}
			return rebuildMetadata();
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to read equivocation storage metadata", e);
		}
	}

	private EquivocationStorageMetadata rebuildMetadata() {
		long conflicts = 0;
		long singles = 0;
		long highWatermark = -1;
		List<byte[]> conflictKeys = new ArrayList<>();
		List<TrackedEvidence> trackedEvidence = new ArrayList<>();
		boolean storageAlreadyUpgraded;
		try {
			storageAlreadyUpgraded = Arrays.equals(
					rocksDBRepository.get(columnFamilies.equivocations(), STORAGE_BARRIER_KEY), STORAGE_BARRIER_VALUE);
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to inspect equivocation storage compatibility barrier", e);
		}
		try (RocksIterator iterator = rocksDBRepository.newIterator(columnFamilies.equivocations())) {
			iterator.seekToFirst();
			while (iterator.isValid()) {
				byte[] storedKey = iterator.key();
				if (Arrays.equals(storedKey, STORAGE_BARRIER_KEY)) {
					iterator.next();
					continue;
				}
				long storedHeight = height(storedKey);
				boolean conflict = codec.isEncodedConflict(iterator.value());
				trackedEvidence.add(new TrackedEvidence(Arrays.copyOf(storedKey, storedKey.length), conflict));
				if (conflict) {
					conflicts = Math.incrementExact(conflicts);
					conflictKeys.add(Arrays.copyOf(storedKey, storedKey.length));
				} else {
					singles = Math.incrementExact(singles);
				}
				highWatermark = Math.max(highWatermark, storedHeight);
				iterator.next();
			}
			iterator.status();
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to rebuild equivocation storage metadata", e);
		}
		long retentionBlocks = properties.getSingleObservationRetentionBlocks();
		EquivocationStorageMetadata rebuilt = new EquivocationStorageMetadata(
				conflicts, singles, highWatermark,
				retentionBlocks, 0, retentionCutoff(highWatermark, retentionBlocks), null);
		rocksDBRepository.executeAtomicBatch(batch -> {
			deleteIndexEntries(batch, CONFLICT_INDEX_PREFIX);
			deleteIndexEntries(batch, TRACKED_INDEX_PREFIX);
			for (TrackedEvidence tracked : trackedEvidence) {
				batch.put(columnFamilies.metadata(), trackedIndexKey(tracked.key()),
						tracked.conflict() ? CONFLICT_STATE : SINGLE_STATE);
			}
			for (byte[] conflictKey : conflictKeys) {
				batch.put(columnFamilies.metadata(), conflictIndexKey(conflictKey), new byte[0]);
			}
			batch.put(columnFamilies.equivocations(), STORAGE_BARRIER_KEY, STORAGE_BARRIER_VALUE);
			batch.put(columnFamilies.metadata(), METADATA_KEY, metadataCodec.encode(rebuilt));
		});
		log.info("Backfilled equivocation storage metadata: {} conflicts, {} retained single observations, height {}",
				conflicts, singles, highWatermark);
		if (!storageAlreadyUpgraded) {
			log.warn("Equivocation storage was upgraded to metadata/index schema v1. "
					+ "Older node binaries must not reopen this database; their startup is blocked intentionally.");
		}
		return rebuilt;
	}

	private EquivocationStorageMetadata reconcileStoragePolicy(EquivocationStorageMetadata metadata) {
		long configuredRetention = properties.getSingleObservationRetentionBlocks();
		try {
			byte[] barrier = rocksDBRepository.get(columnFamilies.equivocations(), STORAGE_BARRIER_KEY);
			boolean barrierMissing = !Arrays.equals(barrier, STORAGE_BARRIER_VALUE);
			if (barrierMissing) {
				return rebuildMetadata();
			}
			if (metadata.retentionBlocks() == configuredRetention) {
				return metadata;
			}
			long generation = Math.incrementExact(metadata.retentionGeneration());
			EquivocationStorageMetadata reconciled = new EquivocationStorageMetadata(
					metadata.conflictCount(), metadata.singleCount(), metadata.highWatermark(),
					configuredRetention, generation,
					retentionCutoff(metadata.highWatermark(), configuredRetention), null);
			rocksDBRepository.executeAtomicBatch(batch -> {
				batch.put(columnFamilies.metadata(), METADATA_KEY, metadataCodec.encode(reconciled));
			});
			return reconciled;
		} catch (RocksDBException e) {
			throw new IllegalStateException("Failed to reconcile equivocation storage policy", e);
		}
	}

	private void pruneStaleSingles(
			WriteBatch batch,
			MutableMetadata metadata,
			long cutoff,
			byte[] protectedKey) throws RocksDBException {
		if (cutoff < 0 || metadata.singleCount == 0) {
			return;
		}
		int examined = 0;
		try (RocksIterator iterator = rocksDBRepository.newIterator(columnFamilies.equivocations())) {
			byte[] cursor = metadata.pruneCursor;
			if (cursor == null) {
				iterator.seekToFirst();
			} else {
				iterator.seek(cursor);
				if (iterator.isValid() && Arrays.equals(iterator.key(), cursor)) {
					iterator.next();
				}
			}
			while (iterator.isValid() && examined < properties.getPruneBatchSize()) {
				byte[] storedKey = iterator.key();
				if (Arrays.equals(storedKey, STORAGE_BARRIER_KEY)) {
					break;
				}
				if (height(storedKey) >= cutoff) {
					break;
				}
				examined++;
				metadata.pruneCursor = Arrays.copyOf(storedKey, storedKey.length);
				if (!Arrays.equals(storedKey, protectedKey) && !codec.isEncodedConflict(iterator.value())) {
					batch.delete(columnFamilies.equivocations(), storedKey);
					batch.delete(columnFamilies.metadata(), trackedIndexKey(storedKey));
					metadata.singleCount = Math.decrementExact(metadata.singleCount);
				}
				iterator.next();
			}
			iterator.status();
		}
	}

	private long retentionCutoff(long highWatermark) {
		return retentionCutoff(highWatermark, properties.getSingleObservationRetentionBlocks());
	}

	private long retentionCutoff(long highWatermark, long retention) {
		if (retention == 0 || highWatermark < retention) {
			return -1;
		}
		return highWatermark - retention + 1;
	}

	private void deleteIndexEntries(WriteBatch batch, byte[] prefix) throws RocksDBException {
		try (RocksIterator iterator = rocksDBRepository.newIterator(columnFamilies.metadata())) {
			iterator.seek(prefix);
			while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
				batch.delete(columnFamilies.metadata(), iterator.key());
				iterator.next();
			}
			iterator.status();
		}
	}

	private boolean isTrackedStateConsistent(byte[] evidence, boolean conflict, byte[] trackedState) {
		if (evidence == null) {
			return trackedState == null;
		}
		return Arrays.equals(trackedState, conflict ? CONFLICT_STATE : SINGLE_STATE);
	}

	private byte[] conflictIndexKey(byte[] evidenceKey) {
		return prefixedKey(CONFLICT_INDEX_PREFIX, evidenceKey);
	}

	private byte[] trackedIndexKey(byte[] evidenceKey) {
		return prefixedKey(TRACKED_INDEX_PREFIX, evidenceKey);
	}

	private byte[] prefixedKey(byte[] prefix, byte[] evidenceKey) {
		byte[] result = Arrays.copyOf(prefix, prefix.length + evidenceKey.length);
		System.arraycopy(evidenceKey, 0, result, prefix.length, evidenceKey.length);
		return result;
	}

	private boolean startsWith(byte[] value, byte[] prefix) {
		if (value == null || value.length < prefix.length) {
			return false;
		}
		for (int index = 0; index < prefix.length; index++) {
			if (value[index] != prefix[index]) {
				return false;
			}
		}
		return true;
	}

	private static byte[] storageBarrierValue() {
		return ByteBuffer.allocate(65).putInt(2).array();
	}

	private long height(byte[] storedKey) {
		if (storedKey == null || storedKey.length != EVIDENCE_KEY_BYTES || storedKey[0] < 0) {
			throw new IllegalStateException("Invalid key in equivocation evidence column family");
		}
		return ByteBuffer.wrap(storedKey).getLong();
	}

	static byte[] key(long height, Address identity) {
		checkArgument(height >= 0, "Evidence height cannot be negative");
		checkArgument(identity != null, "Evidence identity cannot be null");
		return ByteBuffer.allocate(EVIDENCE_KEY_BYTES)
				.putLong(height)
				.put(identity.toArray())
				.array();
	}

	private StorageStatistics statistics(EquivocationStorageMetadata metadata) {
		return new StorageStatistics(
				metadata.conflictCount(), metadata.singleCount(), metadata.highWatermark(),
				metadata.retentionBlocks(), metadata.retentionGeneration(), metadata.pruneCutoff());
	}

	public record StorageStatistics(
			long conflicts,
			long singles,
			long highWatermark,
			long retentionBlocks,
			long retentionGeneration,
			long pruneCutoff) {
	}

	public record SaveResult(
			boolean persisted,
			boolean newConflict,
			StorageStatistics statistics) {
	}

	public record ConflictPage(List<EquivocationEvidence> evidence, byte[] nextCursor) {
		public ConflictPage {
			evidence = List.copyOf(evidence);
			nextCursor = nextCursor == null ? null : Arrays.copyOf(nextCursor, nextCursor.length);
		}

		@Override
		public byte[] nextCursor() {
			return nextCursor == null ? null : Arrays.copyOf(nextCursor, nextCursor.length);
		}
	}

	private static final class MutableMetadata {
		private final long conflictCount;
		private long singleCount;
		private final long highWatermark;
		private final long retentionBlocks;
		private final long retentionGeneration;
		private final long pruneCutoff;
		private byte[] pruneCursor;

		private MutableMetadata(
				long conflictCount,
				long singleCount,
				long highWatermark,
				long retentionBlocks,
				long retentionGeneration,
				long pruneCutoff,
				byte[] pruneCursor) {
			this.conflictCount = conflictCount;
			this.singleCount = singleCount;
			this.highWatermark = highWatermark;
			this.retentionBlocks = retentionBlocks;
			this.retentionGeneration = retentionGeneration;
			this.pruneCutoff = pruneCutoff;
			this.pruneCursor = pruneCursor;
		}

		private EquivocationStorageMetadata freeze() {
			return new EquivocationStorageMetadata(
					conflictCount, singleCount, highWatermark,
					retentionBlocks, retentionGeneration, pruneCutoff, pruneCursor);
		}

		private StorageStatistics statistics() {
			return new StorageStatistics(
					conflictCount, singleCount, highWatermark,
					retentionBlocks, retentionGeneration, pruneCutoff);
		}
	}

	private record TrackedEvidence(byte[] key, boolean conflict) {
	}
}
