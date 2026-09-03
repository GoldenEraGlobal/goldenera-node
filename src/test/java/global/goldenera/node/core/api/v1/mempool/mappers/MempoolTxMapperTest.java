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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1;
import global.goldenera.node.core.api.v1.mempool.dtos.RecommendedFeesDtoV1.FeeLevel;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache.HeadStateSnapshot;
import global.goldenera.node.core.mempool.FeeRecommendationService;
import global.goldenera.node.core.mempool.FeeRecommendationService.FeeHistoryUnavailableException;
import global.goldenera.node.core.mempool.FeeRecommendationService.FeeTotals;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

class MempoolTxMapperTest {

	private static final Wei MAXIMUM_REPRESENTABLE_TOTAL = Wei.valueOf(999_950);
	private static final BigInteger UINT256_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

	private final ChainHeadStateCache chainHeadStateCache = mock(ChainHeadStateCache.class);
	private final MempoolStore mempoolStore = mock(MempoolStore.class);
	private final FeeRecommendationService feeRecommendationService = mock(FeeRecommendationService.class);
	private final MempoolProperties mempoolProperties = new MempoolProperties();
	private final StoredBlock head = mock(StoredBlock.class);
	private final NetworkParamsState params = mock(NetworkParamsState.class);
	private final MempoolTxMapper mapper = new MempoolTxMapper(
			chainHeadStateCache, mempoolStore, mempoolProperties, feeRecommendationService);

	@BeforeEach
	void setUp() {
		WorldState worldState = mock(WorldState.class);
		when(chainHeadStateCache.getHeadSnapshot()).thenReturn(new HeadStateSnapshot(head, worldState));
		when(worldState.getParams()).thenReturn(params);
		when(params.getMinTxBaseFee()).thenReturn(Wei.valueOf(200));
		when(params.getMinTxByteFee()).thenReturn(Wei.valueOf(5));
		mempoolProperties.setMinAcceptableFeeWei(BigInteger.TEN);
		mempoolProperties.setMaxRecommendedFeeWei(BigInteger.valueOf(1_000_000));
		when(mempoolStore.getCount()).thenReturn(3L);
	}

	@Test
	void mapsRecentBlockOracleTotalsAndPreservesMempoolSize() {
		when(feeRecommendationService.recommend(head, Wei.valueOf(950), 150, MAXIMUM_REPRESENTABLE_TOTAL))
				.thenReturn(new FeeTotals(
						Wei.valueOf(2_000), Wei.valueOf(2_900), Wei.valueOf(14), Wei.valueOf(20)));

		MempoolTxMapper.RecommendedFeesAtHead atHead = mapper.mapRecommendedFeesAtHead();
		RecommendedFeesDtoV1 result = atHead.fees();

		assertThat(atHead.head()).isSameAs(head);
		assertThat(result.getSlow().getTotalForAverageTx()).isEqualTo(Wei.valueOf(950));
		assertThat(result.getSlow().getMinimumTotalFee()).isEqualTo(Wei.valueOf(10));
		assertThat(result.getStandard().getFeePerByte()).isEqualTo(Wei.valueOf(5));
		assertThat(result.getStandard().getMiningFeePerByte()).isEqualTo(Wei.valueOf(14));
		assertThat(result.getStandard().getTotalForAverageTx()).isEqualTo(Wei.valueOf(2_100));
		assertThat(result.getStandard().getMinimumTotalFee()).isEqualTo(Wei.valueOf(10));
		assertThat(result.getFast().getFeePerByte()).isEqualTo(Wei.valueOf(6));
		assertThat(result.getFast().getMiningFeePerByte()).isEqualTo(Wei.valueOf(20));
		assertThat(result.getFast().getTotalForAverageTx()).isEqualTo(Wei.valueOf(3_000));
		assertThat(result.getFast().getMinimumTotalFee()).isEqualTo(Wei.valueOf(10));
		assertThat(result.getMempoolSize()).isEqualTo(3);
		assertThat(exactFee(result.getStandard(), 1_000)).isEqualTo(Wei.valueOf(14_000));
	}

	@Test
	void idleChainKeepsStandardAtMinimumAndAddsOnlySmallFastPremium() {
		when(feeRecommendationService.recommend(head, Wei.valueOf(950), 150, MAXIMUM_REPRESENTABLE_TOTAL))
				.thenReturn(new FeeTotals(Wei.valueOf(950), Wei.valueOf(950), Wei.ZERO, Wei.ZERO));

		RecommendedFeesDtoV1 result = mapper.mapRecommendedFees();

		assertThat(result.getSlow().getTotalForAverageTx()).isEqualTo(Wei.valueOf(950));
		assertThat(result.getSlow().getMiningFeePerByte()).isEqualTo(Wei.ZERO);
		assertThat(result.getStandard().getTotalForAverageTx()).isEqualTo(Wei.valueOf(950));
		assertThat(result.getStandard().getMiningFeePerByte()).isEqualTo(Wei.ZERO);
		assertThat(result.getFast().getTotalForAverageTx()).isEqualTo(Wei.valueOf(1_100));
	}

