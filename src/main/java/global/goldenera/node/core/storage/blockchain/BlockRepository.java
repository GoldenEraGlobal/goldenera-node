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

@Slf4j
@Repository
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockRepository {

	@Getter
	RocksDBRepository repository;
	RocksDbColumnFamilies cf;
	Cache<Hash, StoredBlock> blockCache;

	public Optional<StoredBlock> getStoredBlockByHash(Hash hash) {
		StoredBlock cached = blockCache.getIfPresent(hash);
		if (cached != null)
			return Optional.of(cached);
		try {
			byte[] data = repository.get(cf.blocks(), hash.toArray());
			if (data == null)
				return Optional.empty();

			StoredBlock storedBlock = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(data));
			blockCache.put(hash, storedBlock);
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

	public Optional<Hash> getBlockHashByHeight(long height) {
		try {
			byte[] hashBytes = repository.get(cf.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
			return (hashBytes == null) ? Optional.empty() : Optional.of(Hash.wrap(hashBytes));
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
		try {
			byte[] data = repository.get(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH);
			return (data == null) ? Optional.empty() : Optional.of(Hash.wrap(data));
		} catch (RocksDBException e) {
			throw new RuntimeException("Failed to read LATEST_BLOCK_HASH", e);
		}
	}

	public Optional<Block> getLatestBlock() {
		return getLatestBlockHash().flatMap(this::getBlockByHash);
	}

	public Block getLatestBlockOrThrow() {
		return getLatestBlockHash().flatMap(this::getBlockByHash)
				.orElseThrow(() -> new GENotFoundException("No latest block found"));
	}

	public StoredBlock getLatestStoredBlockOrThrow() {
		return getLatestBlockHash().flatMap(this::getStoredBlockByHash)
				.orElseThrow(() -> new GENotFoundException("No latest block found"));
	}

	public Optional<StoredBlock> getLatestStoredBlock() {
		return getLatestBlockHash().flatMap(this::getStoredBlockByHash);
	}

	public List<Tx> getTxsByBlockHash(Hash blockHash) {
		return getBlockByHashOrThrow(blockHash).getTxs();
	}

	public Optional<Tx> getTransactionByHash(Hash txHash) {
		try {
			byte[] blockHashBytes = repository.get(cf.txIndex(), txHash.toArray());
			if (blockHashBytes == null)
				return Optional.empty();

			Hash blockHash = Hash.wrap(blockHashBytes);

			Optional<StoredBlock> storedBlockOpt = getStoredBlockByHash(blockHash);
			if (storedBlockOpt.isEmpty())
				return Optional.empty();

			StoredBlock storedBlock = storedBlockOpt.get();
			Block block = storedBlock.getBlock();
			Optional<Hash> mainChainHashAtHeight = getBlockHashByHeight(block.getHeight());
			if (mainChainHashAtHeight.isEmpty() || !mainChainHashAtHeight.get().equals(blockHash)) {
				return Optional.empty();
			}

			List<Tx> txs = block.getTxs();
			for (int i = 0; i < txs.size(); i++) {
				Tx tx = txs.get(i);
				if (tx.getHash().equals(txHash)) {
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
		int estimatedSize = (int) Math.min(toHeight - fromHeight + 1, 1000);
		List<Block> blocks = new ArrayList<>(estimatedSize);
		try (RocksIterator iterator = repository.newIterator(cf.hashByHeight())) {
			iterator.seek(longToBytes(fromHeight));
			while (iterator.isValid()) {
				long currentHeight = bytesToLong(iterator.key());
				if (currentHeight > toHeight)
					break;

				getBlockByHash(Hash.wrap(iterator.value())).ifPresent(blocks::add);
				iterator.next();
			}
		}
		return blocks;
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
		blockCache.invalidate(block.getHash());

		// 2. Index Transactions (TxHash -> BlockHash)
		for (Tx tx : block.getTxs()) {
			batch.put(cf.txIndex(), tx.getHash().toArray(), hashBytes);
		}
	}

	public void connectBlockIndexToBatch(WriteBatch batch, StoredBlock storedBlock) throws RocksDBException {
		Block block = storedBlock.getBlock();
		byte[] hashBytes = block.getHash().toArray();
		batch.put(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, hashBytes);
		batch.put(cf.hashByHeight(), Bytes.ofUnsignedLong(block.getHeight()).toArray(), hashBytes);
	}

	public void addBlockToBatch(WriteBatch batch, StoredBlock storedBlock) throws RocksDBException {
		saveBlockDataToBatch(batch, storedBlock);
		connectBlockIndexToBatch(batch, storedBlock);
	}

	public void addDisconnectBlockIndexToBatch(WriteBatch batch, StoredBlock blockToDisconnect, StoredBlock newTip)
			throws RocksDBException {
		batch.put(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, newTip.getHash().toArray());
		batch.delete(cf.hashByHeight(), Bytes.ofUnsignedLong(blockToDisconnect.getHeight()).toArray());
	}

	public void forceDisconnectBlockIndex(WriteBatch batch, long height, Hash newTipHash) throws RocksDBException {
		batch.put(cf.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, newTipHash.toArray());
		batch.delete(cf.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
	}

	public void removeBlockIndexFromBatch(WriteBatch batch, long height) throws RocksDBException {
		batch.delete(cf.hashByHeight(), Bytes.ofUnsignedLong(height).toArray());
	}

	public void executeAtomicBatch(RocksDBRepository.BatchOperation operation) {
		repository.executeAtomicBatch(operation);
	}
}