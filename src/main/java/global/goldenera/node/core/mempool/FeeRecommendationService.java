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

import static global.goldenera.node.core.config.CoreAsyncConfig.CORE_TASK_EXECUTOR;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recent-block fee oracle adapted from the approach used by Ethereum execution
 * clients. A few low-priced transactions are sampled from each recent block and
 * aggregated into stable percentiles. Pending transactions do not directly move
 * the recommendation, so a small or adversarial mempool cannot dictate fees.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeeRecommendationService {

	static final int HISTORY_BLOCK_COUNT = 20;
	static final int SAMPLES_PER_BLOCK = 3;
	static final int STANDARD_PERCENTILE = 60;
	static final int FAST_PERCENTILE = 90;

	private final ChainQuery chainQuery;
	private final AtomicReference<CachedFeeTotals> cachedTotals = new AtomicReference<>();
	private final Cache<Hash, List<FeeDensity>> blockSamples = Caffeine.newBuilder()
			.maximumSize(HISTORY_BLOCK_COUNT * 3L)
			.build();
	private final Object refreshLock = new Object();

	@Async(CORE_TASK_EXECUTOR)
	@EventListener(ApplicationReadyEvent.class)
	public void warmRecentBlockSamples() {
		try {
			StoredBlock head = chainQuery.getLatestStoredBlock().orElse(null);
			if (head == null || head.getHeight() == 0) {
				return;
			}
			requireCanonicalHead(head.getHash());
			long firstHeight = Math.max(1L, head.getHeight() - HISTORY_BLOCK_COUNT + 1L);
			List<StoredBlock> headers = chainQuery.findStoredBlockHeadersByHeightRange(firstHeight, head.getHeight());
			for (StoredBlock header : requireCompleteHistory(headers, firstHeight, head)) {
				sampleBlock(header, head);
			}
			requireCanonicalHead(head.getHash());
		} catch (RuntimeException failure) {
			log.warn("Could not warm recent-block fee samples; the oracle will retry on demand", failure);
		}
	}

	public FeeTotals recommend(StoredBlock head, Wei minimumTotalFee, long targetSize, Wei maximumTotalFee) {
		if (targetSize <= 0) {
			throw new IllegalArgumentException("Target transaction size must be positive");
		}
		Wei effectiveMaximum = max(maximumTotalFee, minimumTotalFee);

		CacheKey key = new CacheKey(head.getHash(), minimumTotalFee, targetSize, effectiveMaximum);
		CachedFeeTotals current = cachedTotals.get();
		if (current != null && current.key().equals(key)) {
			return current.totals();
		}

		synchronized (refreshLock) {
			current = cachedTotals.get();
			if (current != null && current.key().equals(key)) {
				return current.totals();
			}

			requireCanonicalHead(head.getHash());
			FeeTotals totals = calculateTotals(head, minimumTotalFee, targetSize, effectiveMaximum);
			requireCanonicalHead(head.getHash());
			cachedTotals.set(new CachedFeeTotals(key, totals));
			return totals;
		}
	}

	private FeeTotals calculateTotals(StoredBlock head, Wei minimumTotalFee, long targetSize, Wei maximumTotalFee) {
		long headHeight = head.getHeight();
		if (headHeight == 0) {
			return FeeTotals.minimum(minimumTotalFee);
		}
		long firstHeight = Math.max(1L, headHeight - HISTORY_BLOCK_COUNT + 1L);
		List<StoredBlock> headers = chainQuery.findStoredBlockHeadersByHeightRange(firstHeight, headHeight);
		List<StoredBlock> completeHistory = requireCompleteHistory(headers, firstHeight, head);

		FeeDensity minimumDensity = FeeDensity.syntheticMinimum(minimumTotalFee.toBigInteger(), targetSize);
		List<FeeDensity> samples = new ArrayList<>(HISTORY_BLOCK_COUNT * SAMPLES_PER_BLOCK);
		for (int missingBlock = completeHistory.size(); missingBlock < HISTORY_BLOCK_COUNT; missingBlock++) {
			// Pad a young chain with the configured starting price, just as an
			// Ethereum oracle starts from a configured price before it has history.
			samples.add(minimumDensity);
		}
		for (StoredBlock header : completeHistory) {
			List<FeeDensity> blockSamples = sampleBlock(header, head);
			if (blockSamples.isEmpty()) {
				// Empty blocks represent available capacity, so use the deterministic
				// protocol/node minimum instead of carrying an old congestion price.
				samples.add(minimumDensity);
			} else {
				samples.addAll(blockSamples);
			}
		}

		samples.sort(Comparator.naturalOrder());
		FeeDensity standard = percentile(samples, STANDARD_PERCENTILE);
		FeeDensity fast = percentile(samples, FAST_PERCENTILE);
		return new FeeTotals(
				toTargetTotal(standard, targetSize, maximumTotalFee),
				toTargetTotal(fast, targetSize, maximumTotalFee),
				toMiningFeePerByte(standard, targetSize, maximumTotalFee),
				toMiningFeePerByte(fast, targetSize, maximumTotalFee));
	}

	private List<StoredBlock> requireCompleteHistory(List<StoredBlock> blocks, long firstHeight, StoredBlock head) {
		long expectedCount = head.getHeight() - firstHeight + 1L;
		if (blocks.size() != expectedCount) {
			throw new FeeHistoryUnavailableException("Canonical fee history is incomplete");
		}

		Map<Long, StoredBlock> blocksByHeight = new HashMap<>(blocks.size());
		for (StoredBlock block : blocks) {
			if (blocksByHeight.put(block.getHeight(), block) != null) {
				throw new FeeHistoryUnavailableException("Canonical fee history contains duplicate heights");
			}
		}

		List<StoredBlock> ordered = new ArrayList<>(blocks.size());
		for (long height = firstHeight; height <= head.getHeight(); height++) {
			StoredBlock block = blocksByHeight.get(height);
			if (block == null) {
				throw new FeeHistoryUnavailableException("Canonical fee history is missing block " + height);
			}
			ordered.add(block);
		}
		if (!ordered.getLast().getHash().equals(head.getHash())) {
			throw new FeeHistoryUnavailableException("Canonical fee history does not end at the requested head");
		}
		return ordered;
	}

	private List<FeeDensity> sampleBlock(StoredBlock header, StoredBlock head) {
		return blockSamples.get(header.getHash(), ignored -> loadBlockSamples(header, head));
	}

	private List<FeeDensity> loadBlockSamples(StoredBlock header, StoredBlock head) {
		StoredBlock fullBlock = header.getHash().equals(head.getHash())
				? head
				: chainQuery.getStoredBlockByHash(header.getHash())
						.orElseThrow(() -> new FeeHistoryUnavailableException(
								"Canonical block body is unavailable at height " + header.getHeight()));
		if (fullBlock.isPartial()
				|| fullBlock.getHeight() != header.getHeight()
				|| !fullBlock.getHash().equals(header.getHash())) {
			throw new FeeHistoryUnavailableException(
					"Canonical block body does not match header at height " + header.getHeight());
		}

		return calculateBlockSamples(fullBlock);
	}

	private List<FeeDensity> calculateBlockSamples(StoredBlock storedBlock) {
		List<Tx> transactions = storedBlock.getBlock().getTxs();
		if (transactions == null) {
			throw new FeeHistoryUnavailableException("Canonical block body is missing transactions");
		}
		Hash[] hashes = storedBlock.getTransactionHashes();
		int[] sizes = storedBlock.getTransactionSizes();
		Address[] senders = storedBlock.getTransactionSenders();
		if (storedBlock.getTxCount() != transactions.size()
				|| hashes == null || hashes.length != transactions.size()
				|| sizes == null || sizes.length != transactions.size()
				|| senders == null || senders.length != transactions.size()) {
			throw new FeeHistoryUnavailableException("Stored transaction index is incomplete");
		}
		if (transactions.isEmpty()) {
			return List.of();
		}

		Address coinbase = storedBlock.getBlock().getHeader().getCoinbase();
		PriorityQueue<FeeDensity> lowest = new PriorityQueue<>(SAMPLES_PER_BLOCK, Comparator.reverseOrder());
		for (int index = 0; index < transactions.size(); index++) {
			Tx transaction = transactions.get(index);
			Wei fee = transaction == null ? null : transaction.getFee();
			int size = sizes[index];
			Address sender = senders[index];
			if (fee == null
					|| hashes[index] == null || size <= 0 || sender == null) {
				throw new FeeHistoryUnavailableException(
						"Stored transaction index is invalid at block " + storedBlock.getHeight()
								+ ", transaction " + index);
			}
			if (sender.equals(coinbase)) {
				continue;
			}
			FeeDensity density = FeeDensity.observed(fee.toBigInteger(), size);
			if (lowest.size() < SAMPLES_PER_BLOCK) {
				lowest.add(density);
			} else if (density.compareTo(lowest.element()) < 0) {
				lowest.remove();
				lowest.add(density);
			}
		}
		return List.copyOf(lowest);
	}

	private Wei toTargetTotal(FeeDensity density, long targetSize, Wei maximumTotalFee) {
		BigInteger total = divideCeiling(
				density.totalFee().multiply(BigInteger.valueOf(targetSize)),
				BigInteger.valueOf(density.size()));
		return total.compareTo(maximumTotalFee.toBigInteger()) > 0 ? maximumTotalFee : Wei.valueOf(total);
	}

	private Wei toMiningFeePerByte(FeeDensity density, long targetSize, Wei maximumTotalFee) {
		if (density.syntheticMinimum()) {
			return Wei.ZERO;
		}
		BigInteger feePerByte = divideCeiling(density.totalFee(), BigInteger.valueOf(density.size()));
		BigInteger maximumFeePerByte = maximumTotalFee.toBigInteger().divide(BigInteger.valueOf(targetSize));
		return Wei.valueOf(feePerByte.min(maximumFeePerByte));
	}

	private FeeDensity percentile(List<FeeDensity> sortedSamples, int percentile) {
		if (sortedSamples.isEmpty()) {
			throw new IllegalArgumentException("Fee samples are required");
		}
		int index = (sortedSamples.size() - 1) * percentile / 100;
		return sortedSamples.get(index);
	}

	private BigInteger divideCeiling(BigInteger dividend, BigInteger divisor) {
		BigInteger[] result = dividend.divideAndRemainder(divisor);
		return result[1].signum() == 0 ? result[0] : result[0].add(BigInteger.ONE);
	}

	private void requireCanonicalHead(Hash expectedHead) {
		Hash actualHead = chainQuery.getLatestBlockHash()
				.orElseThrow(() -> new FeeHistoryUnavailableException("Canonical head is unavailable"));
		if (!actualHead.equals(expectedHead)) {
			throw new FeeHistoryUnavailableException("Canonical head changed while calculating fees");
		}
	}

	private Wei max(Wei first, Wei second) {
		return first.compareTo(second) >= 0 ? first : second;
	}

	public record FeeTotals(
			Wei standardTotalFee,
			Wei fastTotalFee,
			Wei standardMiningFeePerByte,
			Wei fastMiningFeePerByte) {
		private static FeeTotals minimum(Wei minimumTotalFee) {
			return new FeeTotals(minimumTotalFee, minimumTotalFee, Wei.ZERO, Wei.ZERO);
		}
	}

	private record FeeDensity(BigInteger totalFee, long size, boolean syntheticMinimum)
			implements Comparable<FeeDensity> {
		private static FeeDensity observed(BigInteger totalFee, long size) {
			return new FeeDensity(totalFee, size, false);
		}

		private static FeeDensity syntheticMinimum(BigInteger totalFee, long size) {
			return new FeeDensity(totalFee, size, true);
		}

		@Override
		public int compareTo(FeeDensity other) {
			int densityComparison = totalFee.multiply(BigInteger.valueOf(other.size))
					.compareTo(other.totalFee.multiply(BigInteger.valueOf(size)));
			if (densityComparison != 0 || syntheticMinimum == other.syntheticMinimum) {
				return densityComparison;
			}
			return syntheticMinimum ? -1 : 1;
		}
	}

	private record CacheKey(Hash headHash, Wei minimumTotalFee, long targetSize, Wei maximumTotalFee) {
	}

	private record CachedFeeTotals(CacheKey key, FeeTotals totals) {
	}

	public static class FeeHistoryUnavailableException extends RuntimeException {
		public FeeHistoryUnavailableException(String message) {
			super(message);
		}
	}
}
