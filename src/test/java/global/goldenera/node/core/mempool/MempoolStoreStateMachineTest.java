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

import static global.goldenera.node.core.mempool.MempoolTestFixtures.address;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolStore.StorageAddResult;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStoreStateMachineTest {

	private static final List<Address> SENDERS = List.of(address(101), address(102), address(103), address(104));

	@Test
	void randomizedStateMachineMatchesIndependentNonceModelForEveryStep() {
		int seedCount = Integer.getInteger("mempool.soak.seedCount", 5);
		int steps = Integer.getInteger("mempool.soak.steps", 500);
		for (int seedIndex = 0; seedIndex < seedCount; seedIndex++) {
			long seed = 7L + seedIndex * 31L;
			MempoolStore store = newStore(10_000);
			Map<Address, Long> confirmedNonces = new HashMap<>();
			Map<SenderNonce, MempoolEntry> model = new HashMap<>();
			Random random = new Random(seed);
			int nextId = 1;

			for (int step = 0; step < steps; step++) {
				Address sender = SENDERS.get(random.nextInt(SENDERS.size()));
				long confirmedNonce = confirmedNonces.getOrDefault(sender, 0L);
				int operation = random.nextInt(100);
				if (operation < 65) {
					long nonce = Math.max(1, confirmedNonce + 1 + random.nextInt(12));
					SenderNonce key = new SenderNonce(sender, nonce);
					MempoolEntry previous = model.get(key);
					long fee = previous == null ? 10 + random.nextInt(100)
							: previous.getTx().getFee().toBigInteger().longValueExact() * 2;
					MempoolEntry candidate = transfer(nextId++, sender, nonce, fee);
					StorageAddResult result = store.addTransaction(
							candidate, confirmedNonce, MempoolTxAddEvent.AddReason.NEW);
					if (result.isSuccess()) {
						model.put(key, candidate);
					}
				} else if (operation < 85 && !model.isEmpty()) {
					SenderNonce key = randomKey(model, random);
					MempoolEntry removed = model.remove(key);
					store.removeTransaction(removed.getHash());
				} else {
					long newNonce = random.nextInt(7);
					confirmedNonces.put(sender, newNonce);
					model.keySet().removeIf(key -> key.sender().equals(sender) && key.nonce() <= newNonce);
					store.resynchronizeSender(sender, newNonce);
				}

				assertMatchesModel(store, model, confirmedNonces, seed, step);
			}
		}
	}

	@Test
	void concurrentAdmissionRemovalPruningAndResynchronizationPreserveAllIndexes() throws Exception {
		MempoolStore store = newStore(5_000);
		int workerCount = Integer.getInteger("mempool.soak.workers", 12);
		int operationsPerWorker = Integer.getInteger("mempool.soak.operationsPerWorker", 300);
		AtomicInteger ids = new AtomicInteger(10_000);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int worker = 0; worker < workerCount; worker++) {
				int workerId = worker;
				futures.add(executor.submit(() -> {
					Random random = new Random(1_000L + workerId);
					start.await();
					for (int operation = 0; operation < operationsPerWorker; operation++) {
						Address sender = SENDERS.get(random.nextInt(SENDERS.size()));
						long nonce = 1 + random.nextInt(80);
						MempoolEntry entry = transfer(ids.incrementAndGet(), sender, nonce,
								100 + ids.get());
						entry.setFirstSeenTime(operation % 9 == 0 ? Instant.EPOCH : Instant.now());
						switch (random.nextInt(4)) {
							case 0, 1 -> store.addTransaction(entry, 0, MempoolTxAddEvent.AddReason.NEW);
							case 2 -> {
								List<MempoolEntry> senderEntries = store.getTxsBySender(sender);
								if (!senderEntries.isEmpty()) {
									store.removeTransaction(senderEntries.get(random.nextInt(senderEntries.size())).getHash());
								}
							}
							default -> {
								if (operation % 10 == 0) {
									store.pruneExpiredTransactions(Instant.EPOCH.plusSeconds(1));
								} else {
									store.resynchronizeSender(sender, 0);
								}
							}
						}
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get();
			}
		}

		assertPublicInvariants(store);
		assertThat(store.getCount()).isLessThanOrEqualTo(5_000);
	}

	private SenderNonce randomKey(Map<SenderNonce, MempoolEntry> model, Random random) {
		return new ArrayList<>(model.keySet()).get(random.nextInt(model.size()));
	}

	private void assertMatchesModel(MempoolStore store, Map<SenderNonce, MempoolEntry> model,
			Map<Address, Long> confirmedNonces, long seed, int step) {
		String context = "seed=" + seed + ", step=" + step;
		assertThat(store.getAllTxs()).as(context)
				.containsExactlyInAnyOrderElementsOf(model.values());
		Set<Hash> expectedExecutable = new HashSet<>();
		for (Address sender : SENDERS) {
			long nonce = confirmedNonces.getOrDefault(sender, 0L) + 1;
			while (model.containsKey(new SenderNonce(sender, nonce))) {
				expectedExecutable.add(model.get(new SenderNonce(sender, nonce)).getHash());
				nonce++;
			}
		}
		Set<Hash> actualExecutable = new HashSet<>();
		store.getExecutableTransactionsIterator().forEachRemaining(entry -> actualExecutable.add(entry.getHash()));
		assertThat(actualExecutable).as(context).isEqualTo(expectedExecutable);
		assertPublicInvariants(store);
	}

	private void assertPublicInvariants(MempoolStore store) {
		List<MempoolEntry> all = store.getAllTxs();
		assertThat(store.getCount()).isEqualTo(all.size());
		assertThat(store.getAllTxHashes()).containsExactlyInAnyOrderElementsOf(
				all.stream().map(MempoolEntry::getHash).toList());
		assertThat(all.stream().map(entry -> new SenderNonce(entry.getTx().getSender(), entry.getNonce())))
				.doesNotHaveDuplicates();
		for (Address sender : SENDERS) {
			assertThat(store.getTxsBySender(sender)).allMatch(all::contains);
		}
		store.getExecutableTransactionsIterator().forEachRemaining(entry -> assertThat(all).contains(entry));
	}

	private MempoolStore newStore(long capacity) {
		return new MempoolStore(new SimpleMeterRegistry(), properties(capacity),
				mock(ChainHeadStateCache.class), mock(ApplicationEventPublisher.class));
	}

	private record SenderNonce(Address sender, long nonce) {
	}
}
