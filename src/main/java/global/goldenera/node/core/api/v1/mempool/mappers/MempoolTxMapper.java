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

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.core.mempool.FeeRecommendationService;
import global.goldenera.node.core.mempool.FeeRecommendationService.FeeHistoryUnavailableException;
import global.goldenera.node.core.mempool.FeeRecommendationService.FeeTotals;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
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
    static final int MAX_SNAPSHOT_ATTEMPTS = 3;
    private static final BigInteger AVERAGE_TX_SIZE_VALUE = BigInteger.valueOf(AVERAGE_TX_SIZE);
    private static final BigInteger UINT256_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    ChainHeadStateCache chainHeadStateCache;
    MempoolStore mempoolStore;
    MempoolProperties mempoolProperties;
    FeeRecommendationService feeRecommendationService;

    /**
     * Calculates recommended fees from network parameters and recent canonical
     * blocks. The mempool contributes only the reported size.
     */
    public RecommendedFeesDtoV1 mapRecommendedFees() {
        return mapRecommendedFeesAtHead().fees();
    }

    public RecommendedFeesAtHead mapRecommendedFeesAtHead() {
        FeeHistoryUnavailableException lastFailure = null;
        for (int attempt = 0; attempt < MAX_SNAPSHOT_ATTEMPTS; attempt++) {
            HeadStateSnapshot snapshot = chainHeadStateCache.getHeadSnapshot();
            try {
                return new RecommendedFeesAtHead(snapshot.head(), mapRecommendedFees(snapshot));
            } catch (FeeHistoryUnavailableException failure) {
                lastFailure = failure;
            }
        }
        throw new FeeHistoryUnavailableException(
                "Canonical fee history changed or remained incomplete after " + MAX_SNAPSHOT_ATTEMPTS
                        + " attempts: " + lastFailure.getMessage());
    }

    private RecommendedFeesDtoV1 mapRecommendedFees(HeadStateSnapshot snapshot) {
        NetworkParamsState networkParams = snapshot.state().getParams();
        BigInteger minBaseFee = networkParams.getMinTxBaseFee().toBigInteger();
        BigInteger minByteFee = networkParams.getMinTxByteFee().toBigInteger();
        BigInteger nodeMinFee = requireUint256(
                mempoolProperties.getMinAcceptableFeeWei(), "Minimum acceptable fee");
        BigInteger networkMinTotal = requireUint256(
                minBaseFee.add(minByteFee.multiply(AVERAGE_TX_SIZE_VALUE)),
                "Network minimum fee for an average transaction");
        BigInteger minimumTotal = max(networkMinTotal, nodeMinFee);
        BigInteger configuredMaximum = configuredMaximumFee(minimumTotal);
        BigInteger maximumByteFee = calculateMaximumByteFee(configuredMaximum, minBaseFee, minByteFee);
        BigInteger maximumMiningFee = configuredMaximum.divide(AVERAGE_TX_SIZE_VALUE);
        BigInteger maximumTotal = calculateTotalFee(
                nodeMinFee, minBaseFee, maximumByteFee, maximumMiningFee);

        FeeTotals feeTotals = feeRecommendationService.recommend(
                snapshot.head(), wei(minimumTotal), AVERAGE_TX_SIZE, wei(maximumTotal));

        // Slow = minimum acceptable
        RecommendedFeesDtoV1.FeeLevel slow = new RecommendedFeesDtoV1.FeeLevel(
                wei(minBaseFee), wei(minByteFee), wei(nodeMinFee), Wei.ZERO, wei(minimumTotal));

        // Standard = 60th percentile from the recent-block fee oracle.
        BigInteger standardByteFee = minByteFee;
        BigInteger standardMiningFee = clamp(
                feeTotals.standardMiningFeePerByte().toBigInteger(), BigInteger.ZERO, maximumMiningFee);
        BigInteger standardTotal = calculateTotalFee(
                nodeMinFee, minBaseFee, standardByteFee, standardMiningFee);
        RecommendedFeesDtoV1.FeeLevel standard = new RecommendedFeesDtoV1.FeeLevel(
                wei(minBaseFee), wei(standardByteFee), wei(nodeMinFee), wei(standardMiningFee), wei(standardTotal));

        // Fast = 90th percentile with a small minimum premium for an idle chain.
        BigInteger fastFloor = min(percentageCeiling(minByteFee, 120), maximumByteFee);
        BigInteger fastByteFee = max(standardByteFee, fastFloor);
        BigInteger fastMiningFee = clamp(
                feeTotals.fastMiningFeePerByte().toBigInteger(),
                standardMiningFee,
                maximumMiningFee);
        BigInteger fastTotal = calculateTotalFee(nodeMinFee, minBaseFee, fastByteFee, fastMiningFee);
        RecommendedFeesDtoV1.FeeLevel fast = new RecommendedFeesDtoV1.FeeLevel(
                wei(minBaseFee), wei(fastByteFee), wei(nodeMinFee), wei(fastMiningFee), wei(fastTotal));

        return new RecommendedFeesDtoV1(slow, standard, fast, mempoolStore.getCount());
    }

    private BigInteger configuredMaximumFee(BigInteger minimumTotal) {
        BigInteger configured = requireUint256(
                mempoolProperties.getMaxRecommendedFeeWei(), "Maximum recommended fee");
        if (configured.signum() == 0) {
            throw new IllegalStateException("Maximum recommended fee must be positive");
        }
        return max(configured, minimumTotal);
    }

    private BigInteger calculateMaximumByteFee(
            BigInteger maximumTotal,
            BigInteger baseFee,
            BigInteger minimumByteFee) {
        BigInteger available = maximumTotal.subtract(baseFee);
        BigInteger maximumByteFee = available.divide(AVERAGE_TX_SIZE_VALUE);
        return max(maximumByteFee, minimumByteFee);
    }

    private BigInteger clamp(BigInteger value, BigInteger minimum, BigInteger maximum) {
        return min(max(value, minimum), maximum);
    }

    private BigInteger calculateTotalFee(
            BigInteger minimumTotalFee,
            BigInteger baseFee,
            BigInteger byteFee,
            BigInteger miningFee) {
        BigInteger networkFee = baseFee.add(byteFee.multiply(AVERAGE_TX_SIZE_VALUE));
        BigInteger congestionFee = miningFee.multiply(AVERAGE_TX_SIZE_VALUE);
        return requireUint256(
                max(minimumTotalFee, max(networkFee, congestionFee)),
                "Recommended fee for an average transaction");
    }

    private BigInteger percentageCeiling(BigInteger value, long percentage) {
        return divideCeiling(value.multiply(BigInteger.valueOf(percentage)), BigInteger.valueOf(100));
    }

    private BigInteger divideCeiling(BigInteger dividend, BigInteger divisor) {
        BigInteger[] result = dividend.divideAndRemainder(divisor);
        return result[1].signum() == 0 ? result[0] : result[0].add(BigInteger.ONE);
    }

    private BigInteger requireUint256(BigInteger value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(UINT256_MAX) > 0) {
            throw new IllegalStateException(field + " must fit into uint256");
        }
        return value;
    }

    private Wei wei(BigInteger value) {
        return Wei.valueOf(requireUint256(value, "Recommended fee component"));
    }

    private BigInteger max(BigInteger first, BigInteger second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private BigInteger min(BigInteger first, BigInteger second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    public record RecommendedFeesAtHead(
            StoredBlock head,
            RecommendedFeesDtoV1 fees) {
    }
}
