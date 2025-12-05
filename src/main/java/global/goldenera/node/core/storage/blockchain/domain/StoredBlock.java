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
import java.util.Optional;

import org.springframework.modulith.NamedInterface;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import lombok.Builder;
import lombok.Value;

/**
 * Database wrapper for a Block.
 * Contains the canonical Block (consensus data) and extra metadata
 * (node-specific info).
 */
@Value
@Builder(toBuilder = true)
@NamedInterface("stored-block")
public class StoredBlock {

	// Raw consensus data
	Block block;

	// Metadata
	BigInteger cumulativeDifficulty;
	Instant receivedAt;
	Address receivedFrom;
	ConnectedSource connectedSource;
	int size; // Size of StoredBlock object in bytes
	int blockSize; // Size of Block object in bytes
	boolean isPartial;

	// Optimized fields (persisted in DB to avoid recalculation)
	Hash hash;
	Map<Hash, Integer> transactionIndex;

	public Hash getHash() {
		return hash;
	}

	public Map<Hash, Integer> getTransactionIndex() {
		return transactionIndex;
	}

	public Optional<Tx> getTransactionByHash(Hash txHash) {
		if (isPartial || transactionIndex == null) {
			return Optional.empty();
		}

		Integer idx = transactionIndex.get(txHash);
		if (idx != null) {
			List<Tx> txs = block.getTxs();
			if (idx >= 0 && idx < txs.size()) {
				return Optional.of(txs.get(idx));
			}
		}

		return Optional.empty();
	}

	public static Map<Hash, Integer> computeTransactionIndex(Block block) {
		if (block.getTxs() == null || block.getTxs().isEmpty()) {
			return Collections.emptyMap();
		}
		Map<Hash, Integer> index = new HashMap<>(block.getTxs().size());
		List<Tx> txs = block.getTxs();
		for (int i = 0; i < txs.size(); i++) {
			index.put(txs.get(i).getHash(), i);
		}
		return index;
	}

	public long getHeight() {
		return block.getHeight();
	}

	public int getSize() {
		if (isPartial) {
			throw new IllegalStateException("Cannot get size of partial block");
		}
		return size;
	}

	public int getBlockSize() {
		return blockSize;
	}
}