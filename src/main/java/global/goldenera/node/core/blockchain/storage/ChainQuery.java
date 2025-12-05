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
package global.goldenera.node.core.blockchain.storage;

import static lombok.AccessLevel.PRIVATE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * High-level chain query service providing clean API for blockchain data.
 * 
 * IMPORTANT: This service returns StoredBlock objects, NOT Block or
 * BlockHeader.
 * This is intentional to prevent accidental hash recalculation:
 * - Block.getHash() and BlockHeader.getHash() RECALCULATE the hash (lazy,
 * expensive)
 * - StoredBlock.getHash() returns the PRE-COMPUTED hash stored in DB (fast)
 * 
 * Callers should use StoredBlock.getHash() for hash comparisons, and only call
 * storedBlock.getBlock() when they specifically need Block data (e.g., for
 * serialization).
 * 
 * Method naming convention:
 * - getStoredBlock*: Returns full StoredBlock with transactions
 * - getStoredBlockHeader*: Returns partial StoredBlock (header only, no tx
 * body) - more efficient
 */
@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
@NamedInterface("chain-query-service")
public class ChainQuery {

    BlockRepository blockRepository;

    // ========================
    // Full StoredBlock operations
    // ========================

    public Optional<StoredBlock> getStoredBlockByHash(Hash hash) {
        return blockRepository.getStoredBlockByHash(hash);
    }

    public StoredBlock getStoredBlockByHashOrThrow(Hash hash) {
        return blockRepository.getStoredBlockByHashOrThrow(hash);
    }

    public Optional<StoredBlock> getStoredBlockByHeight(long height) {
        return blockRepository.getStoredBlockByHeight(height);
    }

    public List<StoredBlock> getStoredBlocksByHashes(List<Hash> hashes) {
        return blockRepository.getStoredBlocksByHashes(hashes);
    }

    public List<StoredBlock> findStoredBlocksByHeightRange(long fromHeight, long toHeight) {
        return blockRepository.findStoredBlocksByHeightRange(fromHeight, toHeight);
    }

    // ========================
    // Partial StoredBlock (header only) operations - more efficient
    // ========================

    /**
     * Gets partial StoredBlock by hash (header only, no transactions).
     * Use storedBlock.getHash() for the pre-computed hash!
     */
    public Optional<StoredBlock> getStoredBlockHeaderByHash(Hash hash) {
        return blockRepository.getStoredBlockHeaderByHash(hash);
    }

    /**
     * Gets partial StoredBlock by height (header only, no transactions).
     * Use storedBlock.getHash() for the pre-computed hash!
     */
    public Optional<StoredBlock> getStoredBlockHeaderByHeight(long height) {
        return blockRepository.getStoredBlockHeaderByHeight(height);
    }

    /**
     * Finds partial StoredBlocks by height range (headers only, no transactions).
     * Optimized for serving header sync requests.
     * Use storedBlock.getHash() for the pre-computed hash!
     */
    public List<StoredBlock> findStoredBlockHeadersByHeightRange(long fromHeight, long toHeight) {
        return blockRepository.findStoredBlockHeadersByHeightRange(fromHeight, toHeight);
    }

    /**
     * Gets partial StoredBlocks by hashes (headers only, no transactions).
     * Use storedBlock.getHash() for the pre-computed hash!
     */
    public List<StoredBlock> getStoredBlockHeadersByHashes(List<Hash> hashes) {
        return blockRepository.getStoredBlockHeadersByHashes(hashes);
    }

    // ========================
    // Latest block operations
    // ========================

    public Optional<Hash> getLatestBlockHash() {
        return blockRepository.getLatestBlockHash();
    }

    public Optional<StoredBlock> getLatestStoredBlock() {
        return blockRepository.getLatestStoredBlock();
    }

    public StoredBlock getLatestStoredBlockOrThrow() {
        return blockRepository.getLatestStoredBlockOrThrow();
    }

    /**
     * Gets the latest block height. Uses cached StoredBlock.getHeight().
     */
    public Optional<Long> getLatestBlockHeight() {
        return blockRepository.getLatestBlockHeight();
    }

    // ========================
    // Height index operations
    // ========================

    public Optional<Hash> getBlockHashByHeight(long height) {
        return blockRepository.getBlockHashByHeight(height);
    }

    public boolean hasBlockData(Hash hash) {
        return blockRepository.hasBlockData(hash);
    }

    // ========================
    // Transaction operations
    // ========================

    public List<Tx> getTxsByBlockHash(Hash blockHash) {
        return blockRepository.getStoredBlockByHashOrThrow(blockHash).getBlock().getTxs();
    }

    /**
     * Gets transaction by hash from canonical chain.
     * Uses StoredBlock's transactionIndex for O(1) lookup within the block.
     */
    public Optional<Tx> getTransactionByHash(Hash txHash) {
        // Check cache first
        Optional<Tx> cached = blockRepository.getCachedTransaction(txHash);
        if (cached.isPresent()) {
            return cached;
        }

        return blockRepository.getTransactionBlockHash(txHash)
                .flatMap(blockHash -> getCanonicalStoredBlockByHash(blockHash))
                .flatMap(storedBlock -> {
                    Optional<Tx> txOpt = storedBlock.getTransactionByHash(txHash);
                    txOpt.ifPresent(tx -> blockRepository.cacheTransaction(txHash, tx));
                    return txOpt;
                });
    }

