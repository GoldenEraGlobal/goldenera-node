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

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
@NamedInterface("chain-query-service")
public class ChainQuery {

    BlockRepository blockRepository;

    public Optional<Block> getBlockByHash(Hash hash) {
        return blockRepository.getBlockByHash(hash);
    }

    public Block getBlockByHashOrThrow(Hash hash) {
        return blockRepository.getBlockByHashOrThrow(hash);
    }

    public Optional<StoredBlock> getStoredBlockByHash(Hash hash) {
        return blockRepository.getStoredBlockByHash(hash);
    }

    public StoredBlock getStoredBlockByHashOrThrow(Hash hash) {
        return blockRepository.getStoredBlockByHashOrThrow(hash);
    }

    public Optional<Hash> getBlockHashByHeight(long height) {
        return blockRepository.getBlockHashByHeight(height);
    }

    public Optional<Block> getBlockByHeight(long height) {
        return blockRepository.getBlockByHeight(height);
    }

    public Optional<StoredBlock> getStoredBlockByHeight(long height) {
        return blockRepository.getStoredBlockByHeight(height);
    }

    public Optional<Hash> getLatestBlockHash() {
        return blockRepository.getLatestBlockHash();
    }

    public Block getLatestBlockOrThrow() {
        return blockRepository.getLatestBlockOrThrow();
    }

    public Optional<Block> getLatestBlock() {
        return blockRepository.getLatestBlock();
    }

    public Optional<StoredBlock> getLatestStoredBlock() {
        return blockRepository.getLatestStoredBlock();
    }

    public StoredBlock getLatestStoredBlockOrThrow() {
        return blockRepository.getLatestStoredBlockOrThrow();
    }

    public List<Tx> getTxsByBlockHash(Hash blockHash) {
        return blockRepository.getTxsByBlockHash(blockHash);
    }

    public Optional<Tx> getTransactionByHash(Hash txHash) {
        return blockRepository.getTransactionByHash(txHash);
    }

    public List<Block> findByHeightRange(long fromHeight, long toHeight) {
        return blockRepository.findByHeightRange(fromHeight, toHeight);
    }

    public List<Block> findChainFrom(Hash commonAncestorHash, Hash currentBestBlockHash) {
        List<Block> chain = new ArrayList<>();
        Hash currentHash = currentBestBlockHash;

        while (currentHash != null && !currentHash.equals(commonAncestorHash)) {
            final Hash hashForThisIteration = currentHash;
            Block currentBlock = blockRepository.getBlockByHash(hashForThisIteration)
                    .orElseThrow(() -> new GENotFoundException("Chain break at: " + hashForThisIteration));

            chain.add(currentBlock);
            if (currentBlock.getHeight() == 0) {
                if (!currentBlock.getHash().equals(commonAncestorHash)) {
                    throw new GEFailedException("Reached genesis without finding ancestor " + commonAncestorHash);
                }
                break;
            }
            currentHash = currentBlock.getHeader().getPreviousHash();
        }
        Collections.reverse(chain);
        return chain;
    }

    public LinkedHashSet<Hash> getLocatorHashes() {
        Block bestBlock = blockRepository.getLatestBlockOrThrow();
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

    public Optional<Block> findCommonAncestor(LinkedHashSet<Hash> locatorHashes) {
        for (Hash hash : locatorHashes) {
            Optional<Block> b = getCanonicalBlockByHash(hash);
            if (b.isPresent()) {
                return b;
            }
        }
        return Optional.empty();
    }

    public Optional<Block> getCanonicalBlockByHash(Hash hash) {
        return blockRepository.getCanonicalBlockByHash(hash);
    }

    public Optional<StoredBlock> getCanonicalStoredBlockByHash(Hash hash) {
        return blockRepository.getCanonicalStoredBlockByHash(hash);
    }

}