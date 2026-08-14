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
package global.goldenera.node.core.mining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockMinedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MiningServiceParentLifecycleTest {

	@Test
	void canonicalParentChangeInterruptsCurrentNonceSearch() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread nonceSearch = new Thread(() -> {
			started.countDown();
			try {
				Thread.sleep(TimeUnit.SECONDS.toMillis(10));
			} catch (InterruptedException e) {
				interrupted.set(true);
				Thread.currentThread().interrupt();
			}
		});
		nonceSearch.start();
		assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
		miningFlag(fixture.service).set(true);
		ReflectionTestUtils.setField(fixture.service, "miningThread", nonceSearch);
		when(fixture.randomX.isInitializationInProgress()).thenReturn(false);

		fixture.service.onNewBlockConnected(mock(BlockConnectedEvent.class));

		nonceSearch.join(TimeUnit.SECONDS.toMillis(2));
		assertThat(interrupted).isTrue();
		assertThat(nonceSearch.isAlive()).isFalse();
		miningFlag(fixture.service).set(false);
	}

	@Test
	void staleTemplateFromPreviousParentIsNeverPublished() {
		Fixture fixture = fixture();
		Hash oldParent = hash(1);
		Hash currentParent = hash(2);
		StoredBlock currentTip = mock(StoredBlock.class);
		when(currentTip.getHash()).thenReturn(currentParent);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(currentTip);
		MiningBlockAssemblerService.BlockHeaderTemplate template = MiningBlockAssemblerService.BlockHeaderTemplate
				.builder()
				.version(BlockVersion.V1)
				.height(12)
				.timestamp(Instant.parse("2026-01-01T00:00:12Z"))
				.previousHash(oldParent)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(Address.fromHexString("0x0000000000000000000000000000000000000001"))
				.build();
		MiningBlockAssemblerService.AssembledBlock assembled = MiningBlockAssemblerService.AssembledBlock.builder()
				.blockTemplate(template)
				.txs(List.of())
				.invalidTxs(List.of())
				.build();

		ReflectionTestUtils.invokeMethod(fixture.service, "processMinedBlock", template, assembled, 7L, 1.0d);

		verify(fixture.publisher, never()).publishEvent(any(BlockMinedEvent.class));
	}

	private Fixture fixture() {
		MiningProperties properties = new MiningProperties();
		properties.setEnable(true);
		properties.setHashingThreads(1);
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		RandomXManager randomX = mock(RandomXManager.class);
		MiningService service = new MiningService(
				new SimpleMeterRegistry(), new ReentrantLock(), publisher, mock(ExecutorService.class),
				mock(MiningBlockAssemblerService.class), mock(IdentityService.class), mock(MempoolManager.class),
				chainQuery, properties, randomX, mock(ThreadFactory.class));
		return new Fixture(service, publisher, chainQuery, randomX);
	}

	private AtomicBoolean miningFlag(MiningService service) {
		return (AtomicBoolean) ReflectionTestUtils.getField(service, "isMining");
	}

	private Hash hash(int suffix) {
		return Hash.fromHexString(String.format("0x%064x", suffix));
	}

	private record Fixture(MiningService service, ApplicationEventPublisher publisher,
			ChainQuery chainQuery, RandomXManager randomX) {
	}
}
