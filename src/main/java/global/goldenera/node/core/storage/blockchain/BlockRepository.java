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

import org.apache.tuweni.bytes.Bytes;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.springframework.stereotype.Repository;

import com.github.benmanes.caffeine.cache.Cache;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
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

@Slf4j
@Repository
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockRepository {

	@Getter
	RocksDBRepository repository;
	RocksDbColumnFamilies cf;
	Cache<Hash, StoredBlock> blockCache;
	Cache<Hash, BlockHeader> headerCache;
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

	public Optional<Block> getBlockByHash(Hash hash) {
		return getStoredBlockByHash(hash).map(StoredBlock::getBlock);
	}

	public Block getBlockByHashOrThrow(Hash hash) {
		return getBlockByHash(hash)
				.orElseThrow(() -> new GENotFoundException("Block not found: " + hash));
	}

	/**
	 * Batch get multiple blocks by their hashes using multiGet.
	 * Much more efficient than calling getBlockByHash() in a loop.
	 */
	public List<Block> getBlocksByHashes(List<Hash> hashes) {
		if (hashes.isEmpty())
			return new ArrayList<>();

		List<Block> blocks = new ArrayList<>(hashes.size());
		List<Integer> missingIndices = new ArrayList<>();
		List<byte[]> missingKeys = new ArrayList<>();

		// Check cache first
		for (int i = 0; i < hashes.size(); i++) {
			Hash hash = hashes.get(i);
			StoredBlock cached = blockCache.getIfPresent(hash);
			if (cached != null) {
				blocks.add(cached.getBlock());
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
				for (int j = 0; j < values.size(); j++) {
					byte[] data = values.get(j);
					if (data != null) {
						StoredBlock storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data));
						if (!storedBlock.isPartial()) {
							blockCache.put(hashes.get(missingIndices.get(j)), storedBlock);
						}
						blocks.set(missingIndices.get(j), storedBlock.getBlock());
					}
				}
			} catch (RocksDBException e) {
				log.error("MultiGet failed in getBlocksByHashes", e);
			}
		}

		// Remove nulls
		blocks.removeIf(b -> b == null);
		return blocks;
	}

	public Optional<Block> getCanonicalBlockByHash(Hash hash) {
		return getStoredBlockByHash(hash).flatMap(storedBlock -> {
			Optional<Hash> canonicalHash = getBlockHashByHeight(storedBlock.getBlock().getHeight());
			if (canonicalHash.isPresent() && canonicalHash.get().equals(hash)) {
				return Optional.of(storedBlock.getBlock());
			}
			return Optional.empty();
		});
	}

	/**
	 * Optimized version that only loads header - much faster for
	 * findCommonAncestor.
	 */
	public Optional<BlockHeader> getCanonicalBlockHeaderByHash(Hash hash) {
		return getBlockHeaderByHash(hash).flatMap(header -> {
			Optional<Hash> canonicalHash = getBlockHashByHeight(header.getHeight());
			if (canonicalHash.isPresent() && canonicalHash.get().equals(hash)) {
				return Optional.of(header);
			}
			return Optional.empty();
		});
	}

	public Optional<StoredBlock> getCanonicalStoredBlockByHash(Hash hash) {
		return getStoredBlockByHash(hash).flatMap(storedBlock -> {
			Optional<Hash> canonicalHash = getBlockHashByHeight(storedBlock.getBlock().getHeight());
			if (canonicalHash.isPresent() && canonicalHash.get().equals(hash)) {
				return Optional.of(storedBlock);
			}
			return Optional.empty();
		});
	}

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

	public Optional<Block> getBlockByHeight(long height) {
		return getBlockHashByHeight(height).flatMap(this::getBlockByHash);
	}

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

	public Optional<Block> getLatestBlock() {
		return getLatestStoredBlock().map(StoredBlock::getBlock);
	}

	public Block getLatestBlockOrThrow() {
		return getLatestStoredBlock().map(StoredBlock::getBlock)
				.orElseThrow(() -> new GENotFoundException("No latest block found"));
	}

	public StoredBlock getLatestStoredBlockOrThrow() {
		return getLatestStoredBlock()
				.orElseThrow(() -> new GENotFoundException("No latest block found"));
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

	public List<Tx> getTxsByBlockHash(Hash blockHash) {
		return getBlockByHashOrThrow(blockHash).getTxs();
	}

	public Optional<Tx> getTransactionByHash(Hash txHash) {
		Tx cachedTx = txCache.getIfPresent(txHash);
		if (cachedTx != null) {
			return Optional.of(cachedTx);
		}
		try {
			byte[] blockHashBytes = repository.get(cf.txIndex(), txHash.toArray());
			if (blockHashBytes == null)
				return Optional.empty();

			Hash blockHash = Hash.wrap(blockHashBytes);

			Optional<StoredBlock> storedBlockOpt = getCanonicalStoredBlockByHash(blockHash);
			if (storedBlockOpt.isEmpty())
				return Optional.empty();

			StoredBlock storedBlock = storedBlockOpt.get();
			Block block = storedBlock.getBlock();

			List<Tx> txs = block.getTxs();
			for (int i = 0; i < txs.size(); i++) {
				Tx tx = txs.get(i);
				if (tx.getHash().equals(txHash)) {
					txCache.put(txHash, tx);
					return Optional.of(tx);
				}
			}
			return Optional.empty();
		} catch (RocksDBException e) {
			throw new RuntimeException("Failed to read TxIndex " + txHash, e);
		}
	}

	public List<Block> findByHeightRange(long fromHeight, long toHeight) {
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

		// Step 2: Check cache first, then batch fetch missing from DB
		List<Block> blocks = new ArrayList<>(hashes.size());
		List<Integer> missingIndices = new ArrayList<>();
		List<byte[]> missingKeys = new ArrayList<>();

		for (int i = 0; i < hashes.size(); i++) {
			Hash hash = hashes.get(i);
			StoredBlock cached = blockCache.getIfPresent(hash);
			if (cached != null) {
				blocks.add(cached.getBlock());
			} else {
				blocks.add(null); // Placeholder
				missingIndices.add(i);
				missingKeys.add(hash.toArray());
			}
		}

		// Step 3: MultiGet for cache misses
		if (!missingKeys.isEmpty()) {
			try {
				List<byte[]> values = repository.multiGet(cf.blocks(), missingKeys);
				for (int j = 0; j < values.size(); j++) {
					byte[] data = values.get(j);
					if (data != null) {
						StoredBlock storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data));
						if (!storedBlock.isPartial()) {
							blockCache.put(hashes.get(missingIndices.get(j)), storedBlock);
						}
						blocks.set(missingIndices.get(j), storedBlock.getBlock());
					}
				}
			} catch (RocksDBException e) {
				log.error("MultiGet failed in findByHeightRange", e);
			}
		}

		// Remove nulls (blocks that weren't found)
		blocks.removeIf(b -> b == null);
		return blocks;
	}

	public Optional<BlockHeader> getBlockHeaderByHash(Hash hash) {
		BlockHeader cached = headerCache.getIfPresent(hash);
		if (cached != null)
			return Optional.of(cached);
		try {
			byte[] data = repository.get(cf.blocks(), hash.toArray());
			if (data == null)
				return Optional.empty();

			StoredBlock storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data), true);
			BlockHeader header = storedBlock.getBlock().getHeader();
			headerCache.put(hash, header);
			return Optional.of(header);
		} catch (Exception e) {
			log.error("Failed to read BlockHeader " + hash, e);
			return Optional.empty();
		}
	}

	public List<BlockHeader> findHeadersByHeightRange(long fromHeight, long toHeight) {
		if (fromHeight > toHeight)
			return new ArrayList<>();

		// Step 1: Collect all hashes using iterator (fast - just reading index)
		List<Hash> hashes = new ArrayList<>();
		List<Long> heights = new ArrayList<>();
		try (RocksIterator iterator = repository.newIterator(cf.hashByHeight())) {
			iterator.seek(longToBytes(fromHeight));
			while (iterator.isValid()) {
				long currentHeight = bytesToLong(iterator.key());
				if (currentHeight > toHeight)
					break;
				Hash hash = Hash.wrap(iterator.value());
				heightCache.put(currentHeight, hash);
				hashes.add(hash);
				heights.add(currentHeight);
				iterator.next();
			}
		}

		if (hashes.isEmpty())
			return new ArrayList<>();

		// Step 2: Check header cache first, then batch fetch missing from DB
		List<BlockHeader> headers = new ArrayList<>(hashes.size());
		List<Integer> missingIndices = new ArrayList<>();
		List<byte[]> missingKeys = new ArrayList<>();

		for (int i = 0; i < hashes.size(); i++) {
			Hash hash = hashes.get(i);
			BlockHeader cached = headerCache.getIfPresent(hash);
			if (cached != null) {
				headers.add(cached);
			} else {
				headers.add(null); // Placeholder
				missingIndices.add(i);
				missingKeys.add(hash.toArray());
			}
		}

		// Step 3: MultiGet for cache misses (using withoutBody=true for efficiency)
		if (!missingKeys.isEmpty()) {
			try {
				List<byte[]> values = repository.multiGet(cf.blocks(), missingKeys);
				for (int j = 0; j < values.size(); j++) {
					byte[] data = values.get(j);
					if (data != null) {
						StoredBlock storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data), true);
						BlockHeader header = storedBlock.getBlock().getHeader();
						headerCache.put(hashes.get(missingIndices.get(j)), header);
						headers.set(missingIndices.get(j), header);
					}
				}
			} catch (RocksDBException e) {
				log.error("MultiGet failed in findHeadersByHeightRange", e);
			}
		}

		// Remove nulls (headers that weren't found)
		headers.removeIf(h -> h == null);
		return headers;
	}

	private static byte[] longToBytes(long val) {
		return ByteBuffer.allocate(8).putLong(val).array();
	}

	private static long bytesToLong(byte[] bytes) {
		return ByteBuffer.wrap(bytes).getLong();
	}

	/**
	 * Saves StoredBlock (Block + Metadata) and indexes transactions.
	 */
	public void saveBlockDataToBatch(WriteBatch batch, StoredBlock storedBlock) throws RocksDBException {
		Block block = storedBlock.getBlock();
		byte[] hashBytes = block.getHash().toArray();

		// 1. Save StoredBlock
		Bytes encoded = StoredBlockEncoder.INSTANCE.encode(storedBlock, StoredBlockVersion.V1);
		batch.put(cf.blocks(), hashBytes, encoded.toArray());
		scheduleInvalidation(() -> blockCache.invalidate(block.getHash()));
		scheduleInvalidation(() -> headerCache.invalidate(block.getHash()));

		// Update latestBlockCache if we are updating the current tip
		StoredBlock currentLatest = latestBlockCache.get();
		if (currentLatest != null && currentLatest.getHash().equals(block.getHash())) {
			scheduleInvalidation(() -> latestBlockCache.set(storedBlock));
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
		Block block = storedBlock.getBlock();
		byte[] hashBytes = block.getHash().toArray();
		batch.put(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, hashBytes);
		batch.put(cf.hashByHeight(), Bytes.ofUnsignedLong(block.getHeight()).toArray(), hashBytes);
		scheduleInvalidation(() -> heightCache.invalidate(block.getHeight()));
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