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
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.api.v1.blockchain.dtos.AccountSummaryDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainBlockHeaderDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainTxDtoV1;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainBlockHeaderMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.consensus.state.AccountBalanceState;
import global.goldenera.node.shared.consensus.state.AccountNonceState;
import global.goldenera.node.shared.consensus.state.AddressAliasState;
import global.goldenera.node.shared.consensus.state.AuthorityState;
import global.goldenera.node.shared.consensus.state.BipState;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.consensus.state.TokenState;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.utils.PaginationUtil;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@RequestMapping("/api/core/v1/blockchain")
@RestController
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockchainApiV1 {

    private static final int MAX_TX_RANGE = 1000;
    private static final int MAX_BLOCK_HEADER_RANGE = 1000;

    ChainQuery chainQuery;
    ChainHeadStateCache chainHeadStateCache;
    EntityIndexRepository entityIndexRepository;
    MempoolStore mempoolStore;

    BlockchainBlockHeaderMapper blockchainBlockHeaderMapper;
    BlockchainTxMapper blockchainTxMapper;

    // ========================
    // Block Header endpoints (use partial loading for efficiency)
    // ========================

    @GetMapping("latest-height")
    public ResponseEntity<Long> getLatestBlockHeight() {
        return ResponseEntity.ok(chainQuery.getLatestBlockHeight()
                .orElseThrow(() -> new GENotFoundException("No blocks found")));
    }

    @GetMapping("block-header/by-range")
    public ResponseEntity<List<BlockchainBlockHeaderDtoV1>> getBlockHeaderByRange(
            @RequestParam(required = true) long fromHeight,
            @RequestParam(required = true) Long toHeight) {
        PaginationUtil.validateRangeRequest(fromHeight, toHeight, MAX_BLOCK_HEADER_RANGE);
        List<StoredBlock> storedBlockHeaders = chainQuery.findStoredBlockHeadersByHeightRange(fromHeight, toHeight);
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeaders));
    }

    @GetMapping("block-header/latest")
    public ResponseEntity<BlockchainBlockHeaderDtoV1> getLatestBlockHeader() {
        StoredBlock storedBlockHeader = chainQuery.getLatestBlockHash()
                .flatMap(chainQuery::getStoredBlockHeaderByHash)
                .orElseThrow(() -> new GENotFoundException("Block not found"));
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeader));
    }

    @GetMapping("block-header/by-hash/{hash}")
    public ResponseEntity<BlockchainBlockHeaderDtoV1> getBlockHeaderByHash(@PathVariable Hash hash) {
        StoredBlock storedBlockHeader = chainQuery.getStoredBlockHeaderByHash(hash)
                .orElseThrow(() -> new GENotFoundException("Block not found"));
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeader));
    }

    @GetMapping("block-header/by-height/{height}")
    public ResponseEntity<BlockchainBlockHeaderDtoV1> getBlockHeaderByHeight(@PathVariable Long height) {
        StoredBlock storedBlockHeader = chainQuery.getStoredBlockHeaderByHeight(height)
                .orElseThrow(() -> new GENotFoundException("Block not found"));
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeader));
    }

    @GetMapping("block-hash/by-height/{height}")
    public ResponseEntity<Hash> getBlockHashByHeight(@PathVariable Long height) {
        return ResponseEntity.ok(chainQuery.getBlockHashByHeight(height)
                .orElseThrow(() -> new GENotFoundException("Block not found at height " + height)));
    }

    @GetMapping("block-height/by-hash/{hash}")
    public ResponseEntity<Long> getBlockHeightByHash(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getStoredBlockHeaderByHash(hash)
                .map(sb -> sb.getHeight())
                .orElseThrow(() -> new GENotFoundException("Block not found for hash " + hash)));
    }

    // ========================
    // Full Block endpoints
    // ========================

    @GetMapping("block/by-hash/{hash}/txs")
    public ResponseEntity<List<BlockchainTxDtoV1>> getBlockTxsByHash(
            @PathVariable Hash hash,
            @RequestParam(required = true) Integer fromIndex,
            @RequestParam(required = true) Integer toIndex) {
        PaginationUtil.validateRangeRequest(fromIndex, toIndex, MAX_TX_RANGE);
        StoredBlock storedBlock = chainQuery.getStoredBlockByHash(hash)
                .orElseThrow(() -> new GENotFoundException("Block not found"));

        return ResponseEntity.ok(blockchainTxMapper.mapRange(storedBlock, fromIndex, toIndex));
    }

    // ========================
    // Transaction endpoints
    // ========================

    @GetMapping("tx/by-hash/{hash}")
    public ResponseEntity<BlockchainTxDtoV1> getTransactionByHash(@PathVariable Hash hash) {
        StoredBlock storedBlock = chainQuery.getTransactionBlock(hash)
                .orElseThrow(() -> new GENotFoundException("Transaction not found"));

        return ResponseEntity.ok(blockchainTxMapper.map(storedBlock, hash));
    }

    @GetMapping("tx/by-hash/{hash}/confirmations")
    public ResponseEntity<Long> getTransactionConfirmations(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getTransactionConfirmations(hash)
                .orElseThrow(() -> new GENotFoundException("Transaction not found or not in canonical chain")));
    }

    @GetMapping("tx/by-hash/{hash}/block-height")
    public ResponseEntity<Long> getTransactionBlockHeight(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getTransactionBlockHeight(hash)
                .orElseThrow(() -> new GENotFoundException("Transaction not found or not in canonical chain")));
    }

    // ========================
    // WorldState endpoints
    // ========================

    @GetMapping("worldstate/account/{address}/{tokenAddress}/balance")
    public ResponseEntity<AccountBalanceState> getWorldStateAccountBalance(@PathVariable Address address,
            @PathVariable Address tokenAddress) {
        AccountBalanceState accountBalanceState = chainHeadStateCache.getHeadState().getBalance(address, tokenAddress);
        if (!accountBalanceState.exists()) {
            throw new GENotFoundException("Account balance not found");
        }
        return ResponseEntity.ok(accountBalanceState);
    }

    @GetMapping("worldstate/account/{address}/nonce")
    public ResponseEntity<AccountNonceState> getWorldStateAccountNonce(@PathVariable Address address) {
        AccountNonceState accountNonceState = chainHeadStateCache.getHeadState().getNonce(address);
        if (!accountNonceState.exists()) {
            throw new GENotFoundException("Account nonce not found");
        }
        return ResponseEntity.ok(accountNonceState);
    }

    @GetMapping("worldstate/address-alias/{alias}")
    public ResponseEntity<AddressAliasState> getWorldStateAddressAlias(@PathVariable String alias) {
        AddressAliasState addressAliasState = chainHeadStateCache.getHeadState().getAddressAlias(alias);
        if (!addressAliasState.exists()) {
            throw new GENotFoundException("Address alias not found");
        }
        return ResponseEntity.ok(addressAliasState);
    }

    @GetMapping("worldstate/authority/{address}")
    public ResponseEntity<AuthorityState> getWorldStateAuthority(@PathVariable Address address) {
        AuthorityState authorityState = chainHeadStateCache.getHeadState().getAuthority(address);
        if (!authorityState.exists()) {
            throw new GENotFoundException("Authority not found");
        }
        return ResponseEntity.ok(authorityState);
    }

    @GetMapping("worldstate/bip-state/{hash}")
    public ResponseEntity<BipState> getWorldStateBipState(@PathVariable Hash hash) {
        BipState bipState = chainHeadStateCache.getHeadState().getBip(hash);
        if (!bipState.exists()) {
            throw new GENotFoundException("BIP state not found");
        }
        return ResponseEntity.ok(bipState);
    }

    @GetMapping("worldstate/network-params")
    public ResponseEntity<NetworkParamsState> getWorldStateNetworkParams() {
        NetworkParamsState networkParamsState = chainHeadStateCache.getHeadState().getParams();
        return ResponseEntity.ok(networkParamsState);
    }

    @GetMapping("worldstate/token/{address}")
    public ResponseEntity<TokenState> getWorldStateToken(@PathVariable Address address) {
        TokenState tokenState = chainHeadStateCache.getHeadState().getToken(address);
        if (!tokenState.exists()) {
            throw new GENotFoundException("Token not found");
        }
        return ResponseEntity.ok(tokenState);
    }

    @GetMapping("worldstate/tokens")
    public ResponseEntity<Map<Address, TokenState>> getAllTokens() {
        return ResponseEntity.ok(entityIndexRepository.getAllTokensWithAddresses());
    }

    @GetMapping("worldstate/authorities")
    public ResponseEntity<Map<Address, AuthorityState>> getAllAuthorities() {
        return ResponseEntity.ok(entityIndexRepository.getAllAuthoritiesWithAddresses());
    }

    // ========================
    // Account Summary endpoint (wallet-friendly)
    // ========================

    @GetMapping("account/{address}/summary")
    public ResponseEntity<AccountSummaryDtoV1> getAccountSummary(
            @PathVariable Address address,
            @RequestParam(required = false) Address tokenAddress) {
        WorldState state = chainHeadStateCache.getHeadState();

        AccountBalanceState balanceState = state.getBalance(address, Address.NATIVE_TOKEN);
        AccountNonceState nonceState = state.getNonce(address);

        Wei nativeBalance = balanceState.exists() ? balanceState.getBalance() : Wei.ZERO;
        long nonce = nonceState.exists() ? nonceState.getNonce() : -1L;
        int pendingTxCount = mempoolStore.getPendingTxCount(address);

        AccountSummaryDtoV1.AccountSummaryDtoV1Builder builder = AccountSummaryDtoV1.builder()
                .address(address)
                .nativeBalance(nativeBalance)
                .nonce(nonce)
                .nextNonce(nonce + 1)
                .pendingTxCount(pendingTxCount);

        // Optional token balance lookup
        if (tokenAddress != null) {
            AccountBalanceState tokenBalanceState = state.getBalance(address, tokenAddress);
            Wei tokenBalance = tokenBalanceState.exists() ? tokenBalanceState.getBalance() : Wei.ZERO;
            builder.tokenAddress(tokenAddress)
                    .tokenBalance(tokenBalance);
        }

        return ResponseEntity.ok(builder.build());
    }
}