	@Test
	void nodeMinimumProducesMathematicallyConsistentFeeLevel() {
		mempoolProperties.setMinAcceptableFeeWei(BigInteger.valueOf(1_000));
		when(feeRecommendationService.recommend(head, Wei.valueOf(1_000), 150, MAXIMUM_REPRESENTABLE_TOTAL))
				.thenReturn(new FeeTotals(Wei.valueOf(1_000), Wei.valueOf(1_000), Wei.ZERO, Wei.ZERO));

		RecommendedFeesDtoV1 result = mapper.mapRecommendedFees();

		assertThat(result.getSlow().getFeePerByte()).isEqualTo(Wei.valueOf(5));
		assertThat(result.getSlow().getMinimumTotalFee()).isEqualTo(Wei.valueOf(1_000));
		assertThat(result.getSlow().getTotalForAverageTx()).isEqualTo(Wei.valueOf(1_000));
		assertThat(result.getStandard().getTotalForAverageTx()).isEqualTo(Wei.valueOf(1_000));
		assertThat(exactFee(result.getSlow(), 1_000)).isEqualTo(Wei.valueOf(5_200));
	}

	@Test
	void preservesObservedMiningDensityBelowTheAverageNetworkMinimum() {
		when(feeRecommendationService.recommend(head, Wei.valueOf(950), 150, MAXIMUM_REPRESENTABLE_TOTAL))
				.thenReturn(new FeeTotals(Wei.valueOf(900), Wei.valueOf(900), Wei.valueOf(6), Wei.valueOf(6)));

		RecommendedFeesDtoV1 result = mapper.mapRecommendedFees();

		assertThat(result.getStandard().getTotalForAverageTx()).isEqualTo(Wei.valueOf(950));
		assertThat(result.getStandard().getMiningFeePerByte()).isEqualTo(Wei.valueOf(6));
		assertThat(exactFee(result.getStandard(), 1_000)).isEqualTo(Wei.valueOf(6_000));
	}

	@Test
	void configuredCapLimitsRepresentableStandardAndFastTotals() {
		mempoolProperties.setMaxRecommendedFeeWei(BigInteger.valueOf(2_000));
		when(feeRecommendationService.recommend(head, Wei.valueOf(950), 150, Wei.valueOf(2_000)))
				.thenReturn(new FeeTotals(
						Wei.valueOf(2_000), Wei.valueOf(2_000), Wei.valueOf(100), Wei.valueOf(100)));

		RecommendedFeesDtoV1 result = mapper.mapRecommendedFees();

		assertThat(result.getStandard().getTotalForAverageTx()).isEqualTo(Wei.valueOf(1_950));
		assertThat(result.getFast().getTotalForAverageTx()).isEqualTo(Wei.valueOf(1_950));
	}

	@Test
	void retriesWithANewHeadSnapshotWhenHistoryChanges() {
		when(feeRecommendationService.recommend(head, Wei.valueOf(950), 150, MAXIMUM_REPRESENTABLE_TOTAL))
				.thenThrow(new FeeHistoryUnavailableException("head changed"))
				.thenReturn(new FeeTotals(Wei.valueOf(950), Wei.valueOf(950), Wei.ZERO, Wei.ZERO));

		RecommendedFeesDtoV1 result = mapper.mapRecommendedFees();

		assertThat(result.getStandard().getTotalForAverageTx()).isEqualTo(Wei.valueOf(950));
		verify(chainHeadStateCache, times(2)).getHeadSnapshot();
	}

	@Test
	void rejectsAnUnrepresentableNetworkMinimumBeforeCallingTheOracle() {
		when(params.getMinTxBaseFee()).thenReturn(Wei.valueOf(UINT256_MAX));
		when(params.getMinTxByteFee()).thenReturn(Wei.valueOf(1));

		assertThatThrownBy(mapper::mapRecommendedFees)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Network minimum fee")
				.hasMessageContaining("uint256");
	}

	@Test
	void capsTheFastPercentageInBigIntegerBeforeConvertingToWei() {
		BigInteger maximumByteFee = UINT256_MAX.divide(BigInteger.valueOf(MempoolTxMapper.AVERAGE_TX_SIZE));
		BigInteger networkTotal = maximumByteFee.multiply(BigInteger.valueOf(MempoolTxMapper.AVERAGE_TX_SIZE));
		when(params.getMinTxBaseFee()).thenReturn(Wei.ZERO);
		when(params.getMinTxByteFee()).thenReturn(Wei.valueOf(maximumByteFee));
		mempoolProperties.setMaxRecommendedFeeWei(BigInteger.ONE);
		when(feeRecommendationService.recommend(
				head, Wei.valueOf(networkTotal), MempoolTxMapper.AVERAGE_TX_SIZE, Wei.valueOf(networkTotal)))
				.thenReturn(new FeeTotals(
						Wei.valueOf(networkTotal), Wei.valueOf(networkTotal), Wei.ZERO, Wei.ZERO));

		RecommendedFeesDtoV1 result = mapper.mapRecommendedFees();

		assertThat(result.getFast().getFeePerByte()).isEqualTo(Wei.valueOf(maximumByteFee));
		assertThat(result.getFast().getTotalForAverageTx()).isEqualTo(Wei.valueOf(networkTotal));
	}

	@Test
	void rejectsAnUnrepresentableConfiguredMaximum() {
		mempoolProperties.setMaxRecommendedFeeWei(UINT256_MAX.add(BigInteger.ONE));

		assertThatThrownBy(mapper::mapRecommendedFees)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Maximum recommended fee")
				.hasMessageContaining("uint256");
	}

	private Wei exactFee(FeeLevel level, long size) {
		Wei networkFee = level.getBaseFee().add(level.getFeePerByte().multiply(size));
		Wei miningFee = level.getMiningFeePerByte().multiply(size);
		return max(level.getMinimumTotalFee(), max(networkFee, miningFee));
	}

	private Wei max(Wei first, Wei second) {
		return first.compareTo(second) >= 0 ? first : second;
	}
}
