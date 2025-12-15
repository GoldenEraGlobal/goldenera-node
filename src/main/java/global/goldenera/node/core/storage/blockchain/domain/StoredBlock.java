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
package global.goldenera.node.core.storage.blockchain.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

/**
 * Database wrapper for a Block.
 * Contains the canonical Block (consensus data) and extra metadata
 * (node-specific info).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StoredBlock {

	// Raw consensus data
	Block block;

	// Metadata
	BigInteger cumulativeDifficulty;
	Instant receivedAt;
	Address receivedFrom;
	ConnectedSource connectedSource;
	boolean isPartial;

	// Pre-computed block metadata (stored in DB)
	Hash hash;
	int blockSize;
	Address identity;
	int encodedSize; // Size of serialized StoredBlock in bytes

	// Pre-computed transaction metadata (stored in DB)
	TxIndex txIndex;

	/**
	 * "Invisible" state change events that occurred in this block.
	 * These are events like TOKEN_MINTED, AUTHORITY_ADDED, BLOCK_REWARD etc.
	 * that are NOT explicit transactions but result from BIP execution or consensus
	 * rules.
	 * 
	 * <p>
	 * Useful for wallets/explorers to track balance changes that don't have a tx
	 * sender.
	 * </p>
	 */
	List<BlockEvent> events;

	/**
	 * Pre-computed transaction index data.
	 * Stored in DB to avoid expensive recalculation (keccak, RLP encoding, ECDSA
	 * recovery).
	 */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
	public static class TxIndex {
		// Stored in DB (position = index)
		Hash[] hashes;
		int[] sizes;
		Address[] senders;
		// Derived from hashes during load (NOT stored in DB)
		Map<Hash, Integer> hashToIndex;
		Map<Integer, Hash> indexToHash;

		public static TxIndex empty() {
			return new TxIndex(new Hash[0], new int[0], new Address[0], Collections.emptyMap(), Collections.emptyMap());
		}

		/**
		 * Computes TxIndex from block transactions.
		 * Calls getHash(), getSize(), getSender() ONCE per transaction.
		 */
		public static TxIndex compute(Block block) {
			if (block == null || block.getTxs() == null || block.getTxs().isEmpty()) {
				return empty();
			}
			List<Tx> txs = block.getTxs();
			int count = txs.size();
			Hash[] hashes = new Hash[count];
			int[] sizes = new int[count];
			Address[] senders = new Address[count];
			Map<Hash, Integer> hashToIdx = new HashMap<>(count);
			Map<Integer, Hash> idxToHash = new HashMap<>(count);

			for (int i = 0; i < count; i++) {
				Tx tx = txs.get(i);
				Hash h = tx.getHash();
				hashes[i] = h;
				sizes[i] = tx.getSize();
				senders[i] = tx.getSender();
				hashToIdx.put(h, i);
				idxToHash.put(i, h);
			}
			return new TxIndex(hashes, sizes, senders, hashToIdx, idxToHash);
		}

		/**
		 * Creates TxIndex from stored data (during decode).
		 * Derives hashToIndex and indexToHash maps from hashes array.
		 */
		public static TxIndex fromStored(Hash[] hashes, int[] sizes, Address[] senders) {
			if (hashes == null || hashes.length == 0) {
				return empty();
			}
			int count = hashes.length;
			Map<Hash, Integer> hashToIdx = new HashMap<>(count);
			Map<Integer, Hash> idxToHash = new HashMap<>(count);
			for (int i = 0; i < count; i++) {
				hashToIdx.put(hashes[i], i);
				idxToHash.put(i, hashes[i]);
			}
			return new TxIndex(hashes, sizes, senders, hashToIdx, idxToHash);
		}

		public Hash getHashByIndex(int index) {
			if (hashes == null || index < 0 || index >= hashes.length)
				return null;
			return hashes[index];
		}

		public int getSizeByIndex(int index) {
			if (sizes == null || index < 0 || index >= sizes.length)
				return -1;
			return sizes[index];
		}

		public Address getSenderByIndex(int index) {
			if (senders == null || index < 0 || index >= senders.length)
				return null;
			return senders[index];
		}

		public Integer getIndexByHash(Hash hash) {
			if (hashToIndex == null)
				return null;
			return hashToIndex.get(hash);
		}

		public int count() {
			return hashes != null ? hashes.length : 0;
		}
	}

	// ========================
	// Convenience getters
	// ========================

	public long getHeight() {
		return block.getHeight();
	}

	public int getBlockSize() {
		return blockSize;
	}

	public int getTxCount() {
		return txIndex != null ? txIndex.count() : (block.getTxs() != null ? block.getTxs().size() : 0);
	}

	// TxIndex delegate methods for backward compatibility
	public Hash getTransactionHashByIndex(int index) {
		return txIndex != null ? txIndex.getHashByIndex(index) : null;
	}

	public int getTransactionSizeByIndex(int index) {
		return txIndex != null ? txIndex.getSizeByIndex(index) : -1;
	}

	public Address getTransactionSenderByIndex(int index) {
		return txIndex != null ? txIndex.getSenderByIndex(index) : null;
	}

	public Tx getTransactionByHash(Hash txHash) {
		if (isPartial || txIndex == null)
			return null;
		Integer idx = txIndex.getIndexByHash(txHash);
		if (idx != null && block.getTxs() != null && idx >= 0 && idx < block.getTxs().size()) {
			return block.getTxs().get(idx);
		}
		return null;
	}

	// Raw access for serialization
	public Hash[] getTransactionHashes() {
		return txIndex != null ? txIndex.getHashes() : null;
	}

	public int[] getTransactionSizes() {
		return txIndex != null ? txIndex.getSizes() : null;
	}

	public Address[] getTransactionSenders() {
		return txIndex != null ? txIndex.getSenders() : null;
	}

	public Map<Hash, Integer> getTransactionIndex() {
		return txIndex != null ? txIndex.getHashToIndex() : null;
	}

	public Map<Integer, Hash> getReverseTransactionIndex() {
		return txIndex != null ? txIndex.getIndexToHash() : null;
	}

	// ========================
	// Builder
	// ========================

	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder()
				.block(this.block)
				.cumulativeDifficulty(this.cumulativeDifficulty)
				.receivedAt(this.receivedAt)
				.receivedFrom(this.receivedFrom)
				.connectedSource(this.connectedSource)
				.isPartial(this.isPartial)
				.hash(this.hash)
				.blockSize(this.blockSize)
				.encodedSize(this.encodedSize)
				.txIndex(this.txIndex)
				.events(this.events);
	}

	@FieldDefaults(level = AccessLevel.PRIVATE)
	public static class Builder {
		Block block;
		BigInteger cumulativeDifficulty;
		Instant receivedAt;
		Address receivedFrom;
		ConnectedSource connectedSource;
		boolean isPartial;
		Hash hash;
		int blockSize;
		int encodedSize;
		TxIndex txIndex;
		List<BlockEvent> events;
		Address identity;

		public Builder block(Block block) {
			this.block = block;
			return this;
		}

		public Builder cumulativeDifficulty(BigInteger val) {
			this.cumulativeDifficulty = val;
			return this;
		}

		public Builder receivedAt(Instant val) {
			this.receivedAt = val;
			return this;
		}

		public Builder receivedFrom(Address val) {
			this.receivedFrom = val;
			return this;
		}

		public Builder connectedSource(ConnectedSource val) {
			this.connectedSource = val;
			return this;
		}

		public Builder isPartial(boolean val) {
			this.isPartial = val;
			return this;
		}

		public Builder hash(Hash val) {
			this.hash = val;
			return this;
		}

		public Builder blockSize(int val) {
			this.blockSize = val;
			return this;
		}

		public Builder encodedSize(int val) {
			this.encodedSize = val;
			return this;
		}

		public Builder txIndex(TxIndex val) {
			this.txIndex = val;
			return this;
		}

		public Builder events(List<BlockEvent> val) {
			this.events = val;
			return this;
		}

		public Builder identity(Address val) {
			this.identity = val;
			return this;
		}

		/**
		 * Computes hash, blockSize, and txIndex from block data.
		 * Call this before build() when creating new StoredBlock from Block.
		 */
		public Builder computeIndexes() {
			if (block != null) {
				if (hash == null) {
					hash = block.getHash();
				}
				if (blockSize == 0) {
					blockSize = block.getSize();
				}
				if (txIndex == null) {
					txIndex = TxIndex.compute(block);
				}
			}
			return this;
		}

		public StoredBlock build() {
			return new StoredBlock(
					block,
					cumulativeDifficulty,
					receivedAt,
					receivedFrom,
					connectedSource,
					isPartial,
					hash,
					blockSize,
					identity,
					encodedSize,
					txIndex,
					events != null ? events : List.of());
		}
	}
}