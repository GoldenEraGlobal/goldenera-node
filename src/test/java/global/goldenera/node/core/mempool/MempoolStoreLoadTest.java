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

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.address;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStoreLoadTest {

	@Test
	void largeSingleSenderBatchMaintainsContiguousExecutionAndExactReservation() {
		int transactionCount = 750;
		MempoolProperties properties = properties(transactionCount + 10L);
		properties.setMaxNonceGap(transactionCount + 10L);
		MempoolStore store = store(properties);
		List<MempoolEntry> entries = new ArrayList<>(transactionCount);
		for (int nonce = 1; nonce <= transactionCount; nonce++) {
			entries.add(transfer(nonce, ALICE, nonce, 1));
		}

		store.addTransactions(entries, Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

		assertThat(store.getCount()).isEqualTo(transactionCount);
		assertThat(store.getExecutableTransactionsIterator()).toIterable().hasSize(transactionCount);
		MempoolEntry candidate = transfer(transactionCount + 1, ALICE, transactionCount + 1L, 0);
		assertThat(store.nativeReservation(ALICE, candidate.getTx(), Wei.valueOf(transactionCount + 1L)).reserved())
				.isEqualTo(Wei.valueOf(transactionCount));
	}

	@Test
	void largeMultiSenderBatchKeepsEveryHashAndSenderIndexConsistent() {
		int senderCount = 200;
		MempoolStore store = store(properties(senderCount + 10L));
		List<MempoolEntry> entries = new ArrayList<>(senderCount);
		Map<Address, Long> chainNonces = new HashMap<>();
		for (int index = 1; index <= senderCount; index++) {
			Address sender = address(1_000 + index);
			entries.add(transfer(1_000 + index, sender, 1, index));
			chainNonces.put(sender, 0L);
		}

		store.addTransactions(entries, chainNonces, MempoolTxAddEvent.AddReason.SYNC);

		assertThat(store.getCount()).isEqualTo(senderCount);
		assertThat(store.getAllTxHashes()).hasSize(senderCount);
		assertThat(entries).allSatisfy(entry -> {
			assertThat(store.getTxByHash(entry.getHash())).containsSame(entry);
			assertThat(store.getTxsBySender(entry.getTx().getSender())).containsExactly(entry);
		});
	}

	@Test
	void fullMempoolRemainsAtCapacityAndRetainsHigherFeeIndependentTransactions() {
		int capacity = 100;
		MempoolStore store = store(properties(capacity));
		List<MempoolEntry> highFeeEntries = new ArrayList<>(capacity);
		for (int index = 1; index <= capacity; index++) {
			store.addTransaction(transfer(index, address(2_000 + index), 1, 1), 0,
					MempoolTxAddEvent.AddReason.NEW);
		}
		for (int index = 1; index <= capacity; index++) {
			MempoolEntry highFee = transfer(10_000 + index, address(3_000 + index), 1, 100);
			highFeeEntries.add(highFee);
			store.addTransaction(highFee, 0, MempoolTxAddEvent.AddReason.NEW);
		}

		assertThat(store.getCount()).isEqualTo(capacity);
		assertThat(store.getAllTxs()).containsExactlyInAnyOrderElementsOf(highFeeEntries);
		assertThat(store.getFeeStatistics().txCount()).isEqualTo(capacity);
	}

	private MempoolStore store(MempoolProperties properties) {
		MempoolStore store = new MempoolStore(
				new SimpleMeterRegistry(), properties, mock(ChainHeadStateCache.class),
				mock(ApplicationEventPublisher.class));
		store.initMetrics();
		return store;
	}
}
