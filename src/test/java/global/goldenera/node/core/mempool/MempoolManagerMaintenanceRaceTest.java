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
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolValidator.MempoolValidationResult;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.state.WorldState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolManagerMaintenanceRaceTest {

	@Test
	void connectedBlockCannotBeUndoneByOlderOverlappingRevalidationSnapshot() throws Exception {
		RaceFixture fixture = fixture(1);
		MempoolEntry mined = transfer(1, ALICE, 1, 10);
		MempoolEntry descendant = transfer(2, ALICE, 2, 20);
		fixture.store().addTransactions(List.of(mined, descendant), Map.of(ALICE, 0L),
				MempoolTxAddEvent.AddReason.SYNC);
		Block block = block(List.of(mined), 11);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> revalidation = executor.submit(fixture.manager()::revalidateMempool);
			assertThat(fixture.validationStarted().await(5, TimeUnit.SECONDS)).isTrue();
			Future<?> connection = executor.submit(() -> fixture.manager().onBlockConnected(connected(block)));
			assertThat(connection).isNotDone();
			fixture.releaseValidation().countDown();
			revalidation.get();
			connection.get();
		}

		assertThat(fixture.store().getTxByHash(mined.getHash())).isEmpty();
		assertThat(executable(fixture.store())).containsExactly(descendant);
	}

	@Test
	void disconnectedBlockReadditionCannotRaceWithPeriodicRevalidation() throws Exception {
		RaceFixture fixture = fixture(0);
		MempoolEntry restored = transfer(10, ALICE, 1, 10);
		MempoolEntry descendant = transfer(11, ALICE, 2, 20);
		fixture.store().addTransaction(descendant, 1, MempoolTxAddEvent.AddReason.NEW);
		Block block = block(List.of(restored), 11);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> revalidation = executor.submit(fixture.manager()::revalidateMempool);
			assertThat(fixture.validationStarted().await(5, TimeUnit.SECONDS)).isTrue();
			Future<?> disconnection = executor.submit(
					() -> fixture.manager().onBlockDisconnected(new BlockDisconnectedEvent(this, block)));
			assertThat(disconnection).isNotDone();
			fixture.releaseValidation().countDown();
			revalidation.get();
			disconnection.get();
		}

		assertThat(fixture.store().getAllTxs()).extracting(MempoolEntry::getHash)
				.containsExactlyInAnyOrder(restored.getHash(), descendant.getHash());
		assertThat(executable(fixture.store())).hasSize(2);
	}

	private RaceFixture fixture(long headNonce) throws InterruptedException {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ChainHeadStateCache cache = mock(ChainHeadStateCache.class);
		WorldState state = mock(WorldState.class);
		AccountNonceState nonce = mock(AccountNonceState.class);
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(nonce.getNonce()).thenReturn(headNonce);
		when(balance.getBalance()).thenReturn(Wei.valueOf(Long.MAX_VALUE));
		when(state.getNonce(ALICE)).thenReturn(nonce);
		when(state.getBalance(any(Address.class), any(Address.class))).thenReturn(balance);
		when(cache.getHeadState()).thenReturn(state);
		MempoolStore store = new MempoolStore(registry, properties(100), cache,
				mock(ApplicationEventPublisher.class));
		MempoolValidator validator = mock(MempoolValidator.class);
		CountDownLatch validationStarted = new CountDownLatch(1);
		CountDownLatch releaseValidation = new CountDownLatch(1);
		when(validator.revalidateAgainstChain(any(MempoolEntry.class))).thenAnswer(invocation -> {
			validationStarted.countDown();
			releaseValidation.await();
			return MempoolValidationResult.valid(headNonce);
		});
		MempoolManager manager = new MempoolManager(registry, store, validator, properties(100), cache,
				Runnable::run, mock(ThreadPoolTaskScheduler.class));
		return new RaceFixture(store, manager, validationStarted, releaseValidation);
	}

	private Block block(List<MempoolEntry> entries, long height) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getTimestamp()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(height);
		when(block.getHeader()).thenReturn(header);
		when(block.getTxs()).thenReturn(entries.stream().map(MempoolEntry::getTx).toList());
		return block;
	}

	private BlockConnectedEvent connected(Block block) {
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		when(event.getBlock()).thenReturn(block);
		return event;
	}

	private List<MempoolEntry> executable(MempoolStore store) {
		List<MempoolEntry> entries = new ArrayList<>();
		store.getExecutableTransactionsIterator().forEachRemaining(entries::add);
		return entries;
	}

	private record RaceFixture(MempoolStore store, MempoolManager manager,
			CountDownLatch validationStarted, CountDownLatch releaseValidation) {
	}
}
