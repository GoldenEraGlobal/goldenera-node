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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.IdentityHashMap;
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
import org.mockito.InOrder;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationContext;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationSession;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.BlockValidator.PreparedHeaderValidation;
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
		List<BlockHeader> headers = headers(8);
		stubPreparedHeaders(validator, headers, List.of(context(1)));
		AtomicInteger active = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		CountDownLatch twoWorkersEntered = new CountDownLatch(2);
		Set<String> threadNames = ConcurrentHashMap.newKeySet();
		when(validator.validatePreparedHeader(any(PreparedHeaderValidation.class),
				any(ProofOfWorkVerificationSession.class))).thenAnswer(invocation -> {
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
			Map<Hash, StatelessValidatedHeader> validated = service.validateBatch(headers);

			assertThat(validated).hasSize(8);
			assertThat(peak).hasValue(2);
			assertThat(service.headerValidationWorkSnapshot())
					.isEqualTo(new BlockSyncManagerService.HeaderValidationWorkSnapshot(2, 2, 1));
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
		List<BlockHeader> headers = headers(4);
		stubPreparedHeaders(validator, headers, List.of(context(1)));
		IllegalStateException failure = new IllegalStateException("invalid header");
		when(validator.validatePreparedHeader(any(PreparedHeaderValidation.class),
				any(ProofOfWorkVerificationSession.class))).thenThrow(failure);
		BlockSyncManagerService service = service(validator);

		try {
			assertThatThrownBy(() -> service.validateBatch(headers)).isSameAs(failure);
		} finally {
			service.stop();
		}
	}

	@Test
	@Timeout(5)
	void preservesTheLowestHeaderIndexFailureAcrossConcurrentChunks() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(2);
		List<BlockHeader> headers = headers(8);
		Map<PreparedHeaderValidation, ProofOfWorkVerificationContext> contexts =
				stubPreparedHeaders(validator, headers, List.of(context(1)));
		Map<PreparedHeaderValidation, Integer> indexes = new IdentityHashMap<>();
		for (int index = 0; index < headers.size(); index++) {
			BlockHeader header = headers.get(index);
			PreparedHeaderValidation item = validator.prepareHeader(header, Map.of());
			indexes.put(item, index);
		}
		IllegalStateException earlier = new IllegalStateException("earlier invalid header");
		IllegalArgumentException later = new IllegalArgumentException("later invalid header");
		CountDownLatch laterFailed = new CountDownLatch(1);
		when(validator.validatePreparedHeader(any(PreparedHeaderValidation.class),
				any(ProofOfWorkVerificationSession.class))).thenAnswer(invocation -> {
			PreparedHeaderValidation item = invocation.getArgument(0);
			int index = indexes.get(item);
			if (index == 2) {
				assertThat(laterFailed.await(1, TimeUnit.SECONDS)).isTrue();
				throw earlier;
			}
			if (index == 4) {
				laterFailed.countDown();
				throw later;
			}
			return mock(StatelessValidatedHeader.class);
		});
		BlockSyncManagerService service = service(validator, 2);

		try {
			assertThatThrownBy(() -> service.validateBatch(headers)).isSameAs(earlier);
		} finally {
			service.stop();
		}
	}

	@Test
	@Timeout(10)
	void actualWorkerConcurrencyScalesOnFourEightAndSixteenCpuHosts() throws Exception {
		for (int processors : List.of(4, 8, 16)) {
			BlockValidator validator = mock(BlockValidator.class);
			when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(16);
			List<BlockHeader> headers = headers(processors * 2);
			stubPreparedHeaders(validator, headers, List.of(context(processors)));
			AtomicInteger active = new AtomicInteger();
			AtomicInteger peak = new AtomicInteger();
			CountDownLatch allWorkersEntered = new CountDownLatch(processors);
			when(validator.validatePreparedHeader(any(PreparedHeaderValidation.class),
					any(ProofOfWorkVerificationSession.class))).thenAnswer(invocation -> {
				int concurrency = active.incrementAndGet();
				peak.accumulateAndGet(concurrency, Math::max);
				allWorkersEntered.countDown();
				try {
					assertThat(allWorkersEntered.await(2, TimeUnit.SECONDS)).isTrue();
					return mock(StatelessValidatedHeader.class);
				} finally {
					active.decrementAndGet();
				}
			});
			BlockSyncManagerService service = service(validator, processors);

			try {
				assertThat(service.validateBatch(headers)).hasSize(headers.size());
				assertThat(peak).hasValue(processors);
				assertThat(service.headerValidationWorkSnapshot())
						.isEqualTo(new BlockSyncManagerService.HeaderValidationWorkSnapshot(
								processors, processors, 1));
			} finally {
				service.stop();
			}
		}
	}

	@Test
	void completesSeedProducingEpochBeforeOpeningTheNextEpochSessions() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(4);
		List<BlockHeader> headers = headers(8);
		ProofOfWorkVerificationContext first = context(1);
		ProofOfWorkVerificationContext second = context(2);
		Map<PreparedHeaderValidation, ProofOfWorkVerificationContext> contexts =
				stubPreparedHeaders(validator, headers, List.of(first, second));
		AtomicInteger firstEpochValidated = new AtomicInteger();
		when(validator.openVerificationSession(any(ProofOfWorkVerificationContext.class)))
				.thenAnswer(invocation -> {
					ProofOfWorkVerificationContext context = invocation.getArgument(0);
					if (context.equals(second)) {
						assertThat(firstEpochValidated).hasValue(4);
					}
					return mock(ProofOfWorkVerificationSession.class);
				});
		when(validator.validatePreparedHeader(any(PreparedHeaderValidation.class),
				any(ProofOfWorkVerificationSession.class))).thenAnswer(invocation -> {
			PreparedHeaderValidation prepared = invocation.getArgument(0);
			if (contexts.get(prepared).equals(first)) {
				firstEpochValidated.incrementAndGet();
			}
			return mock(StatelessValidatedHeader.class);
		});
		BlockSyncManagerService service = service(validator, 4);

		try {
			assertThat(service.validateBatch(headers)).hasSize(8);
			assertThat(service.headerValidationWorkSnapshot())
					.isEqualTo(new BlockSyncManagerService.HeaderValidationWorkSnapshot(4, 8, 2));
		} finally {
			service.stop();
		}
	}

	@Test
	void pausesMiningBeforePreparationAndResumesItWhenValidationFails() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(2);
		MiningService miningService = mock(MiningService.class);
		IllegalStateException failure = new IllegalStateException("invalid prepared header");
		when(validator.prepareHeader(any(BlockHeader.class), anyMap())).thenThrow(failure);
		BlockSyncManagerService service = service(
				validator, 2, miningService, mock(SyncVerificationAccelerationPolicy.class));

		try {
			assertThatThrownBy(() -> service.validateBatchWithMiningCoordination(headers(2)))
					.isSameAs(failure);
			InOrder order = inOrder(miningService, validator);
			order.verify(miningService).pauseMining();
			order.verify(validator).prepareHeader(any(BlockHeader.class), anyMap());
			order.verify(miningService).resumeMining();
		} finally {
			service.stop();
		}
	}

	@Test
	void successfulValidationRetainsMiningPauseUntilCatchUpCompletes() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(2);
		List<BlockHeader> headers = headers(2);
		stubPreparedHeaders(validator, headers, List.of(context(1)));
		when(validator.validatePreparedHeader(any(PreparedHeaderValidation.class),
				any(ProofOfWorkVerificationSession.class)))
				.thenAnswer(invocation -> mock(StatelessValidatedHeader.class));
		MiningService miningService = mock(MiningService.class);
		BlockSyncManagerService service = service(
				validator, 2, miningService, mock(SyncVerificationAccelerationPolicy.class));

		try {
			assertThat(service.validateBatchWithMiningCoordination(headers)).hasSize(2);
			verify(miningService).pauseMining();
			verify(miningService, never()).resumeMining();
		} finally {
			service.stop();
		}
	}

	@Test
	void accelerationLifecycleSignalsOneStartProgressAndTerminalTransition() {
		BlockValidator validator = mock(BlockValidator.class);
		when(validator.headerValidationConcurrencyLimit(any(Integer.class))).thenReturn(2);
		SyncVerificationAccelerationPolicy policy = mock(SyncVerificationAccelerationPolicy.class);
		BlockSyncManagerService service = service(validator, 2, mock(MiningService.class), policy);

		try {
			service.signalCatchUpGap(10, 1_000);
			service.signalCatchUpGap(20, 1_000);
			service.signalSyncProgress(30, 1_000);
			service.signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.FAILED, false);
			service.signalSyncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP, false);

			InOrder order = inOrder(policy);
			order.verify(policy).bulkCatchUpStarted(10, 1_000);
			order.verify(policy).progress(20, 1_000);
			order.verify(policy).progress(30, 1_000);
			order.verify(policy).syncEnded(SyncVerificationAccelerationPolicy.EndReason.FAILED);
			verify(policy, never()).syncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP);
		} finally {
			service.stop();
		}
	}

	@Test
	void parallelismScalesAcrossFourEightAndSixteenCpuHostsWithinVerifierCapacity() {
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(4, 16)).isEqualTo(4);
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(8, 16)).isEqualTo(8);
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(16, 4)).isEqualTo(4);
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(16, 16)).isEqualTo(16);
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(2, 4)).isEqualTo(2);
		assertThat(BlockSyncManagerService.calculateHeaderValidationParallelism(8, 1)).isEqualTo(1);
	}

	private BlockSyncManagerService service(BlockValidator validator) {
		return service(validator, Runtime.getRuntime().availableProcessors());
	}

	private BlockSyncManagerService service(BlockValidator validator, int availableProcessors) {
		return service(validator, availableProcessors, mock(MiningService.class),
				mock(SyncVerificationAccelerationPolicy.class));
	}

	private BlockSyncManagerService service(
			BlockValidator validator,
			int availableProcessors,
			MiningService miningService,
			SyncVerificationAccelerationPolicy policy) {
		return new BlockSyncManagerService(
				new SimpleMeterRegistry(),
				new ReentrantLock(),
				Runnable::run,
				miningService,
				mock(IdentityService.class),
				validator,
				mock(ChainQuery.class),
				mock(BlockReorgs.class),
				mock(PeerRegistry.class),
				mock(PeerReputationService.class),
				mock(BlockIngestionService.class),
				policy,
				availableProcessors);
	}

	private Map<PreparedHeaderValidation, ProofOfWorkVerificationContext> stubPreparedHeaders(
			BlockValidator validator,
			List<BlockHeader> headers,
			List<ProofOfWorkVerificationContext> contexts) {
		Map<BlockHeader, PreparedHeaderValidation> preparedByHeader = new IdentityHashMap<>();
		Map<PreparedHeaderValidation, ProofOfWorkVerificationContext> contextByPrepared =
				new IdentityHashMap<>();
		int groupSize = Math.max(1, headers.size() / contexts.size());
		for (int index = 0; index < headers.size(); index++) {
			ProofOfWorkVerificationContext context = contexts.get(
					Math.min(contexts.size() - 1, index / groupSize));
			PreparedHeaderValidation prepared = mock(PreparedHeaderValidation.class);
			when(prepared.verificationContext()).thenReturn(context);
			preparedByHeader.put(headers.get(index), prepared);
			contextByPrepared.put(prepared, context);
		}
		when(validator.prepareHeader(any(BlockHeader.class), anyMap()))
				.thenAnswer(invocation -> preparedByHeader.get(invocation.getArgument(0)));
		when(validator.openVerificationSession(any(ProofOfWorkVerificationContext.class)))
				.thenAnswer(invocation -> mock(ProofOfWorkVerificationSession.class));
		return contextByPrepared;
	}

	private ProofOfWorkVerificationContext context(int marker) {
		return new ProofOfWorkVerificationContext(Bytes.of(marker));
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
