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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.node.bridge.api.v1.dtos.BridgeTxDtoV1;
import global.goldenera.node.core.api.v1.blockchain.mappers.TxMapper;

class BridgeTxMapperTest {

    private final BridgeTxMapper mapper = new BridgeTxMapper(new TxMapper());

    @Test
    void mapsSignedTransferToExactBridgeShapeAndRoundTripsRawData() throws Exception {
        PrivateKey senderKey = PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", 1)));
        Address recipient = Address.fromHexString(String.format("0x%040x", 2));
        Instant timestamp = Instant.parse("2026-08-14T00:00:00Z");
        Tx tx = TxBuilder.create()
                .type(TxType.TRANSFER)
                .network(Network.MAINNET)
                .timestamp(timestamp)
                .recipient(recipient)
                .amount(Wei.valueOf(BigInteger.valueOf(100_000_000L)))
                .fee(Wei.valueOf(BigInteger.valueOf(1_000L)))
                .nonce(12L)
                .sign(senderKey);

        BridgeTxDtoV1 result = mapper.mapConfirmed(tx, 123L, String.format("0x%064x", 3), 0, 10L);

        assertThat(result.txHash()).isEqualTo(tx.getHash().toHexString());
        assertThat(result.network()).isEqualTo(Network.MAINNET);
        assertThat(result.version().name()).isEqualTo("V1");
        assertThat(result.timestamp()).isEqualTo(timestamp);
        assertThat(result.txType()).isEqualTo(TxType.TRANSFER);
        assertThat(result.nonce()).isEqualTo(BigInteger.valueOf(12L));
        assertThat(result.sender()).isEqualTo(tx.getSender().toChecksumAddress());
        assertThat(result.recipient()).isEqualTo(recipient.toChecksumAddress());
        assertThat(result.amount()).isEqualTo(BigInteger.valueOf(100_000_000L));
        assertThat(result.fee()).isEqualTo(BigInteger.valueOf(1_000L));
        assertThat(result.payloadType()).isNull();
        assertThat(result.payload()).isNull();
        assertThat(result.blockNumber()).isEqualTo(123L);
        assertThat(result.txIndex()).isZero();
        assertThat(result.status()).isEqualTo(BridgeTxDtoV1.Status.CONFIRMED);
        assertThat(result.confirmations()).isEqualTo(10L);
        assertThat(TxDecoder.INSTANCE.decode(org.apache.tuweni.bytes.Bytes.fromHexString(result.rawDataHex())).getHash())
                .isEqualTo(tx.getHash());
    }

    @Test
    void mapsMempoolMetadataAsUnconfirmed() throws Exception {
        PrivateKey senderKey = PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", 4)));
        Tx tx = TxBuilder.create()
                .type(TxType.TRANSFER)
                .network(Network.TESTNET)
                .recipient(Address.fromHexString(String.format("0x%040x", 5)))
                .amount(Wei.valueOf(BigInteger.ONE))
                .fee(Wei.valueOf(BigInteger.ONE))
                .nonce(0L)
                .sign(senderKey);

        BridgeTxDtoV1 result = mapper.mapMempool(tx);

        assertThat(result.status()).isEqualTo(BridgeTxDtoV1.Status.MEMPOOL);
        assertThat(result.blockNumber()).isNull();
        assertThat(result.blockHash()).isNull();
        assertThat(result.txIndex()).isNull();
        assertThat(result.confirmations()).isZero();
    }
}
