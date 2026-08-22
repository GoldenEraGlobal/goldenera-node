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
package global.goldenera.node.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedHeader;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BlockSyncManagerHeaderValidationExecutorTest {

	@Test
	@Timeout(5)
	void validatesOnDedicatedBoundedWorkersWithoutUsingTheCommonPool() throws Exception {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(2);
		AtomicInteger active = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		CountDownLatch twoWorkersEntered = new CountDownLatch(2);
		Set<String> threadNames = ConcurrentHashMap.newKeySet();
		when(validator.validateHeader(any(BlockHeader.class), anyMap())).thenAnswer(invocation -> {
			threadNames.add(Thread.currentThread().getName());
			int concurrency = active.incrementAndGet();
			peak.accumulateAndGet(concurrency, Math::max);
			twoWorkersEntered.countDown();
			try {
				assertThat(twoWorkersEntered.await(1, TimeUnit.SECONDS)).isTrue();
				return mock(StatelessValidatedHeader.class);
			} finally {
				active.decrementAndGet();
			}
		});
		BlockSyncManagerService service = service(validator);

		try {
			Map<Hash, StatelessValidatedHeader> validated = service.validateBatch(headers(8));

			assertThat(validated).hasSize(8);
			assertThat(peak).hasValue(2);
			assertThat(threadNames)
					.allMatch(name -> name.startsWith("Sync-Header-Validator"))
					.noneMatch(name -> name.contains("ForkJoinPool"));
		} finally {
			service.stop();
		}
	}

	@Test
	void propagatesTheOriginalValidationFailureAndCancelsTheBatch() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(2);
		IllegalStateException failure = new IllegalStateException("invalid header");
		when(validator.validateHeader(any(BlockHeader.class), anyMap())).thenThrow(failure);
		BlockSyncManagerService service = service(validator);

		try {
			assertThatThrownBy(() -> service.validateBatch(headers(4))).isSameAs(failure);
		} finally {
			service.stop();
		}
	}

	@Test
	void parallelismNeverExceedsCpuOrVerifierLeaseCapacity() {
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(16, 4)).isEqualTo(4);
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(2, 4)).isEqualTo(2);
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(8, 1)).isEqualTo(1);
	}

	private BlockSyncManagerService service(BlockValidator validator) {
		return new BlockSyncManagerService(
				new SimpleMeterRegistry(),
				new ReentrantLock(),
				Runnable::run,
				mock(MiningService.class),
				mock(IdentityService.class),
				validator,
				mock(ChainQuery.class),
				mock(BlockReorgs.class),
				mock(PeerRegistry.class),
				mock(PeerReputationService.class),
				mock(BlockIngestionService.class));
	}

	private List<BlockHeader> headers(int count) {
		List<BlockHeader> headers = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			BlockHeader header = mock(BlockHeader.class);
			Hash hash = Hash.hash(Bytes.of(index + 1));
			when(header.getHeight()).thenReturn((long) index + 1L);
			when(header.getHash()).thenReturn(hash);
			headers.add(header);
		}
		return headers;
	}
}
