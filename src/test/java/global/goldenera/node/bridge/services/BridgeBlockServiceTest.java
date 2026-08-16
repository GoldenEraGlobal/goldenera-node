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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeLastBlockDtoV1;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1.FeeLevel;
import global.goldenera.node.core.api.v1.mempool.mappers.MempoolTxMapper;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.explorer.entities.ExBlockHeader;
import global.goldenera.node.explorer.services.core.ExBlockHeaderCoreService;

class BridgeBlockServiceTest {

    private final BridgeNetworkValidator networkValidator = mock(BridgeNetworkValidator.class);
    private final ExBlockHeaderCoreService explorer = mock(ExBlockHeaderCoreService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExBlockHeaderCoreService> explorerProvider = mock(ObjectProvider.class);
    private final ChainQuery chainQuery = mock(ChainQuery.class);
    private final MempoolTxMapper mempoolTxMapper = mock(MempoolTxMapper.class);

    private BridgeBlockService service;

    @BeforeEach
    void setUp() {
        when(explorerProvider.getIfAvailable()).thenReturn(explorer);
        FeeLevel fast = new FeeLevel(Wei.ZERO, Wei.ZERO, Wei.valueOf(123L));
        when(mempoolTxMapper.mapRecommendedFees())
                .thenReturn(new RecommendedFeesDtoV1(fast, fast, fast, 0L));
        service = new BridgeBlockService(networkValidator, explorerProvider, chainQuery, mempoolTxMapper);
    }

    @Test
    void explorerHeightWinsOverNewerCoreHeight() {
        ExBlockHeader explorerHeader = mock(ExBlockHeader.class);
        when(explorerHeader.getHeight()).thenReturn(100L);
        when(explorer.getLatestOptional()).thenReturn(Optional.of(explorerHeader));
        when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(101L));

        BridgeLastBlockDtoV1 result = service.getLastBlock(Network.MAINNET);

        assertThat(result.blockNumber()).isEqualTo(100L);
        assertThat(result.fee()).isEqualTo(BigInteger.valueOf(123L));
        verify(chainQuery, never()).getLatestBlockHeight();
    }

    @Test
    void fallsBackToCoreWhenExplorerHasNoCanonicalBlock() {
        when(explorer.getLatestOptional()).thenReturn(Optional.empty());
        when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(101L));

        BridgeLastBlockDtoV1 result = service.getLastBlock(Network.TESTNET);

        assertThat(result.blockNumber()).isEqualTo(101L);
        verify(chainQuery).getLatestBlockHeight();
    }
}
