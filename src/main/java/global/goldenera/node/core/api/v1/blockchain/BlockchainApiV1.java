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
package global.goldenera.node.core.api.v1.blockchain;

import static lombok.AccessLevel.PRIVATE;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.utils.PaginationUtil;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@RequestMapping("/api/core/v1/blockchain")
@RestController
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockchainApiV1 {

    ChainQuery chainQuery;

    @GetMapping("block-header/latest")
    public ResponseEntity<BlockHeader> getLatestBlockHeader() {
        return ResponseEntity.ok(chainQuery.getLatestBlockOrThrow().getHeader());
    }

    @GetMapping("block-header/by-height/{height}")
    public ResponseEntity<BlockHeader> getBlockHeaderByHeight(@PathVariable Long height) {
        return ResponseEntity.ok(chainQuery.getBlockByHeight(height).map(Block::getHeader)
                .orElseThrow(() -> new GENotFoundException("Block not found")));
    }

    @GetMapping("block-header/by-hash/{hash}")
    public ResponseEntity<BlockHeader> getBlockHeaderByHash(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getBlockByHashOrThrow(hash).getHeader());
    }

    @GetMapping("block-header/by-range")
    public ResponseEntity<List<BlockHeader>> getBlockHeaderByRange(@RequestParam long fromHeight,
            @RequestParam long toHeight) {
        PaginationUtil.validateRangeRequest(fromHeight, toHeight);
        return ResponseEntity.ok(chainQuery.findByHeightRange(fromHeight, toHeight).stream()
                .map(Block::getHeader).collect(Collectors.toList()));
    }

    @GetMapping("block/latest")
    public ResponseEntity<Block> getLatestBlock() {
        return ResponseEntity.ok(chainQuery.getLatestBlockOrThrow());
    }

    @GetMapping("block/by-height/{height}")
    public ResponseEntity<Block> getBlockByHeight(@PathVariable Long height) {
        return ResponseEntity
                .ok(chainQuery.getBlockByHeight(height).orElseThrow(() -> new GENotFoundException("Block not found")));
    }

    @GetMapping("block/by-hash/{hash}")
    public ResponseEntity<Block> getBlockByHash(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getBlockByHashOrThrow(hash));
    }

    @GetMapping("block/by-hash/{hash}/txs")
    public ResponseEntity<List<Tx>> getBlockTxsByHash(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getBlockByHashOrThrow(hash).getTxs());
    }

    @GetMapping("block/by-height/{height}/txs")
    public ResponseEntity<List<Tx>> getBlockTxsByHeight(@PathVariable Long height) {
        return ResponseEntity.ok(chainQuery.getBlockByHeight(height).map(Block::getTxs)
                .orElseThrow(() -> new GENotFoundException("Block not found")));
    }

    @GetMapping("tx/by-hash/{hash}")
    public ResponseEntity<Tx> getTransactionByHash(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getTransactionByHash(hash)
                .orElseThrow(() -> new GENotFoundException("Transaction not found")));
    }
}
