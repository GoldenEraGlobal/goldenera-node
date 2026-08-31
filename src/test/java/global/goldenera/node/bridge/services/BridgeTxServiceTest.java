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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import global.goldenera.node.bridge.api.v1.dtos.BridgeBroadcastTxDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeBroadcastTxInDtoV1;
import global.goldenera.node.bridge.api.v1.dtos.BridgeTxDtoV1;
import global.goldenera.node.bridge.exceptions.BridgeCapabilityException;
import global.goldenera.node.bridge.mappers.BridgeTxMapper;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;

class BridgeTxServiceTest {

    private final GeneralProperties generalProperties = new GeneralProperties();
    private final ChainQuery chainQuery = mock(ChainQuery.class);
    private final MempoolStore mempool = mock(MempoolStore.class);
    private final MempoolManager manager = mock(MempoolManager.class);
    private final BridgeTxMapper mapper = mock(BridgeTxMapper.class);

    private BridgeTxService service;

    @BeforeEach
    void setUp() {
        generalProperties.setNetwork(Network.MAINNET);
		service = new BridgeTxService(generalProperties, chainQuery, mempool, manager, mapper);
    }

    @Test
	void coreCanonicalTransactionWinsOverExplorerAndMempool() {
		Hash hash = hash(1);
		Tx tx = mock(Tx.class);
		StoredBlock block = mock(StoredBlock.class);
		BridgeTxDtoV1 expected = mock(BridgeTxDtoV1.class);
		when(chainQuery.getTransactionBlock(hash)).thenReturn(Optional.of(block));
		when(block.getTransactionByHash(hash)).thenReturn(tx);
		when(block.getTransactionIndex()).thenReturn(Map.of(hash, 3));
		when(block.getHeight()).thenReturn(9L);
		when(block.getHash()).thenReturn(hash(2));
		when(chainQuery.getTransactionConfirmations(hash)).thenReturn(Optional.of(7L));
		when(mapper.mapConfirmed(tx, 9L, hash(2).toHexString(), 3, 7L)).thenReturn(expected);

        assertThat(service.getByHash(hash)).isSameAs(expected);
		verify(mempool, never()).getTxByHash(hash);
    }

    @Test
    void fallsBackToMempoolWhenNotCanonical() {
        Hash hash = hash(3);
        Tx tx = mock(Tx.class);
        MempoolEntry entry = new MempoolEntry(tx);
        BridgeTxDtoV1 expected = mock(BridgeTxDtoV1.class);
		when(chainQuery.getTransactionBlock(hash)).thenReturn(Optional.empty());
        when(mempool.getTxByHash(hash)).thenReturn(Optional.of(entry));
        when(mapper.mapMempool(tx)).thenReturn(expected);

		assertThat(service.getByHash(hash)).isSameAs(expected);
    }

    @Test
    void returnsNotFoundOnlyAfterAllSourcesMiss() {
        Hash hash = hash(4);
        when(chainQuery.getTransactionBlock(hash)).thenReturn(Optional.empty());
        when(mempool.getTxByHash(hash)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getByHash(hash))
				.isInstanceOf(GENotFoundException.class);
	}

	@Test
	void rejectsIncompleteCanonicalMetadataInsteadOfReturningSentinels() {
		Hash hash = hash(5);
		StoredBlock block = mock(StoredBlock.class);
		when(chainQuery.getTransactionBlock(hash)).thenReturn(Optional.of(block));
		when(block.getTransactionByHash(hash)).thenReturn(mock(Tx.class));
		when(block.getTransactionIndex()).thenReturn(Map.of());

		assertThatThrownBy(() -> service.getByHash(hash))
				.isInstanceOf(BridgeCapabilityException.class);
	}

    @Test
    void broadcastIsIdempotentForTransactionAlreadyInCanonicalChain() throws Exception {
        PrivateKey key = PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", 10)));
        Tx tx = TxBuilder.create()
                .type(TxType.TRANSFER)
                .network(Network.MAINNET)
                .recipient(Address.fromHexString(String.format("0x%040x", 11)))
                .amount(Wei.valueOf(1L))
                .fee(Wei.valueOf(1L))
                .nonce(0L)
                .sign(key);
        when(chainQuery.getTransactionBlock(tx.getHash())).thenReturn(Optional.of(mock(StoredBlock.class)));

        BridgeBroadcastTxDtoV1 result = service.broadcast(new BridgeBroadcastTxInDtoV1(
                TxEncoder.INSTANCE.encode(tx, true).toHexString()));

        assertThat(result.network()).isEqualTo(Network.MAINNET);
        assertThat(result.accepted()).isTrue();
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.txHash()).isEqualTo(tx.getHash().toHexString());
        verify(manager, never()).addTx(any(Tx.class));
    }

    @Test
    void orphanCacheEntryDoesNotMakeBroadcastLookAccepted() throws Exception {
        PrivateKey key = PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", 12)));
        Tx tx = TxBuilder.create()
                .type(TxType.TRANSFER)
                .network(Network.MAINNET)
                .recipient(Address.fromHexString(String.format("0x%040x", 13)))
                .amount(Wei.valueOf(1L))
                .fee(Wei.valueOf(1L))
                .nonce(0L)
                .sign(key);
        when(chainQuery.getTransactionByHash(tx.getHash())).thenReturn(Optional.of(tx));
        when(chainQuery.getTransactionBlock(tx.getHash())).thenReturn(Optional.empty());
        when(mempool.getTxByHash(tx.getHash())).thenReturn(Optional.empty());
        when(manager.addTx(any(Tx.class))).thenReturn(new MempoolManager.MempoolResult(
                MempoolManager.MempoolAddResult.SUCCESS,
                null,
                null));

        BridgeBroadcastTxDtoV1 result = service.broadcast(new BridgeBroadcastTxInDtoV1(
                TxEncoder.INSTANCE.encode(tx, true).toHexString()));

        assertThat(result.network()).isEqualTo(Network.MAINNET);
        assertThat(result.accepted()).isTrue();
        verify(manager).addTx(argThat(decoded -> decoded.getHash().equals(tx.getHash())));
        verify(chainQuery, never()).getTransactionByHash(tx.getHash());
    }

    @Test
    void broadcastRejectsTransactionFromAnotherNetworkWithoutRequestNetwork() throws Exception {
        PrivateKey key = PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", 14)));
        Tx tx = TxBuilder.create()
                .type(TxType.TRANSFER)
                .network(Network.TESTNET)
                .recipient(Address.fromHexString(String.format("0x%040x", 15)))
                .amount(Wei.valueOf(1L)).fee(Wei.valueOf(1L)).nonce(0L).sign(key);

        assertThatThrownBy(() -> service.broadcast(new BridgeBroadcastTxInDtoV1(
                TxEncoder.INSTANCE.encode(tx, true).toHexString())))
                .isInstanceOf(GEValidationException.class)
                .hasMessageContaining("this node's network");
        verify(manager, never()).addTx(any(Tx.class));
    }

    private static Hash hash(int value) {
        return Hash.fromHexString(String.format("0x%064x", value));
    }
}
