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
package global.goldenera.node.core.api.v1.mempool.mappers;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.properties.MempoolProperties;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * Maps mempool data to DTOs.
 */
@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MempoolTxMapper {

    static final long AVERAGE_TX_SIZE = 150L;

    ChainHeadStateCache chainHeadStateCache;
    MempoolStore mempoolStore;
    MempoolProperties mempoolProperties;

    /**
     * Calculates and returns recommended fees based on network params and mempool
     * state.
     */
    public RecommendedFeesDtoV1 mapRecommendedFees() {
        NetworkParamsState networkParams = chainHeadStateCache.getHeadState().getParams();
        Wei networkMinBaseFee = networkParams.getMinTxBaseFee();
        Wei networkMinByteFee = networkParams.getMinTxByteFee();
        Wei nodeMinFee = Wei.valueOf(mempoolProperties.getMinAcceptableFeeWei());
        Wei networkMinTotal = networkMinBaseFee.add(networkMinByteFee.multiply(AVERAGE_TX_SIZE));
        Wei effectiveMinTotal = networkMinTotal.compareTo(nodeMinFee) >= 0 ? networkMinTotal : nodeMinFee;

        Wei minBaseFee = networkMinBaseFee;
        Wei minByteFee = calculateMinByteFee(effectiveMinTotal, networkMinTotal, minBaseFee, networkMinByteFee);

        MempoolStore.FeeStatistics feeStats = mempoolStore.getFeeStatistics();

        // Slow = minimum acceptable
        RecommendedFeesDtoV1.FeeLevel slow = new RecommendedFeesDtoV1.FeeLevel(
                minBaseFee, minByteFee, effectiveMinTotal);

        // Standard = median from mempool or 10% above min
        Wei standardByteFee = calculateStandardByteFee(feeStats, minByteFee);
        Wei standardTotal = minBaseFee.add(standardByteFee.multiply(AVERAGE_TX_SIZE));
        RecommendedFeesDtoV1.FeeLevel standard = new RecommendedFeesDtoV1.FeeLevel(
                minBaseFee, standardByteFee, standardTotal);

        // Fast = 75th percentile from mempool or 25% above min
        Wei fastByteFee = calculateFastByteFee(feeStats, minByteFee, standardByteFee);
        Wei fastTotal = minBaseFee.add(fastByteFee.multiply(AVERAGE_TX_SIZE));
        RecommendedFeesDtoV1.FeeLevel fast = new RecommendedFeesDtoV1.FeeLevel(
                minBaseFee, fastByteFee, fastTotal);

        return new RecommendedFeesDtoV1(slow, standard, fast, mempoolStore.getCount());
    }

    private Wei calculateMinByteFee(Wei effectiveMinTotal, Wei networkMinTotal, Wei minBaseFee, Wei networkMinByteFee) {
        if (effectiveMinTotal.compareTo(networkMinTotal) > 0) {
            return effectiveMinTotal.subtract(minBaseFee).divide(AVERAGE_TX_SIZE);
        }
        return networkMinByteFee;
    }

    private Wei calculateStandardByteFee(MempoolStore.FeeStatistics feeStats, Wei minByteFee) {
        if (feeStats.txCount() == 0 || feeStats.medianFeePerByte() <= 0) {
            return minByteFee.multiply(110).divide(100);
        }
        Wei medianFromMempool = Wei.valueOf(BigDecimal.valueOf(feeStats.medianFeePerByte())
                .setScale(0, RoundingMode.CEILING)
                .toBigInteger());
        return medianFromMempool.compareTo(minByteFee) >= 0 ? medianFromMempool : minByteFee;
    }

    private Wei calculateFastByteFee(MempoolStore.FeeStatistics feeStats, Wei minByteFee, Wei standardByteFee) {
        if (feeStats.txCount() == 0 || feeStats.fastFeePerByte() <= 0) {
            return minByteFee.multiply(125).divide(100);
        }
        Wei fastFromMempool = Wei.valueOf(BigDecimal.valueOf(feeStats.fastFeePerByte())
                .setScale(0, RoundingMode.CEILING)
                .toBigInteger());
        if (fastFromMempool.compareTo(standardByteFee) > 0) {
            return fastFromMempool;
        }
        return standardByteFee.multiply(120).divide(100);
    }
}
