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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeLastBlockDtoV1;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1.FeeLevel;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1;
import global.goldenera.node.core.api.v1.mempool.mappers.MempoolTxMapper;
import global.goldenera.node.core.api.v1.mempool.mappers.MempoolTxMapper.RecommendedFeesAtHead;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import global.goldenera.node.shared.properties.GeneralProperties;

class BridgeBlockServiceTest {

    private final GeneralProperties generalProperties = new GeneralProperties();
    private final MempoolTxMapper mempoolTxMapper = mock(MempoolTxMapper.class);

    private BridgeBlockService service;

    @BeforeEach
    void setUp() {
        generalProperties.setNetwork(Network.MAINNET);
        FeeLevel slow = new FeeLevel(Wei.ZERO, Wei.ZERO, Wei.ZERO, Wei.ZERO, Wei.valueOf(100L));
        FeeLevel standard = new FeeLevel(Wei.ZERO, Wei.ZERO, Wei.ZERO, Wei.ZERO, Wei.valueOf(123L));
        FeeLevel fast = new FeeLevel(Wei.ZERO, Wei.ZERO, Wei.ZERO, Wei.ZERO, Wei.valueOf(999L));
        StoredBlock head = mock(StoredBlock.class);
        when(head.getHeight()).thenReturn(101L);
        when(mempoolTxMapper.mapRecommendedFeesAtHead())
				.thenReturn(new RecommendedFeesAtHead(
						head, new RecommendedFeesDtoV1(slow, standard, fast, 0L)));
		service = new BridgeBlockService(generalProperties, mempoolTxMapper);
    }

    @Test
	void coreCanonicalHeightWinsOverLaggingExplorerHeight() {
        BridgeLastBlockDtoV1 result = service.getLastBlock();

		assertThat(result.network()).isEqualTo(Network.MAINNET);
		assertThat(result.blockNumber()).isEqualTo(101L);
		assertThat(result.fee()).isEqualTo(BigInteger.valueOf(123L));
		verify(mempoolTxMapper).mapRecommendedFeesAtHead();
    }

    @Test
	void noCanonicalCoreHeadIsNotFound() {
		when(mempoolTxMapper.mapRecommendedFeesAtHead())
				.thenThrow(new GENotFoundException("Latest canonical block not found"));

		assertThatThrownBy(() -> service.getLastBlock())
				.isInstanceOf(GENotFoundException.class);
	}
}