    /**
     * Gets the number of confirmations for a transaction.
     * Confirmations = (current chain height - tx block height) + 1
     * 
     * @param txHash
     *            the transaction hash
     * @return number of confirmations, or empty if tx not found in canonical chain
     */
    public Optional<Long> getTransactionConfirmations(Hash txHash) {
        return getTransactionBlockHeight(txHash)
                .flatMap(txBlockHeight -> blockRepository.getLatestBlockHeight()
                        .map(currentHeight -> currentHeight - txBlockHeight + 1));
    }

    /**
     * Gets the block height where a transaction is included in canonical chain.
     */
    public Optional<Long> getTransactionBlockHeight(Hash txHash) {
        return blockRepository.getTransactionBlockHash(txHash)
                .flatMap(blockHash -> {
                    // Use header-only loading for efficiency
                    return blockRepository.getStoredBlockHeaderByHash(blockHash)
                            .filter(storedBlock -> {
                                // Verify the block is still canonical using pre-computed hash
                                Optional<Hash> canonicalHash = blockRepository
                                        .getBlockHashByHeight(storedBlock.getHeight());
                                return canonicalHash.isPresent() && canonicalHash.get().equals(storedBlock.getHash());
                            })
                            .map(StoredBlock::getHeight);
                });
    }

    // ========================
    // Canonical chain operations
    // ========================

    /**
     * Gets canonical StoredBlock by hash - verifies the block is on the main chain.
     * Uses StoredBlock.getHash() for comparison (pre-computed, not recalculated).
     */
    public Optional<StoredBlock> getCanonicalStoredBlockByHash(Hash hash) {
        return blockRepository.getStoredBlockByHash(hash).flatMap(storedBlock -> {
            // Use pre-computed hash from StoredBlock
            Optional<Hash> canonicalHash = blockRepository.getBlockHashByHeight(storedBlock.getHeight());
            if (canonicalHash.isPresent() && canonicalHash.get().equals(storedBlock.getHash())) {
                return Optional.of(storedBlock);
            }
            return Optional.empty();
        });
    }

    /**
     * Gets canonical partial StoredBlock (header only) by hash.
     * Uses StoredBlock.getHash() for comparison (pre-computed, not recalculated).
     */
    public Optional<StoredBlock> getCanonicalStoredBlockHeaderByHash(Hash hash) {
        return blockRepository.getStoredBlockHeaderByHash(hash).flatMap(storedBlock -> {
            // Use pre-computed hash from StoredBlock
            Optional<Hash> canonicalHash = blockRepository.getBlockHashByHeight(storedBlock.getHeight());
            if (canonicalHash.isPresent() && canonicalHash.get().equals(storedBlock.getHash())) {
                return Optional.of(storedBlock);
            }
            return Optional.empty();
        });
    }

    // ========================
    // Chain traversal operations
    // ========================

    /**
     * Finds the chain of StoredBlocks from common ancestor to current best block.
     * Uses header-only loading for walking backwards, then batch fetches full
     * blocks.
     */
    public List<StoredBlock> findChainFrom(Hash commonAncestorHash, Hash currentBestBlockHash) {
        // First pass: collect all hashes we need (walking backwards using headers only)
        List<Hash> hashesToFetch = new ArrayList<>();
        Hash currentHash = currentBestBlockHash;

        while (currentHash != null && !currentHash.equals(commonAncestorHash)) {
            hashesToFetch.add(currentHash);

            // Get just the header to find parent hash (much faster than full block)
            Optional<StoredBlock> storedBlockOpt = blockRepository.getStoredBlockHeaderByHash(currentHash);
            if (storedBlockOpt.isEmpty()) {
                throw new GENotFoundException("Chain break at: " + currentHash);
            }

            StoredBlock storedBlock = storedBlockOpt.get();
            if (storedBlock.getHeight() == 0) {
                // Use pre-computed hash for comparison
                if (!storedBlock.getHash().equals(commonAncestorHash)) {
                    throw new GEFailedException("Reached genesis without finding ancestor " + commonAncestorHash);
                }
                break;
            }
            currentHash = storedBlock.getBlock().getHeader().getPreviousHash();
        }

        if (hashesToFetch.isEmpty()) {
            return new ArrayList<>();
        }

        // Second pass: batch fetch all full blocks using multiGet
        List<StoredBlock> chain = getStoredBlocksByHashes(hashesToFetch);

        // Reverse because we collected backwards
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Generates locator hashes for block sync protocol.
     * Uses StoredBlock.getHash() to avoid hash recalculation.
     */
    public LinkedHashSet<Hash> getLocatorHashes() {
        StoredBlock bestBlock = blockRepository.getLatestStoredBlockOrThrow();
        List<Long> heights = new ArrayList<>();
        long height = bestBlock.getHeight();
        long step = 1;
        int count = 0;

        while (height > 0) {
            heights.add(height);
            count++;
            if (count > 10)
                step *= 2;
            height = Math.max(0, height - step);
        }
        if (!heights.contains(0L))
            heights.add(0L);

        return heights.stream()
                .map(blockRepository::getBlockHashByHeight)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(blockRepository::hasBlockData)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Finds the common ancestor StoredBlock (header only) from locator hashes.
     * Uses header-only loading for efficiency.
     * Use StoredBlock.getHash() for the pre-computed hash!
     */
    public Optional<StoredBlock> findCommonAncestor(LinkedHashSet<Hash> locatorHashes) {
        for (Hash hash : locatorHashes) {
            // Check if it's canonical using header-only (fast)
            Optional<StoredBlock> storedBlock = getCanonicalStoredBlockHeaderByHash(hash);
            if (storedBlock.isPresent()) {
                return storedBlock;
            }
        }
        return Optional.empty();
    }
}