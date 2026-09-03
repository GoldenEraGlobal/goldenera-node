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
package global.goldenera.node.core.mempool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.FeeRecommendationService.FeeHistoryUnavailableException;
import global.goldenera.node.core.mempool.FeeRecommendationService.FeeTotals;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

class FeeRecommendationServiceTest {

	private static final long TARGET_SIZE = 150;
	private static final Wei MINIMUM_TOTAL_FEE = Wei.valueOf(950);
	private static final Wei MAXIMUM_TOTAL_FEE = Wei.valueOf(1_000_000);
	private static final Address MINER = MempoolTestFixtures.address(90);
	private static final Address SENDER = MempoolTestFixtures.address(91);

	private final ChainQuery chainQuery = mock(ChainQuery.class);
	private final FeeRecommendationService service = new FeeRecommendationService(chainQuery);

	@Test
	void genesisUsesMinimum() {
		StoredBlock genesis = block(0, List.of());
		stubCanonicalHead(genesis);

		assertThat(recommend(genesis))
				.isEqualTo(new FeeTotals(MINIMUM_TOTAL_FEE, MINIMUM_TOTAL_FEE, Wei.ZERO, Wei.ZERO));
	}

	@Test
	void oneExpensiveTransactionAmongEmptyBlocksCannotMoveRecommendation() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 19; height++) {
			blocks.add(block(height, List.of()));
		}
		blocks.add(block(20, List.of(transaction(SENDER, 100, 10_000))));
		stubHistory(blocks);

		assertThat(recommend(blocks.getLast()))
				.isEqualTo(new FeeTotals(MINIMUM_TOTAL_FEE, MINIMUM_TOTAL_FEE, Wei.ZERO, Wei.ZERO));
	}

	@Test
	void youngChainIsPaddedWithMinimumInsteadOfTrustingOneOffer() {
		StoredBlock head = block(1, List.of(transaction(SENDER, 100, 10_000)));
		stubHistory(List.of(head));

		assertThat(recommend(head))
				.isEqualTo(new FeeTotals(MINIMUM_TOTAL_FEE, MINIMUM_TOTAL_FEE, Wei.ZERO, Wei.ZERO));
	}

	@Test
	void usesRecentBlockPercentilesOfMinerFeeDensity() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			long density = height + 9L;
			blocks.add(block(height, List.of(transaction(SENDER, 100, density * 100))));
		}
		stubHistory(blocks);

		FeeTotals result = recommend(blocks.getLast());

		assertThat(result.standardTotalFee()).isEqualTo(Wei.valueOf(3_150));
		assertThat(result.fastTotalFee()).isEqualTo(Wei.valueOf(4_050));
		assertThat(result.standardMiningFeePerByte()).isEqualTo(Wei.valueOf(21));
		assertThat(result.fastMiningFeePerByte()).isEqualTo(Wei.valueOf(27));
	}

	@Test
	void samplesOnlyThreeLowestDensitiesAndExcludesMinerTransactions() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			blocks.add(block(height, List.of(
					transaction(SENDER, 100, 4_000),
					transaction(SENDER, 100, 1_000),
					transaction(SENDER, 100, 3_000),
					transaction(SENDER, 100, 2_000),
					transaction(MINER, 100, 100_000))));
		}
		stubHistory(blocks);

		FeeTotals result = recommend(blocks.getLast());

		assertThat(result.standardTotalFee()).isEqualTo(Wei.valueOf(3_000));
		assertThat(result.fastTotalFee()).isEqualTo(Wei.valueOf(4_500));
		assertThat(result.standardMiningFeePerByte()).isEqualTo(Wei.valueOf(20));
		assertThat(result.fastMiningFeePerByte()).isEqualTo(Wei.valueOf(30));
	}

	@Test
	void usesSameTotalFeeDensityAsMinerAcrossTransactionSizes() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			blocks.add(block(height, List.of(
					transaction(SENDER, 100, 700),
					transaction(SENDER, 300, 2_000))));
		}
		stubHistory(blocks);

		FeeTotals result = recommend(blocks.getLast());

		assertThat(result.standardTotalFee()).isEqualTo(Wei.valueOf(1_050));
		assertThat(result.fastTotalFee()).isEqualTo(Wei.valueOf(1_050));
		assertThat(result.standardMiningFeePerByte()).isEqualTo(Wei.valueOf(7));
		assertThat(result.fastMiningFeePerByte()).isEqualTo(Wei.valueOf(7));
	}

	@Test
	void preservesObservedDensityBelowTheSyntheticAverageMinimum() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			blocks.add(block(height, List.of(transaction(SENDER, 1_000, 6_000))));
		}
		stubHistory(blocks);

		FeeTotals result = recommend(blocks.getLast());

		assertThat(result.standardTotalFee()).isEqualTo(Wei.valueOf(900));
		assertThat(result.fastTotalFee()).isEqualTo(Wei.valueOf(900));
		assertThat(result.standardMiningFeePerByte()).isEqualTo(Wei.valueOf(6));
		assertThat(result.fastMiningFeePerByte()).isEqualTo(Wei.valueOf(6));
	}

	@Test
	void equalDensityUsesObservedDemandOnlyAfterItCrossesThePercentile() {
		List<StoredBlock> mostlyEmpty = new ArrayList<>();
		for (int height = 1; height <= 12; height++) {
			mostlyEmpty.add(block(height, List.of()));
		}
		for (int height = 13; height <= 20; height++) {
			mostlyEmpty.add(block(height, List.of(transaction(SENDER, 150, 950))));
		}
		stubHistory(mostlyEmpty);
		assertThat(recommend(mostlyEmpty.getLast()).standardMiningFeePerByte()).isEqualTo(Wei.ZERO);

		FeeRecommendationService demandService = new FeeRecommendationService(chainQuery);
		List<StoredBlock> mostlyObserved = new ArrayList<>();
		for (int height = 1; height <= 8; height++) {
			mostlyObserved.add(block(height, List.of()));
		}
		for (int height = 9; height <= 20; height++) {
			mostlyObserved.add(block(height, List.of(transaction(SENDER, 150, 950))));
		}
		stubHistory(mostlyObserved);

		assertThat(demandService.recommend(
				mostlyObserved.getLast(), MINIMUM_TOTAL_FEE, TARGET_SIZE, MAXIMUM_TOTAL_FEE)
				.standardMiningFeePerByte()).isEqualTo(Wei.valueOf(7));
	}

	@Test
	void capsExtremeHistoricalFeesBeforeConvertingToWei() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			blocks.add(block(height, List.of(
					transaction(SENDER, 1, BigInteger.ONE.shiftLeft(255)))));
		}
		stubHistory(blocks);

		FeeTotals result = service.recommend(
				blocks.getLast(), MINIMUM_TOTAL_FEE, TARGET_SIZE, Wei.valueOf(5_000));

		assertThat(result).isEqualTo(new FeeTotals(
				Wei.valueOf(5_000), Wei.valueOf(5_000), Wei.valueOf(33), Wei.valueOf(33)));
	}

	@Test
	void roundsNonIntegralDensityOnlyWhenConvertingToTargetSize() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			blocks.add(block(height, List.of(transaction(SENDER, 100, 701))));
		}
		stubHistory(blocks);

		FeeTotals result = recommend(blocks.getLast());

		assertThat(result.standardTotalFee()).isEqualTo(Wei.valueOf(1_052));
		assertThat(result.fastTotalFee()).isEqualTo(Wei.valueOf(1_052));
		assertThat(result.standardMiningFeePerByte()).isEqualTo(Wei.valueOf(8));
		assertThat(result.fastMiningFeePerByte()).isEqualTo(Wei.valueOf(8));
	}

	@Test
	void rejectsIncompleteHistoryAndDoesNotCacheIt() {
		List<StoredBlock> incomplete = new ArrayList<>();
		for (int height = 2; height <= 20; height++) {
			incomplete.add(block(height, List.of()));
		}
		StoredBlock head = incomplete.getLast();
		stubCanonicalHead(head);
		when(chainQuery.findStoredBlockHeadersByHeightRange(1, 20)).thenReturn(incomplete);

		assertThatThrownBy(() -> recommend(head)).isInstanceOf(FeeHistoryUnavailableException.class);
		assertThatThrownBy(() -> recommend(head)).isInstanceOf(FeeHistoryUnavailableException.class);
		verify(chainQuery, times(2)).findStoredBlockHeadersByHeightRange(1, 20);
	}

	@Test
	void rejectsHeadChangeDuringHistoryRead() {
		StoredBlock head = block(1, List.of());
		Hash expectedHead = head.getHash();
		when(chainQuery.getLatestBlockHash()).thenReturn(Optional.of(expectedHead), Optional.of(hash(2)));
		when(chainQuery.findStoredBlockHeadersByHeightRange(1, 1)).thenReturn(List.of(head));

		assertThatThrownBy(() -> recommend(head)).isInstanceOf(FeeHistoryUnavailableException.class);
	}

	@Test
	void cachesRecommendationForCanonicalHead() {
		StoredBlock head = block(1, List.of(transaction(SENDER, 100, 1_000)));
		stubHistory(List.of(head));

		recommend(head);
		recommend(head);

		verify(chainQuery).findStoredBlockHeadersByHeightRange(1, 1);
	}

	@Test
	void rejectsMissingTransactionHashMetadata() {
		List<StoredBlock> blocks = historyWithOneTransactionPerBlock();
		StoredBlock malformed = blocks.getFirst();
		when(malformed.getTransactionHashes()).thenReturn(new Hash[] { null });
		stubHistory(blocks);

		assertThatThrownBy(() -> recommend(blocks.getLast()))
				.isInstanceOf(FeeHistoryUnavailableException.class);
	}

	@Test
	void rejectsMissingTransactionSizeMetadata() {
		List<StoredBlock> blocks = historyWithOneTransactionPerBlock();
		StoredBlock malformed = blocks.getFirst();
		when(malformed.getTransactionSizes()).thenReturn(new int[] { -1 });
		stubHistory(blocks);

		assertThatThrownBy(() -> recommend(blocks.getLast()))
				.isInstanceOf(FeeHistoryUnavailableException.class);
	}

	@Test
	void rejectsMissingTransactionSenderMetadata() {
		List<StoredBlock> blocks = historyWithOneTransactionPerBlock();
		StoredBlock malformed = blocks.getFirst();
		when(malformed.getTransactionSenders()).thenReturn(new Address[] { null });
		stubHistory(blocks);

		assertThatThrownBy(() -> recommend(blocks.getLast()))
				.isInstanceOf(FeeHistoryUnavailableException.class);
	}

	@Test
	void rejectsNullTransactionBody() {
		StoredBlock head = block(1, List.of());
		Block body = head.getBlock();
		when(body.getTxs()).thenReturn(null);
		stubHistory(List.of(head));

		assertThatThrownBy(() -> recommend(head))
				.isInstanceOf(FeeHistoryUnavailableException.class);
	}

	@Test
	void rejectsEmptyBodyWithNonEmptyTransactionMetadata() {
		StoredBlock head = block(1, List.of());
		when(head.getTransactionHashes()).thenReturn(new Hash[] { hash(100) });
		stubHistory(List.of(head));

		assertThatThrownBy(() -> recommend(head))
				.isInstanceOf(FeeHistoryUnavailableException.class);
	}

	@Test
	void usesValidatedStoredMetadataWithoutRecomputingTransactionIndexes() {
		List<StoredBlock> blocks = historyWithOneTransactionPerBlock();
		stubHistory(blocks);

		recommend(blocks.getLast());

		for (StoredBlock block : blocks) {
			Tx transaction = block.getBlock().getTxs().getFirst();
			verify(transaction, never()).getHash();
			verify(transaction, never()).getSize();
			verify(transaction, never()).getSender();
		}
	}

	@Test
	void reusesSamplesFromOverlappingHistoryAfterNewHead() {
		List<StoredBlock> firstHistory = new ArrayList<>();
		List<Tx> historicalTransactions = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			Tx tx = mock(Tx.class);
			when(tx.getFee()).thenReturn(Wei.valueOf(1_000 + height));
			historicalTransactions.add(tx);
			firstHistory.add(block(height, List.of(new TransactionSpec(tx, SENDER, 100))));
		}
		stubHistory(firstHistory);
		recommend(firstHistory.getLast());

		StoredBlock nextHead = block(21, List.of(transaction(SENDER, 100, 1_100)));
		List<StoredBlock> nextHistory = new ArrayList<>(firstHistory.subList(1, firstHistory.size()));
		nextHistory.add(nextHead);
		stubHistory(nextHistory);
		recommend(nextHead);

		for (Tx historicalTransaction : historicalTransactions) {
			verify(historicalTransaction).getFee();
		}
		Hash firstBlockHash = firstHistory.getFirst().getHash();
		Hash nextHeadHash = nextHead.getHash();
		verify(chainQuery).getStoredBlockByHash(firstBlockHash);
		verify(chainQuery, never()).getStoredBlockByHash(nextHeadHash);
	}

	@Test
	void startupWarmupPreloadsBodiesBeforeTheFirstRecommendation() {
		List<StoredBlock> blocks = historyWithOneTransactionPerBlock();
		StoredBlock head = blocks.getLast();
		stubHistory(blocks);
		when(chainQuery.getLatestStoredBlock()).thenReturn(Optional.of(head));

		service.warmRecentBlockSamples();
		recommend(head);

		Hash firstBlockHash = blocks.getFirst().getHash();
		verify(chainQuery).getStoredBlockByHash(firstBlockHash);
	}

	@Test
	void startupWarmupAndFirstRequestShareOneBlockSampleLoad() throws Exception {
		StoredBlock historical = block(1, List.of(transaction(SENDER, 100, 1_000)));
		StoredBlock head = block(2, List.of(transaction(SENDER, 100, 1_000)));
		List<StoredBlock> blocks = List.of(historical, head);
		stubCanonicalHead(head);
		when(chainQuery.getLatestStoredBlock()).thenReturn(Optional.of(head));
		when(chainQuery.findStoredBlockHeadersByHeightRange(1, 2)).thenReturn(blocks);
		CountDownLatch loadStarted = new CountDownLatch(1);
		CountDownLatch allowLoad = new CountDownLatch(1);
		when(chainQuery.getStoredBlockByHash(historical.getHash())).thenAnswer(ignored -> {
			loadStarted.countDown();
			if (!allowLoad.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting to finish the sample load");
			}
			return Optional.of(historical);
		});

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> warmup = executor.submit(service::warmRecentBlockSamples);
			assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
			Future<FeeTotals> request = executor.submit(() -> recommend(head));
			allowLoad.countDown();
			warmup.get(5, TimeUnit.SECONDS);
			request.get(5, TimeUnit.SECONDS);
		} finally {
			allowLoad.countDown();
			executor.shutdownNow();
		}

		verify(chainQuery, times(1)).getStoredBlockByHash(historical.getHash());
	}

	private FeeTotals recommend(StoredBlock head) {
		return service.recommend(head, MINIMUM_TOTAL_FEE, TARGET_SIZE, MAXIMUM_TOTAL_FEE);
	}

	private void stubHistory(List<StoredBlock> blocks) {
		StoredBlock head = blocks.getLast();
		stubCanonicalHead(head);
		when(chainQuery.findStoredBlockHeadersByHeightRange(Math.max(1, head.getHeight() - 19), head.getHeight()))
				.thenReturn(blocks);
		for (StoredBlock block : blocks) {
			if (!block.getHash().equals(head.getHash())) {
				Hash blockHash = block.getHash();
				when(chainQuery.getStoredBlockByHash(blockHash)).thenReturn(Optional.of(block));
			}
		}
	}

	private void stubCanonicalHead(StoredBlock head) {
		Hash expectedHead = head.getHash();
		when(chainQuery.getLatestBlockHash()).thenReturn(Optional.of(expectedHead));
	}

	private StoredBlock block(long height, List<TransactionSpec> transactions) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getCoinbase()).thenReturn(MINER);
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		when(block.getTxs()).thenReturn(transactions.stream().map(TransactionSpec::tx).toList());
		StoredBlock storedBlock = mock(StoredBlock.class);
		when(storedBlock.getHeight()).thenReturn(height);
		when(storedBlock.getHash()).thenReturn(hash(height));
		when(storedBlock.getBlock()).thenReturn(block);
		when(storedBlock.getTxCount()).thenReturn(transactions.size());
		Hash[] hashes = new Hash[transactions.size()];
		int[] sizes = new int[transactions.size()];
		Address[] senders = new Address[transactions.size()];
		for (int index = 0; index < transactions.size(); index++) {
			TransactionSpec transaction = transactions.get(index);
			hashes[index] = hash((height + 1L) * 1_000L + index);
			sizes[index] = transaction.size();
			senders[index] = transaction.sender();
			when(storedBlock.getTransactionHashByIndex(index)).thenReturn(hashes[index]);
			when(storedBlock.getTransactionSenderByIndex(index)).thenReturn(transaction.sender());
			when(storedBlock.getTransactionSizeByIndex(index)).thenReturn(transaction.size());
		}
		when(storedBlock.getTransactionHashes()).thenReturn(hashes);
		when(storedBlock.getTransactionSizes()).thenReturn(sizes);
		when(storedBlock.getTransactionSenders()).thenReturn(senders);
		return storedBlock;
	}

	private TransactionSpec transaction(Address sender, int size, long totalFee) {
		return transaction(sender, size, BigInteger.valueOf(totalFee));
	}

	private TransactionSpec transaction(Address sender, int size, BigInteger totalFee) {
		Tx tx = mock(Tx.class);
		when(tx.getFee()).thenReturn(Wei.valueOf(totalFee));
		return new TransactionSpec(tx, sender, size);
	}

	private List<StoredBlock> historyWithOneTransactionPerBlock() {
		List<StoredBlock> blocks = new ArrayList<>();
		for (int height = 1; height <= 20; height++) {
			blocks.add(block(height, List.of(transaction(SENDER, 100, 1_000))));
		}
		return blocks;
	}

	private Hash hash(long height) {
		return Hash.fromHexString(String.format("0x%064x", height));
	}

	private record TransactionSpec(Tx tx, Address sender, int size) {
	}
}
