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
package global.goldenera.node.core.api.v1.blockchain.mappers;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainTxDtoV1;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

/**
 * Maps transactions from StoredBlock to BlockchainTxDtoV1.
 * Uses pre-computed metadata from TxIndex to avoid expensive operations.
 */
@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BlockchainTxMapper {

    /**
     * Maps a single transaction from StoredBlock using pre-computed metadata.
     */
    public BlockchainTxDtoV1 map(@NonNull StoredBlock storedBlock, @NonNull Hash txHash) {
        Tx tx = storedBlock.getTransactionByHash(txHash);
        if (tx == null) {
            throw new IllegalArgumentException("Transaction not found in block: " + txHash);
        }

        Integer idx = storedBlock.getTransactionIndex().get(txHash);
        int index = idx != null ? idx : -1;
        int size = storedBlock.getTransactionSizeByIndex(index);
        Address sender = storedBlock.getTransactionSenderByIndex(index);

        // Fallback if index data not available
        if (size < 0)
            size = tx.getSize();
        if (sender == null)
            sender = tx.getSender();

        return new BlockchainTxDtoV1(tx,
                new BlockchainTxDtoV1.BlockchainTxMetadataDtoV1(txHash, size, index, sender));
    }

    /**
     * Maps a transaction at specific index from StoredBlock.
     */
    public BlockchainTxDtoV1 mapByIndex(@NonNull StoredBlock storedBlock, int index) {
        List<Tx> txs = storedBlock.getBlock().getTxs();
        if (index < 0 || index >= txs.size()) {
            throw new IllegalArgumentException("Transaction index out of bounds: " + index);
        }

        Tx tx = txs.get(index);
        Hash hash = storedBlock.getTransactionHashByIndex(index);
        int size = storedBlock.getTransactionSizeByIndex(index);
        Address sender = storedBlock.getTransactionSenderByIndex(index);

        // Fallback if index data not available
        if (hash == null)
            hash = tx.getHash();
        if (size < 0)
            size = tx.getSize();
        if (sender == null)
            sender = tx.getSender();

        return new BlockchainTxDtoV1(tx,
                new BlockchainTxDtoV1.BlockchainTxMetadataDtoV1(hash, size, index, sender));
    }

    /**
     * Maps a range of transactions from StoredBlock.
     */
    public List<BlockchainTxDtoV1> mapRange(@NonNull StoredBlock storedBlock, int fromIndex, int toIndex) {
        List<Tx> txs = storedBlock.getBlock().getTxs();
        int endIndex = Math.min(toIndex, txs.size());

        List<BlockchainTxDtoV1> result = new ArrayList<>(endIndex - fromIndex);
        for (int i = fromIndex; i < endIndex; i++) {
            result.add(mapByIndex(storedBlock, i));
        }
        return result;
    }

    /**
     * Maps all transactions from StoredBlock.
     */
    public List<BlockchainTxDtoV1> mapAll(@NonNull StoredBlock storedBlock) {
        List<Tx> txs = storedBlock.getBlock().getTxs();
        return mapRange(storedBlock, 0, txs.size());
    }
}
