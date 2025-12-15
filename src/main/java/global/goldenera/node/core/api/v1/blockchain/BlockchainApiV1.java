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
import java.util.stream.Collectors;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.api.v1.blockchain.dtos.AccountBalanceStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.AccountNonceStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.AccountSummaryDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.AddressAliasStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.AuthorityStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BipStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainBlockHeaderDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainTxDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.NetworkParamsStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.TokenStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.ValidatorStateDtoV1;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainBlockHeaderMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.BlockchainTxMapper;
import global.goldenera.node.core.api.v1.blockchain.mappers.StateMapper;
import global.goldenera.node.core.blockchain.genesis.GenesisStateService;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.domain.TxCacheEntry;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.security.CoreApiSecurity;
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
    GenesisStateService genesisStateService;

    BlockchainBlockHeaderMapper blockchainBlockHeaderMapper;
    BlockchainTxMapper blockchainTxMapper;
    StateMapper stateMapper;

    // ========================
    // Block Header endpoints (use partial loading for efficiency)
    // ========================

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("latest-height")
    public ResponseEntity<Long> getLatestBlockHeight() {
        return ResponseEntity.ok(chainQuery.getLatestBlockHeight()
                .orElseThrow(() -> new GENotFoundException("No blocks found")));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("block-header/by-range")
    public ResponseEntity<List<BlockchainBlockHeaderDtoV1>> getBlockHeaderByRange(
            @RequestParam(required = true) long fromHeight,
            @RequestParam(required = true) Long toHeight,
            @RequestParam(required = false, defaultValue = "false") boolean withEvents) {
        PaginationUtil.validateRangeRequest(fromHeight, toHeight, MAX_BLOCK_HEADER_RANGE);
        List<StoredBlock> storedBlockHeaders = chainQuery.findStoredBlockHeadersByHeightRange(fromHeight, toHeight);
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeaders, withEvents));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("block-header/latest")
    public ResponseEntity<BlockchainBlockHeaderDtoV1> getLatestBlockHeader(
            @RequestParam(required = false, defaultValue = "false") boolean withEvents) {
        StoredBlock storedBlockHeader = chainQuery.getLatestBlockHash()
                .flatMap(chainQuery::getStoredBlockHeaderByHash)
                .orElseThrow(() -> new GENotFoundException("Block not found"));
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeader, withEvents));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("block-header/by-hash/{hash}")
    public ResponseEntity<BlockchainBlockHeaderDtoV1> getBlockHeaderByHash(
            @PathVariable Hash hash,
            @RequestParam(required = false, defaultValue = "false") boolean withEvents) {
        StoredBlock storedBlockHeader = chainQuery.getStoredBlockHeaderByHash(hash)
                .orElseThrow(() -> new GENotFoundException("Block not found"));
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeader, withEvents));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("block-header/by-height/{height}")
    public ResponseEntity<BlockchainBlockHeaderDtoV1> getBlockHeaderByHeight(
            @PathVariable Long height,
            @RequestParam(required = false, defaultValue = "false") boolean withEvents) {
        StoredBlock storedBlockHeader = chainQuery.getStoredBlockHeaderByHeight(height)
                .orElseThrow(() -> new GENotFoundException("Block not found"));
        return ResponseEntity.ok(blockchainBlockHeaderMapper.map(storedBlockHeader, withEvents));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("block-hash/by-height/{height}")
    public ResponseEntity<Hash> getBlockHashByHeight(@PathVariable Long height) {
        return ResponseEntity.ok(chainQuery.getBlockHashByHeight(height)
                .orElseThrow(() -> new GENotFoundException("Block not found at height " + height)));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("block-height/by-hash/{hash}")
    public ResponseEntity<Long> getBlockHeightByHash(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getStoredBlockHeaderByHash(hash)
                .map(sb -> sb.getHeight())
                .orElseThrow(() -> new GENotFoundException("Block not found for hash " + hash)));
    }

    // ========================
    // Full Block endpoints
    // ========================

    @CoreApiSecurity(ApiKeyPermission.READ_TX)
    @GetMapping("block/by-hash/{hash}/txs")
    public ResponseEntity<List<BlockchainTxDtoV1>> getBlockTxsByHash(
            @PathVariable Hash hash,
            @RequestParam(required = true) Integer fromIndex,
            @RequestParam(required = true) Integer toIndex) {
        PaginationUtil.validateRangeRequest(fromIndex, toIndex, MAX_TX_RANGE);
        List<TxCacheEntry> entries = chainQuery.getTransactionRange(hash, fromIndex, toIndex);
        return ResponseEntity.ok(entries.stream().map(blockchainTxMapper::map).collect(Collectors.toList()));
    }

    // ========================
    // Transaction endpoints
    // ========================

    @CoreApiSecurity(ApiKeyPermission.READ_TX)
    @GetMapping("tx/by-hash/{hash}")
    public ResponseEntity<BlockchainTxDtoV1> getTransactionByHash(@PathVariable Hash hash) {
        TxCacheEntry entry = chainQuery.getTransactionEntry(hash)
                .orElseThrow(() -> new GENotFoundException("Transaction not found"));

        return ResponseEntity.ok(blockchainTxMapper.map(entry));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_TX)
    @GetMapping("tx/by-hash/{hash}/confirmations")
    public ResponseEntity<Long> getTransactionConfirmations(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getTransactionConfirmations(hash)
                .orElseThrow(() -> new GENotFoundException("Transaction not found or not in canonical chain")));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_TX)
    @GetMapping("tx/by-hash/{hash}/block-height")
    public ResponseEntity<Long> getTransactionBlockHeight(@PathVariable Hash hash) {
        return ResponseEntity.ok(chainQuery.getTransactionBlockHeight(hash)
                .orElseThrow(() -> new GENotFoundException("Transaction not found or not in canonical chain")));
    }

    // ========================
    // WorldState endpoints
    // ========================

    @CoreApiSecurity(ApiKeyPermission.READ_ACCOUNT)
    @GetMapping("worldstate/account/{address}/{tokenAddress}/balance")
    public ResponseEntity<AccountBalanceStateDtoV1> getWorldStateAccountBalance(@PathVariable Address address,
            @PathVariable Address tokenAddress) {
        AccountBalanceState accountBalanceState = chainHeadStateCache.getHeadState().getBalance(address, tokenAddress);
        if (!accountBalanceState.exists()) {
            throw new GENotFoundException("Account balance not found");
        }
        return ResponseEntity.ok(stateMapper.map(accountBalanceState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_ACCOUNT)
    @GetMapping("worldstate/account/{address}/nonce")
    public ResponseEntity<AccountNonceStateDtoV1> getWorldStateAccountNonce(@PathVariable Address address) {
        AccountNonceState accountNonceState = chainHeadStateCache.getHeadState().getNonce(address);
        if (!accountNonceState.exists()) {
            throw new GENotFoundException("Account nonce not found");
        }
        return ResponseEntity.ok(stateMapper.map(accountNonceState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_ADDRESS_ALIAS)
    @GetMapping("worldstate/address-alias/{alias}")
    public ResponseEntity<AddressAliasStateDtoV1> getWorldStateAddressAlias(@PathVariable String alias) {
        var addressAliasState = chainHeadStateCache.getHeadState().getAddressAlias(alias);
        if (!addressAliasState.exists()) {
            throw new GENotFoundException("Address alias not found");
        }
        return ResponseEntity.ok(stateMapper.map(addressAliasState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_AUTHORITY)
    @GetMapping("worldstate/authority/{address}")
    public ResponseEntity<AuthorityStateDtoV1> getWorldStateAuthority(@PathVariable Address address) {
        var authorityState = chainHeadStateCache.getHeadState().getAuthority(address);
        if (!authorityState.exists()) {
            throw new GENotFoundException("Authority not found");
        }
        return ResponseEntity.ok(stateMapper.map(authorityState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BIP_STATE)
    @GetMapping("worldstate/bip-state/{hash}")
    public ResponseEntity<BipStateDtoV1> getWorldStateBipState(@PathVariable Hash hash) {
        var bipState = chainHeadStateCache.getHeadState().getBip(hash);
        if (!bipState.exists()) {
            throw new GENotFoundException("BIP state not found");
        }
        return ResponseEntity.ok(stateMapper.map(bipState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_NETWORK_PARAMS)
    @GetMapping("worldstate/network-params")
    public ResponseEntity<NetworkParamsStateDtoV1> getWorldStateNetworkParams() {
        var networkParamsState = chainHeadStateCache.getHeadState().getParams();
        return ResponseEntity.ok(stateMapper.map(networkParamsState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_TOKEN)
    @GetMapping("worldstate/token/{address}")
    public ResponseEntity<TokenStateDtoV1> getWorldStateToken(@PathVariable Address address) {
        var tokenState = chainHeadStateCache.getHeadState().getToken(address);
        if (!tokenState.exists()) {
            throw new GENotFoundException("Token not found");
        }
        return ResponseEntity.ok(stateMapper.map(tokenState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_TOKEN)
    @GetMapping("worldstate/tokens")
    public ResponseEntity<Map<Address, TokenStateDtoV1>> getAllTokens() {
        return ResponseEntity.ok(
                entityIndexRepository.getAllTokensWithAddresses().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> stateMapper.map(e.getValue()))));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_AUTHORITY)
    @GetMapping("worldstate/authorities")
    public ResponseEntity<Map<Address, AuthorityStateDtoV1>> getAllAuthorities() {
        return ResponseEntity.ok(
                entityIndexRepository.getAllAuthoritiesWithAddresses().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> stateMapper.map(e.getValue()))));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_VALIDATOR)
    @GetMapping("worldstate/validator/{address}")
    public ResponseEntity<ValidatorStateDtoV1> getWorldStateValidator(@PathVariable Address address) {
        var validatorState = chainHeadStateCache.getHeadState().getValidator(address);
        if (!validatorState.exists()) {
            throw new GENotFoundException("Validator not found");
        }
        return ResponseEntity.ok(stateMapper.map(validatorState));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_VALIDATOR)
    @GetMapping("worldstate/validators")
    public ResponseEntity<Map<Address, ValidatorStateDtoV1>> getAllValidators() {
        return ResponseEntity.ok(
                entityIndexRepository.getAllValidatorsWithAddresses().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> stateMapper.map(e.getValue()))));
    }

    // ========================
    // Account Summary endpoint (wallet-friendly)
    // ========================

    @CoreApiSecurity(ApiKeyPermission.READ_ACCOUNT)
    @GetMapping("account/{address}/summary")
    public ResponseEntity<AccountSummaryDtoV1> getAccountSummary(
            @PathVariable Address address,
            @RequestParam(required = false) Address tokenAddress) {
        WorldState state = chainHeadStateCache.getHeadState();

        AccountBalanceState balanceState = state.getBalance(address, Address.NATIVE_TOKEN);
        AccountNonceState nonceState = state.getNonce(address);

        Wei nativeBalance = balanceState.exists() ? balanceState.getBalance() : Wei.ZERO;
        // If account never sent a tx, confirmedNonce is -1
        long confirmedNonce = nonceState.exists() ? nonceState.getNonce() : -1L;
        int pendingTxCount = mempoolStore.getPendingTxCount(address);

        // Calculate next available nonce considering mempool transactions and gaps
        // If confirmedNonce is -1 (never sent tx), nextNonce starts from 0
        long nextNonce = mempoolStore.getNextAvailableNonce(address, confirmedNonce);

        AccountSummaryDtoV1.AccountSummaryDtoV1Builder builder = AccountSummaryDtoV1.builder()
                .address(address)
                .nativeBalance(nativeBalance)
                .nonce(confirmedNonce)
                .nextNonce(nextNonce)
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

    // ========================
    // Genesis State endpoints (cached, immutable)
    // ========================

    @CoreApiSecurity(ApiKeyPermission.READ_NETWORK_PARAMS)
    @GetMapping("genesis/network-params")
    public ResponseEntity<NetworkParamsStateDtoV1> getGenesisNetworkParams() {
        return ResponseEntity.ok(stateMapper.map(genesisStateService.getGenesisNetworkParams()));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_TOKEN)
    @GetMapping("genesis/native-token")
    public ResponseEntity<TokenStateDtoV1> getGenesisNativeToken() {
        return ResponseEntity.ok(stateMapper.map(genesisStateService.getGenesisNativeToken()));
    }

    @CoreApiSecurity(ApiKeyPermission.READ_AUTHORITY)
    @GetMapping("genesis/authorities")
    public ResponseEntity<Map<Address, AuthorityStateDtoV1>> getGenesisAuthorities() {
        Map<Address, AuthorityStateDtoV1> result = genesisStateService.getGenesisAuthoritiesWithAddresses()
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> stateMapper.map(e.getValue())));
        return ResponseEntity.ok(result);
    }

    @CoreApiSecurity(ApiKeyPermission.READ_VALIDATOR)
    @GetMapping("genesis/validators")
    public ResponseEntity<Map<Address, ValidatorStateDtoV1>> getGenesisValidators() {
        Map<Address, ValidatorStateDtoV1> result = genesisStateService.getGenesisValidatorsWithAddresses()
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> stateMapper.map(e.getValue())));
        return ResponseEntity.ok(result);
    }

    @CoreApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
    @GetMapping("genesis/block-hash")
    public ResponseEntity<Hash> getGenesisBlockHash() {
        return ResponseEntity.ok(genesisStateService.getGenesisBlockHash());
    }
}
