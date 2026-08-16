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

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.bridge.api.v1.dtos.BridgeLastBlockDtoV1;
import global.goldenera.node.core.api.v1.mempool.mappers.MempoolTxMapper;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.explorer.services.core.ExBlockHeaderCoreService;
import global.goldenera.node.shared.exceptions.GENotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BridgeBlockService {

    private final BridgeNetworkValidator networkValidator;
    private final ObjectProvider<ExBlockHeaderCoreService> exBlockHeaderCoreService;
    private final ChainQuery chainQuery;
    private final MempoolTxMapper mempoolTxMapper;

    public BridgeLastBlockDtoV1 getLastBlock(Network network) {
        networkValidator.validate(network);
        ExBlockHeaderCoreService explorer = exBlockHeaderCoreService.getIfAvailable();
        Long height = explorer == null
                ? null
                : explorer.getLatestOptional().map(header -> header.getHeight()).orElse(null);
        if (height == null) {
            height = chainQuery.getLatestBlockHeight().orElse(null);
        }
        if (height == null) {
            throw new GENotFoundException("Latest canonical block not found");
        }
        return new BridgeLastBlockDtoV1(
                network,
                height,
                mempoolTxMapper.mapRecommendedFees().getFast().getTotalForAverageTx().toBigInteger());
    }
}
