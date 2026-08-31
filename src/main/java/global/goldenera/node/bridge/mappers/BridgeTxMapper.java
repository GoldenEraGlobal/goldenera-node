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
package global.goldenera.node.bridge.mappers;

import java.math.BigInteger;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import global.goldenera.node.bridge.api.v1.dtos.BridgeTxDtoV1;
import global.goldenera.node.core.api.v1.blockchain.mappers.TxMapper;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BridgeTxMapper {

    private final TxMapper txMapper;

    public BridgeTxDtoV1 mapConfirmed(
            Tx tx,
            long blockNumber,
            String blockHash,
            int txIndex,
            long confirmations) {
        return map(tx, blockNumber, blockHash, txIndex, BridgeTxDtoV1.Status.CONFIRMED, confirmations);
    }

    public BridgeTxDtoV1 mapMempool(Tx tx) {
        return map(tx, null, null, null, BridgeTxDtoV1.Status.MEMPOOL, 0L);
    }

    private BridgeTxDtoV1 map(
            Tx tx,
            Long blockNumber,
            String blockHash,
            Integer txIndex,
            BridgeTxDtoV1.Status status,
            Long confirmations) {
        return new BridgeTxDtoV1(
                tx.getHash().toHexString(),
                tx.getNetwork(),
                TxEncoder.INSTANCE.encode(tx, true).toHexString(),
                tx.getVersion(),
                tx.getTimestamp(),
                tx.getType(),
                BigInteger.valueOf(tx.getNonce()),
                tx.getSender().toChecksumAddress(),
                tx.getRecipient() == null ? null : tx.getRecipient().toChecksumAddress(),
                tx.getAmount() == null ? null : tx.getAmount().toBigInteger(),
                tx.getFee().toBigInteger(),
                tx.getTokenAddress() == null ? null : tx.getTokenAddress().toChecksumAddress(),
                tx.getMessage() == null ? null : tx.getMessage().toHexString(),
                tx.getReferenceHash() == null ? null : tx.getReferenceHash().toHexString(),
                tx.getSignature().toHexString(),
                tx.getPayload() == null ? null : tx.getPayload().getPayloadType().name(),
                txMapper.mapPayload(tx.getPayload()),
                tx.getSize(),
                blockNumber,
                blockHash,
                txIndex,
                status,
                confirmations);
    }
}
