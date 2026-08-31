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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.properties.LifecycleJournalProperties;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.shared.exceptions.GEFailedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class LifecycleJournalRepository implements LifecycleJournalQuery {

	private static final int MAX_READ_BATCH = 4_096;
	private final RocksDBRepository repository;
	private final RocksDbColumnFamilies columnFamilies;
	private final LifecycleJournalProperties properties;
	private final MeterRegistry registry;
	private final LifecycleJournalCodec codec = new LifecycleJournalCodec();
	private final LifecycleJournalHeadCodec headCodec = new LifecycleJournalHeadCodec();

	@Autowired
	public LifecycleJournalRepository(
			RocksDBRepository repository,
			RocksDbColumnFamilies columnFamilies,
			LifecycleJournalProperties properties,
			MeterRegistry registry) {
		this.repository = repository;
		this.columnFamilies = columnFamilies;
		this.properties = properties;
		this.registry = registry;
	}

	LifecycleJournalRepository(RocksDBRepository repository, RocksDbColumnFamilies columnFamilies) {
		this(repository, columnFamilies, new LifecycleJournalProperties(), Metrics.globalRegistry);
	}

	public synchronized void initializeAnchorIfMissing(long anchorHeight, Hash anchorHash) {
		if (anchorHeight < -1L || anchorHash == null) {
			throw new IllegalArgumentException("Lifecycle journal anchor is invalid");
		}
		Optional<LifecycleJournalHead> canonicalHead = findHead(LifecycleJournalStream.CANONICAL);
		Optional<LifecycleJournalHead> mempoolHead = findHead(LifecycleJournalStream.MEMPOOL);
		if (canonicalHead.isPresent() && mempoolHead.isPresent()) {
			if (!canonicalHead.get().epoch().equals(mempoolHead.get().epoch())) {
				throw new GEFailedException("Lifecycle journal streams have inconsistent epochs");
			}
			return;
		}
		UUID epoch = canonicalHead.map(LifecycleJournalHead::epoch)
				.or(() -> mempoolHead.map(LifecycleJournalHead::epoch))
				.orElseGet(UUID::randomUUID);
		repository.executeAtomicBatch(batch -> {
			for (LifecycleJournalStream stream : LifecycleJournalStream.values()) {
				if (findHead(stream).isEmpty()) {
					LifecycleJournalHead initial = new LifecycleJournalHead(
							stream, epoch, 0L, 1L, anchorHeight, anchorHash);
					batch.put(columnFamilies.metadata(), headKey(stream), headCodec.encode(initial));
				}
			}
		});
	}

	@Override
	public LifecycleJournalHead head(LifecycleJournalStream stream) {
		return findHead(stream).orElseThrow(() -> new GEFailedException(
				"Lifecycle journal stream " + stream + " is not initialized"));
	}

	public Optional<LifecycleJournalHead> findHead(LifecycleJournalStream stream) {
		try {
			byte[] encoded = repository.get(columnFamilies.metadata(), headKey(stream));
			return encoded == null ? Optional.empty() : Optional.of(headCodec.decode(encoded));
		} catch (RocksDBException | IllegalArgumentException failure) {
			throw new GEFailedException("Cannot read lifecycle journal head for " + stream, failure);
		}
	}

	@Override
	public List<LifecycleJournalEntry> readAfter(
			LifecycleJournalStream stream, LifecycleJournalCursor cursor, int limit) {
		if (cursor == null) {
			throw new IllegalArgumentException("Lifecycle journal cursor is required");
		}
		LifecycleJournalHead current = head(stream);
		if (!current.epoch().equals(cursor.epoch())) {
			throw new LifecycleJournalEpochException(stream, cursor.epoch(), current.epoch());
		}
		return readAfterCurrentEpoch(stream, cursor.sequence(), limit, current);
	}

	private List<LifecycleJournalEntry> readAfterCurrentEpoch(
			LifecycleJournalStream stream,
			long sequenceExclusive,
			int limit,
			LifecycleJournalHead current) {
		if (sequenceExclusive < 0L || limit < 1 || limit > MAX_READ_BATCH) {
			throw new IllegalArgumentException("Lifecycle journal cursor or limit is invalid");
		}
		if (sequenceExclusive == Long.MAX_VALUE) {
			return List.of();
		}
		if (sequenceExclusive + 1L < current.floorSequence()) {
			throw new LifecycleJournalFloorException(stream, sequenceExclusive, current.floorSequence());
		}
		List<LifecycleJournalEntry> entries = new ArrayList<>(Math.min(limit, 256));
		try (RocksIterator iterator = repository.newIterator(columnFamilies.lifecycleJournal())) {
			iterator.seek(entryKey(stream, sequenceExclusive + 1L));
			while (iterator.isValid() && entries.size() < limit && belongsTo(iterator.key(), stream)) {
				LifecycleJournalEntry entry = codec.decode(iterator.value());
				if (entry.stream() != stream || !entry.epoch().equals(current.epoch())) {
					throw new GEFailedException("Lifecycle journal key/value lineage mismatch");
				}
				entries.add(entry);
				iterator.next();
			}
			iterator.status();
			return List.copyOf(entries);
		} catch (RocksDBException | IllegalArgumentException failure) {
			throw new GEFailedException("Cannot scan lifecycle journal stream " + stream, failure);
		}
	}

	@Override
	public Optional<LifecycleJournalEntry> find(LifecycleJournalStream stream, long sequence) {
		if (sequence < 1L) {
			return Optional.empty();
		}
		try {
			byte[] encoded = repository.get(columnFamilies.lifecycleJournal(), entryKey(stream, sequence));
			return encoded == null ? Optional.empty() : Optional.of(codec.decode(encoded));
		} catch (RocksDBException | IllegalArgumentException failure) {
			throw new GEFailedException("Cannot read lifecycle journal entry " + stream + "/" + sequence, failure);
		}
	}

	public synchronized List<LifecycleJournalEntry> appendToBatch(
			LifecycleJournalStream stream, WriteBatch batch, List<LifecycleJournalDraft> drafts)
			throws RocksDBException {
		if (batch == null || drafts == null) {
			throw new IllegalArgumentException("Lifecycle journal batch and drafts are required");
		}
		if (drafts.isEmpty()) {
			return List.of();
		}
		LifecycleJournalHead current = head(stream);
		List<LifecycleJournalEntry> entries = materialize(stream, current, drafts);
		for (LifecycleJournalEntry entry : entries) {
			batch.put(columnFamilies.lifecycleJournal(), entryKey(stream, entry.sequence()), codec.encode(entry));
		}
		LifecycleJournalHead advanced = applyHardRetentionToBatch(
				stream, batch, current, entries);
		batch.put(columnFamilies.metadata(), headKey(stream), headCodec.encode(advanced));
		return entries;
	}

	public synchronized List<LifecycleJournalEntry> appendMempool(List<LifecycleJournalDraft> drafts) {
		if (drafts == null || drafts.isEmpty()) {
			return List.of();
		}
		List<LifecycleJournalEntry> entries = new ArrayList<>();
		repository.executeAtomicBatch(batch -> entries.addAll(
				appendToBatch(LifecycleJournalStream.MEMPOOL, batch, drafts)));
		return List.copyOf(entries);
	}

	public synchronized void pruneThrough(LifecycleJournalStream stream, long sequenceInclusive) {
		LifecycleJournalHead current = head(stream);
		if (sequenceInclusive < current.floorSequence() - 1L || sequenceInclusive > current.sequence()) {
			throw new IllegalArgumentException("Lifecycle journal prune sequence is outside retained bounds");
		}
		if (sequenceInclusive < current.floorSequence()) {
			return;
		}
		long newFloor = alignFloorToGroupBoundary(stream, sequenceInclusive + 1L, current, List.of());
		if (newFloor <= current.floorSequence()) {
			return;
		}
		repository.executeAtomicBatch(batch -> {
			batch.deleteRange(columnFamilies.lifecycleJournal(),
					entryKey(stream, current.floorSequence()), entryKey(stream, newFloor));
			batch.put(columnFamilies.metadata(), headKey(stream),
					headCodec.encode(current.withFloor(newFloor)));
		});
	}

	private List<LifecycleJournalEntry> materialize(
			LifecycleJournalStream stream, LifecycleJournalHead current, List<LifecycleJournalDraft> drafts) {
		List<LifecycleJournalEntry> entries = new ArrayList<>(drafts.size());
		long sequence = current.sequence();
		for (LifecycleJournalDraft draft : drafts) {
			if (draft.stream() != stream) {
				throw new IllegalArgumentException("Draft stream does not match append stream");
			}
			sequence = Math.incrementExact(sequence);
			entries.add(new LifecycleJournalEntry(
					LifecycleJournalEntry.CURRENT_VERSION,
					current.epoch(),
					sequence,
					LifecycleJournalEventKey.forSequence(current.epoch(), stream, sequence),
					stream,
					draft.operation(),
					draft.groupId(),
					draft.groupOrdinal(),
					draft.groupSize(),
					draft.height(),
					draft.primaryHash(),
					draft.relatedHash(),
					draft.occurredAt(),
					draft.sourceCode(),
					draft.reasonCode(),
					draft.payload()));
		}
		return entries;
	}

	private LifecycleJournalHead applyHardRetentionToBatch(
			LifecycleJournalStream stream,
			WriteBatch batch,
			LifecycleJournalHead current,
			List<LifecycleJournalEntry> newEntries) throws RocksDBException {
		long newSequence = newEntries.getLast().sequence();
		LifecycleJournalHead advanced = current.advance(newSequence);
		long retained = Math.max(0L, newSequence - current.floorSequence() + 1L);
		long hardMaximum = properties.getHardMaxRetainedEntries();
		if (retained <= hardMaximum) {
			return advanced;
		}
		long targetRetained = Math.max(1L, hardMaximum - Math.max(10_000L, hardMaximum / 10L));
		long proposedFloor = newSequence - targetRetained + 1L;
		long hardFloor = alignFloorToGroupBoundary(stream, proposedFloor, current, newEntries);
		if (hardFloor <= current.floorSequence()) {
			return advanced;
		}
		long deleted = hardFloor - current.floorSequence();
		batch.deleteRange(
				columnFamilies.lifecycleJournal(),
				entryKey(stream, current.floorSequence()),
				entryKey(stream, hardFloor));
		registry.counter("lifecycle.journal.hard_pruned", "stream", stream.name()).increment(deleted);
		log.warn("Lifecycle journal {} crossed its hard limit; pruning {} entries to floor {}. "
				+ "Consumers behind the new floor will fail closed.", stream, deleted, hardFloor);
		return advanced.withFloor(hardFloor);
	}

	private long alignFloorToGroupBoundary(
			LifecycleJournalStream stream,
			long proposedFloor,
			LifecycleJournalHead current,
			List<LifecycleJournalEntry> newEntries) {
		if (proposedFloor <= current.floorSequence() || proposedFloor > current.sequence() + newEntries.size()) {
			return proposedFloor;
		}
		LifecycleJournalEntry boundaryEntry = newEntries.stream()
				.filter(entry -> entry.sequence() == proposedFloor)
				.findFirst()
				.or(() -> find(stream, proposedFloor))
				.orElseThrow(() -> new GEFailedException(
						"Lifecycle journal retention boundary entry is unavailable: " + stream + "/" + proposedFloor));
		if (boundaryEntry.groupId() == null || boundaryEntry.groupOrdinal() == 0) {
			return proposedFloor;
		}
		long groupStart = proposedFloor - boundaryEntry.groupOrdinal();
		if (groupStart < current.floorSequence()) {
			throw new GEFailedException("Lifecycle journal retained floor already splits a canonical group");
		}
		return groupStart;
	}

	private byte[] headKey(LifecycleJournalStream stream) {
		return LifecycleJournalStorageLayout.headKey(stream);
	}

	private byte[] entryKey(LifecycleJournalStream stream, long sequence) {
		return ByteBuffer.allocate(Byte.BYTES + Long.BYTES)
				.put((byte) stream.code())
				.putLong(sequence)
				.array();
	}

	private boolean belongsTo(byte[] key, LifecycleJournalStream stream) {
		return key != null && key.length == Byte.BYTES + Long.BYTES && key[0] == (byte) stream.code();
	}
}
