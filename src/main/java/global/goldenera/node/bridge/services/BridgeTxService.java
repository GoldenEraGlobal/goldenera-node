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
package global.goldenera.node.bridge.services;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.bridge.api.v1.dtos.BridgeBroadcastTxDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeBroadcastTxInDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeTxDtoV1;
import global.goldenera.node.bridge.mappers.BridgeTxMapper;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolManager.MempoolAddResult;
import global.goldenera.node.core.mempool.MempoolManager.MempoolReasonCode;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExTx;
import global.goldenera.node.explorer.services.core.ExTxCoreService;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.RequiredArgsConstructor;

@Service
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BridgeTxService {

    private final BridgeNetworkValidator networkValidator;
    private final ObjectProvider<ExTxCoreService> exTxCoreService;
    private final ChainQuery chainQuery;
    private final MempoolStore mempoolStore;
    private final MempoolManager mempoolManager;
    private final BridgeTxMapper bridgeTxMapper;

    public BridgeTxDtoV1 getByHash(Hash hash, Network network) {
        networkValidator.validate(network);

        ExTxCoreService explorer = exTxCoreService.getIfAvailable();
        ExTx explorerTx = explorer == null ? null : explorer.getByHashOptional(hash).orElse(null);
        if (explorerTx != null) {
            Long confirmations = explorer.getConfirmationsByHashOptional(hash).orElse(null);
            if (confirmations != null) {
                return bridgeTxMapper.mapConfirmed(
                        explorerTx,
                        explorerTx.getBlockHeight(),
                        explorerTx.getBlockHash().toHexString(),
                        explorerTx.getIndex(),
                        confirmations);
            }
        }

        StoredBlock block = chainQuery.getTransactionBlock(hash).orElse(null);
        if (block != null) {
            Tx tx = block.getTransactionByHash(hash);
            Integer index = block.getTransactionIndex().get(hash);
            long confirmations = chainQuery.getTransactionConfirmations(hash).orElse(0L);
            return bridgeTxMapper.mapConfirmed(
                    tx,
                    block.getHeight(),
                    block.getHash().toHexString(),
                    index == null ? -1 : index,
                    confirmations);
        }

        return mempoolStore.getTxByHash(hash)
                .map(entry -> bridgeTxMapper.mapMempool(entry.getTx()))
                .orElseThrow(() -> new GENotFoundException("Transaction not found"));
    }

    public BridgeBroadcastTxDtoV1 broadcast(BridgeBroadcastTxInDtoV1 input) {
        if (input == null || input.rawDataHex() == null || input.rawDataHex().isBlank()) {
            throw new GEValidationException("rawDataHex is required");
        }
        Network network = networkValidator.validate(input.network());
        Tx tx = TxDecoder.INSTANCE.decode(Bytes.fromHexString(input.rawDataHex()));
        if (tx.getNetwork() != network) {
            throw new GEValidationException("Transaction network does not match requested network");
        }

        if (chainQuery.getTransactionBlock(tx.getHash()).isPresent()
                || mempoolStore.getTxByHash(tx.getHash()).isPresent()) {
            return new BridgeBroadcastTxDtoV1(tx.getHash().toHexString(), true, "ACCEPTED", null);
        }

        MempoolManager.MempoolResult result = mempoolManager.addTx(tx);
        boolean duplicate = result.status() == MempoolAddResult.REJECTED_DUPLICATE
                && result.reasonCode() == MempoolReasonCode.DUPLICATE_HASH;
        if (!result.status().isSuccess() && !duplicate) {
            throw new GEValidationException(result.message() == null ? "Transaction rejected" : result.message());
        }
        return new BridgeBroadcastTxDtoV1(tx.getHash().toHexString(), true, "ACCEPTED", null);
    }
}
