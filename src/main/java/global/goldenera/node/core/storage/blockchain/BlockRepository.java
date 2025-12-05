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

import static lombok.AccessLevel.PRIVATE;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.springframework.stereotype.Repository;

import com.github.benmanes.caffeine.cache.Cache;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncoder;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Low-level repository for blockchain data storage in RocksDB.
 * Returns raw storage objects (StoredBlock) and index data (Hash, height).
 * 
 * IMPORTANT: Always use StoredBlock.getHash() instead of Block.getHash() or
 * BlockHeader.getHash() - the latter recalculate the hash every time!
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockRepository {

	@Getter
	RocksDBRepository repository;
	RocksDbColumnFamilies cf;
	Cache<Hash, StoredBlock> blockCache;
	Cache<Hash, StoredBlock> headerCache; // Cache for partial (header-only) blocks
	Cache<Long, Hash> heightCache;
	Cache<Hash, Tx> txCache;
	AtomicReference<StoredBlock> latestBlockCache = new AtomicReference<>();

	private final ThreadLocal<List<Runnable>> postCommitActions = new ThreadLocal<>();

	private void scheduleInvalidation(Runnable action) {
		List<Runnable> actions = postCommitActions.get();
		if (actions != null) {
			actions.add(action);
		} else {
			action.run();
		}
	}

	// ========================
	// StoredBlock operations
	// ========================

	public Optional<StoredBlock> getStoredBlockByHash(Hash hash) {
		StoredBlock cached = blockCache.getIfPresent(hash);
		if (cached != null)
			return Optional.of(cached);
		try {
			byte[] data = repository.get(cf.blocks(), hash.toArray());
			if (data == null)
				return Optional.empty();

			StoredBlock storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data));
			if (!storedBlock.isPartial()) {
				blockCache.put(hash, storedBlock);
			}
			return Optional.of(storedBlock);
		} catch (RocksDBException e) {
			throw new RuntimeException("Failed to read StoredBlock " + hash, e);
		}
	}

	/**
	 * Gets a partial StoredBlock containing only the header (no transactions).
	 * More efficient when only header data is needed.
	 * Use StoredBlock.getHash() to get the pre-computed hash!
	 */
	public Optional<StoredBlock> getStoredBlockHeaderByHash(Hash hash) {
		// Check full block cache first - if we have full block, return it
		StoredBlock fullCached = blockCache.getIfPresent(hash);
		if (fullCached != null) {
			return Optional.of(fullCached);
		}
		// Check header cache
		StoredBlock headerCached = headerCache.getIfPresent(hash);
		if (headerCached != null) {
			return Optional.of(headerCached);
		}
		try {
			byte[] data = repository.get(cf.blocks(), hash.toArray());
			if (data == null)
				return Optional.empty();

			// Decode with withoutBody=true for efficiency
			StoredBlock storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data), true);
			// Cache partial block in headerCache
			headerCache.put(hash, storedBlock);
			return Optional.of(storedBlock);
		} catch (RocksDBException e) {
			throw new RuntimeException("Failed to read StoredBlock header " + hash, e);
		}
	}

	public boolean hasBlockData(Hash hash) {
		if (blockCache.getIfPresent(hash) != null)
			return true;
		try {
			// Optimization: Read bytes but DO NOT decode/deserialize
			byte[] data = repository.get(cf.blocks(), hash.toArray());
			return data != null;
		} catch (RocksDBException e) {
			log.error("Failed to check block existence for {}", hash, e);
			return false;
		}
	}

	public StoredBlock getStoredBlockByHashOrThrow(Hash hash) {
		return getStoredBlockByHash(hash)
				.orElseThrow(() -> new GENotFoundException("Block not found: " + hash));
	}

	public List<StoredBlock> getStoredBlocksByHashes(List<Hash> hashes) {
		if (hashes.isEmpty())
			return new ArrayList<>();

		List<StoredBlock> blocks = new ArrayList<>(hashes.size());
		List<Integer> missingIndices = new ArrayList<>();
		List<byte[]> missingKeys = new ArrayList<>();

		// Check cache first
		for (int i = 0; i < hashes.size(); i++) {
			Hash hash = hashes.get(i);
			StoredBlock cached = blockCache.getIfPresent(hash);
			if (cached != null) {
				blocks.add(cached);
			} else {
				blocks.add(null); // Placeholder
				missingIndices.add(i);
				missingKeys.add(hash.toArray());
			}
		}

		// MultiGet for cache misses
		if (!missingKeys.isEmpty()) {
			try {
				List<byte[]> values = repository.multiGet(cf.blocks(), missingKeys);

				// Parallel decoding for performance
				StoredBlock[] decodedBlocks = new StoredBlock[values.size()];
				IntStream.range(0, values.size()).parallel().forEach(j -> {
					byte[] data = values.get(j);
					if (data != null) {
						decodedBlocks[j] = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data));
					}
				});

				for (int j = 0; j < values.size(); j++) {
					StoredBlock storedBlock = decodedBlocks[j];
					if (storedBlock != null) {
						if (!storedBlock.isPartial()) {
							blockCache.put(hashes.get(missingIndices.get(j)), storedBlock);
						}
						blocks.set(missingIndices.get(j), storedBlock);
					}
				}
			} catch (RocksDBException e) {
				log.error("MultiGet failed in getStoredBlocksByHashes", e);
			}
		}

		// Remove nulls
		blocks.removeIf(b -> b == null);
		return blocks;
	}

	/**
	 * Gets partial StoredBlocks (headers only) by hashes - more efficient for
	 * header-only operations.
	 * Use StoredBlock.getHash() to get the pre-computed hash!
	 */
	public List<StoredBlock> getStoredBlockHeadersByHashes(List<Hash> hashes) {
		if (hashes.isEmpty())
			return new ArrayList<>();

		List<StoredBlock> blocks = new ArrayList<>(hashes.size());
		List<Integer> missingIndices = new ArrayList<>();
		List<byte[]> missingKeys = new ArrayList<>();

		// Check caches first (full blocks are fine, we just need header data)
		for (int i = 0; i < hashes.size(); i++) {
			Hash hash = hashes.get(i);
			StoredBlock fullCached = blockCache.getIfPresent(hash);
			if (fullCached != null) {
				blocks.add(fullCached);
			} else {
				StoredBlock headerCached = headerCache.getIfPresent(hash);
				if (headerCached != null) {
					blocks.add(headerCached);
				} else {
					blocks.add(null); // Placeholder
					missingIndices.add(i);
					missingKeys.add(hash.toArray());
				}
			}
		}

		// MultiGet for cache misses (using withoutBody=true for efficiency)
		if (!missingKeys.isEmpty()) {
			try {
				List<byte[]> values = repository.multiGet(cf.blocks(), missingKeys);

				// Parallel decoding for performance
				StoredBlock[] decodedBlocks = new StoredBlock[values.size()];
				IntStream.range(0, values.size()).parallel().forEach(j -> {
					byte[] data = values.get(j);
					if (data != null) {
						// Use withoutBody=true for header-only decoding
						decodedBlocks[j] = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data), true);
					}
				});

				for (int j = 0; j < values.size(); j++) {
					StoredBlock storedBlock = decodedBlocks[j];
					if (storedBlock != null) {
						// Cache in headerCache
						headerCache.put(hashes.get(missingIndices.get(j)), storedBlock);
						blocks.set(missingIndices.get(j), storedBlock);
					}
				}
			} catch (RocksDBException e) {
				log.error("MultiGet failed in getStoredBlockHeadersByHashes", e);
			}
		}

		// Remove nulls
		blocks.removeIf(b -> b == null);
		return blocks;
	}

	// ========================
	// Height index operations
	// ========================

	public Optional<Hash> getBlockHashByHeight(long height) {
		Hash cached = heightCache.getIfPresent(height);
		if (cached != null)
			return Optional.of(cached);
		try {
			byte[] hashBytes = repository.get(cf.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
			if (hashBytes == null)
				return Optional.empty();

			Hash hash = Hash.wrap(hashBytes);
			heightCache.put(height, hash);
			return Optional.of(hash);
		} catch (RocksDBException e) {
			throw new RuntimeException("Failed to read HashByHeight " + height, e);
		}
	}

	public Optional<StoredBlock> getStoredBlockByHeight(long height) {
		return getBlockHashByHeight(height).flatMap(this::getStoredBlockByHash);
	}

	/**
	 * Gets partial StoredBlock (header only) by height - more efficient for
	 * header-only operations.
	 * Use StoredBlock.getHash() to get the pre-computed hash!
	 */
	public Optional<StoredBlock> getStoredBlockHeaderByHeight(long height) {
		return getBlockHashByHeight(height).flatMap(this::getStoredBlockHeaderByHash);
	}

	// ========================
	// Latest block operations
	// ========================

	public Optional<Hash> getLatestBlockHash() {
		StoredBlock cachedLatest = latestBlockCache.get();
		if (cachedLatest != null) {
			return Optional.of(cachedLatest.getHash());
		}
		try {
			byte[] data = repository.get(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH);
			return (data == null) ? Optional.empty() : Optional.of(Hash.wrap(data));
		} catch (RocksDBException e) {
			throw new RuntimeException("Failed to read LATEST_BLOCK_HASH", e);
		}
	}

	public Optional<StoredBlock> getLatestStoredBlock() {
		StoredBlock cachedLatest = latestBlockCache.get();
		if (cachedLatest != null) {
			return Optional.of(cachedLatest);
		}
		Optional<StoredBlock> loaded = getLatestBlockHash().flatMap(this::getStoredBlockByHash);
		loaded.ifPresent(latestBlockCache::set);
		return loaded;
	}

	public StoredBlock getLatestStoredBlockOrThrow() {
		return getLatestStoredBlock()
				.orElseThrow(() -> new GENotFoundException("No latest block found"));
	}

	/**
	 * Gets the height of the latest block in the canonical chain.
	 * Uses cached StoredBlock.getHeight() which is already computed.
	 */
	public Optional<Long> getLatestBlockHeight() {
		StoredBlock cachedLatest = latestBlockCache.get();
		if (cachedLatest != null) {
			return Optional.of(cachedLatest.getHeight());
		}
		return getLatestStoredBlock().map(StoredBlock::getHeight);
	}

	// ========================
	// Transaction operations
	// ========================

	/**
	 * Gets the block hash where a transaction is stored.
	 * This is raw index lookup - does not verify canonical status.
	 */
	public Optional<Hash> getTransactionBlockHash(Hash txHash) {
		try {
			byte[] blockHashBytes = repository.get(cf.txIndex(), txHash.toArray());
			if (blockHashBytes == null)
				return Optional.empty();
			return Optional.of(Hash.wrap(blockHashBytes));
		} catch (RocksDBException e) {
			throw new RuntimeException("Failed to read TxIndex " + txHash, e);
		}
	}

	/**
	 * Gets a cached transaction by hash, or empty if not cached.
	 */
	public Optional<Tx> getCachedTransaction(Hash txHash) {
		Tx cached = txCache.getIfPresent(txHash);
		return Optional.ofNullable(cached);
	}

	/**
	 * Puts a transaction into the cache.
	 */
	public void cacheTransaction(Hash txHash, Tx tx) {
		txCache.put(txHash, tx);
	}

	// ========================
	// Range queries
	// ========================

	/**
	 * Finds StoredBlocks by height range. Returns full blocks.
	 */
	public List<StoredBlock> findStoredBlocksByHeightRange(long fromHeight, long toHeight) {
		if (fromHeight > toHeight)
			return new ArrayList<>();

		// Step 1: Collect all hashes using iterator (fast - just reading index)
		List<Hash> hashes = new ArrayList<>();
		try (RocksIterator iterator = repository.newIterator(cf.hashByHeight())) {
			iterator.seek(longToBytes(fromHeight));
			while (iterator.isValid()) {
				long currentHeight = bytesToLong(iterator.key());
				if (currentHeight > toHeight)
					break;
				Hash hash = Hash.wrap(iterator.value());
				heightCache.put(currentHeight, hash);
				hashes.add(hash);
				iterator.next();
			}
		}

		if (hashes.isEmpty())
			return new ArrayList<>();

		// Step 2: Batch fetch using existing method
		return getStoredBlocksByHashes(hashes);
	}

	/**
	 * Finds partial StoredBlocks (headers only) by height range.
	 * More efficient when only header data is needed.
	 * Use StoredBlock.getHash() to get the pre-computed hash!
	 */
	public List<StoredBlock> findStoredBlockHeadersByHeightRange(long fromHeight, long toHeight) {
		if (fromHeight > toHeight)
			return new ArrayList<>();

		// Step 1: Collect all hashes using iterator (fast - just reading index)
		List<Hash> hashes = new ArrayList<>();
		try (RocksIterator iterator = repository.newIterator(cf.hashByHeight())) {
			iterator.seek(longToBytes(fromHeight));
			while (iterator.isValid()) {
				long currentHeight = bytesToLong(iterator.key());
				if (currentHeight > toHeight)
					break;
				Hash hash = Hash.wrap(iterator.value());
				heightCache.put(currentHeight, hash);
				hashes.add(hash);
				iterator.next();
			}
		}

		if (hashes.isEmpty())
			return new ArrayList<>();

		// Step 2: Batch fetch headers only
		return getStoredBlockHeadersByHashes(hashes);
	}

	// ========================
	// Helper methods
	// ========================

	private static byte[] longToBytes(long val) {
		return ByteBuffer.allocate(8).putLong(val).array();
	}

	private static long bytesToLong(byte[] bytes) {
		return ByteBuffer.wrap(bytes).getLong();
	}

	// ========================
	// Write operations
	// ========================

	/**
	 * Saves StoredBlock (Block + Metadata) and indexes transactions.
	 */
	public void saveBlockDataToBatch(WriteBatch batch, StoredBlock storedBlock) throws RocksDBException {
		Block block = storedBlock.getBlock();
		byte[] hashBytes = storedBlock.getHash().toArray();

		// 1. Save StoredBlock
		Bytes encoded = StoredBlockEncoder.INSTANCE.encode(storedBlock, StoredBlockVersion.V1);
		batch.put(cf.blocks(), hashBytes, encoded.toArray());

		// Put the block into cache immediately so it's available for serving
		// (instead of just invalidating, which would require a DB read on next access)
		StoredBlock cachedBlock = storedBlock.toBuilder().size(encoded.size()).build();
		scheduleInvalidation(() -> blockCache.put(storedBlock.getHash(), cachedBlock));
		scheduleInvalidation(() -> headerCache.invalidate(storedBlock.getHash())); // Invalidate partial cache

		// Update latestBlockCache if we are updating the current tip
		StoredBlock currentLatest = latestBlockCache.get();
		if (currentLatest != null && currentLatest.getHash().equals(storedBlock.getHash())) {
			scheduleInvalidation(() -> latestBlockCache.set(cachedBlock));
		}

		// 2. Index Transactions (TxHash -> BlockHash)
		List<Hash> txHashes = new ArrayList<>(block.getTxs().size());
		for (Tx tx : block.getTxs()) {
			batch.put(cf.txIndex(), tx.getHash().toArray(), hashBytes);
			txHashes.add(tx.getHash());
		}
		scheduleInvalidation(() -> txCache.invalidateAll(txHashes));
	}

	public void connectBlockIndexToBatch(WriteBatch batch, StoredBlock storedBlock) throws RocksDBException {
		byte[] hashBytes = storedBlock.getHash().toArray();
		batch.put(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, hashBytes);
		batch.put(cf.hashByHeight(), Bytes.ofUnsignedLong(storedBlock.getHeight()).toArray(), hashBytes);
		// Put height->hash mapping into cache immediately
		scheduleInvalidation(() -> heightCache.put(storedBlock.getHeight(), storedBlock.getHash()));
		scheduleInvalidation(() -> latestBlockCache.set(storedBlock));
	}

	public void addBlockToBatch(WriteBatch batch, StoredBlock storedBlock) throws RocksDBException {
		saveBlockDataToBatch(batch, storedBlock);
		connectBlockIndexToBatch(batch, storedBlock);
	}

	public void addDisconnectBlockIndexToBatch(WriteBatch batch, StoredBlock blockToDisconnect, StoredBlock newTip)
			throws RocksDBException {
		batch.put(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, newTip.getHash().toArray());
		batch.delete(cf.hashByHeight(), Bytes.ofUnsignedLong(blockToDisconnect.getHeight()).toArray());
		scheduleInvalidation(() -> heightCache.invalidate(blockToDisconnect.getHeight()));
		scheduleInvalidation(() -> latestBlockCache.set(newTip));
	}

	public void forceDisconnectBlockIndex(WriteBatch batch, long height, Hash newTipHash) throws RocksDBException {
		batch.put(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, newTipHash.toArray());
		batch.delete(cf.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
		scheduleInvalidation(() -> heightCache.invalidate(height));
		scheduleInvalidation(() -> latestBlockCache.set(null)); // We don't have the full new tip block here easily
	}

	public void removeBlockIndexFromBatch(WriteBatch batch, long height) throws RocksDBException {
		batch.delete(cf.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
		scheduleInvalidation(() -> heightCache.invalidate(height));
	}

	public void executeAtomicBatch(RocksDBRepository.BatchOperation operation) {
		postCommitActions.set(new ArrayList<>());
		try {
			repository.executeAtomicBatch(operation);
			postCommitActions.get().forEach(Runnable::run);
		} finally {
			postCommitActions.remove();
		}
	}
}