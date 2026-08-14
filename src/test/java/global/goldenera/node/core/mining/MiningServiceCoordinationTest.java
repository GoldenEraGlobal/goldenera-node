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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkHasher;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.time.BlockTimestampReservation;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.BlockIngestionOutcome;
import global.goldenera.node.core.sync.BlockIngestionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MiningServiceCoordinationTest {

	private static final Hash PARENT_HASH = hash(1);
	private static final Instant BLOCK_TIME = Instant.parse("2028-01-01T00:00:01Z");

	@Test
	void exactOneWorksWithAutonomousMiningDisabledAndUsesReservedTimestampAndValidatedIngestion() throws Exception {
		try (Fixture fixture = fixture(false)) {
			Instant requested = BLOCK_TIME.plusMillis(10);
			ExactOneMiningOutcome outcome = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.of(requested), Duration.ofSeconds(2))).get(2, TimeUnit.SECONDS);

			assertThat(outcome.code()).isEqualTo(ExactOneMiningOutcome.Code.ACCEPTED);
			assertThat(outcome.ingestionCode()).isEqualTo(BlockIngestionOutcome.Code.ACCEPTED);
			verify(fixture.chainClock).reserveNextBlockTimestamp(any(), eq(Optional.of(requested)));
			verify(fixture.assembler).createBlockTemplate(fixture.parentBlock, fixture.reservation);
			verify(fixture.assembler, never()).createBlockTemplate(fixture.parentBlock);
			verify(fixture.reservation).close();
			ArgumentCaptor<Instant> receivedAt = ArgumentCaptor.forClass(Instant.class);
			verify(fixture.ingestion).processBlock(any(), any(), any(), receivedAt.capture());
			assertThat(receivedAt.getValue()).isEqualTo(BLOCK_TIME);
		}
	}

	@Test
	void syncResumeNeverClearsSandboxControlPause() throws Exception {
		try (Fixture fixture = fixture(true)) {
			assertThat(fixture.service.pauseAutonomousMining(Duration.ofSeconds(1))).isTrue();

			fixture.service.pauseMining();
			fixture.service.resumeMining();

			AutonomousMiningState state = fixture.service.getAutonomousMiningState();
			assertThat(state.suspensions()).containsExactly(MiningSuspensionReason.SANDBOX_CONTROL);
			assertThat(state.quiescent()).isTrue();
			ExactOneMiningOutcome outcome = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.empty(), Duration.ofSeconds(2))).get(2, TimeUnit.SECONDS);
			assertThat(outcome.accepted()).isTrue();
		}
	}

	@Test
	void exactOneRejectsActiveAutonomousIntentAndSyncSuspension() throws Exception {
		try (Fixture fixture = fixture(true)) {
			ExactOneMiningOutcome notPaused = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.empty(), Duration.ofSeconds(1))).get(1, TimeUnit.SECONDS);
			assertThat(notPaused.code()).isEqualTo(ExactOneMiningOutcome.Code.REJECTED_NOT_PAUSED);

			fixture.service.pauseMining();
			ExactOneMiningOutcome syncing = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.empty(), Duration.ofSeconds(1))).get(1, TimeUnit.SECONDS);
			assertThat(syncing.code()).isEqualTo(ExactOneMiningOutcome.Code.REJECTED_SYNCING);
		}
	}

	@Test
	void oneSerializationBoundaryRejectsSecondExactRequestWithoutQueueingMutation() throws Exception {
		CountDownLatch hashingStarted = new CountDownLatch(1);
		CountDownLatch releaseHash = new CountDownLatch(1);
		try (Fixture fixture = fixture(false, input -> {
			hashingStarted.countDown();
			try {
				releaseHash.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
		})) {
			var first = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.empty(), Duration.ofSeconds(3)));
			assertThat(hashingStarted.await(1, TimeUnit.SECONDS)).isTrue();

			ExactOneMiningOutcome second = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.empty(), Duration.ofSeconds(1))).get(1, TimeUnit.SECONDS);
			assertThat(second.code()).isEqualTo(ExactOneMiningOutcome.Code.REJECTED_BUSY);

			releaseHash.countDown();
			assertThat(first.get(2, TimeUnit.SECONDS).accepted()).isTrue();
			verify(fixture.ingestion, atMostOnce()).processBlock(any(), any(), any(), any());
		}
	}

	@Test
	void parentChangeCancelsExactAttemptAsStaleWithoutRetry() throws Exception {
		CountDownLatch hashingStarted = new CountDownLatch(1);
		try (Fixture fixture = fixture(false, input -> {
			hashingStarted.countDown();
			byte[] hash = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
			Arrays.fill(hash, (byte) 0xff);
			return hash;
		})) {
			var result = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.empty(), Duration.ofSeconds(3)));
			assertThat(hashingStarted.await(1, TimeUnit.SECONDS)).isTrue();
			BlockConnectedEvent connected = mock(BlockConnectedEvent.class);
			Block competing = mock(Block.class);
			when(competing.getHash()).thenReturn(hash(9));
			when(connected.getBlock()).thenReturn(competing);

			fixture.service.onNewBlockConnected(connected);

			assertThat(result.get(2, TimeUnit.SECONDS).code()).isEqualTo(ExactOneMiningOutcome.Code.STALE_PARENT);
			verify(fixture.ingestion, never()).processBlock(any(), any(), any(), any());
			verify(fixture.assembler).createBlockTemplate(fixture.parentBlock, fixture.reservation);
		}
	}

	@Test
	void exactAttemptStopsAtItsBoundedDeadlineWithoutSubmission() throws Exception {
		try (Fixture fixture = fixture(false, input -> {
			byte[] hash = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
			Arrays.fill(hash, (byte) 0xff);
			return hash;
		})) {
			ExactOneMiningOutcome outcome = fixture.service.mineExactlyOne(new ExactOneMiningRequest(
					Optional.empty(), Duration.ofMillis(20))).get(2, TimeUnit.SECONDS);

			assertThat(outcome.code()).isEqualTo(ExactOneMiningOutcome.Code.TIMED_OUT);
			verify(fixture.ingestion, never()).processBlock(any(), any(), any(), any());
		}
	}

	@Test
	void exactDeadlineIsRecheckedAfterNativeInitializationWithoutInterruptingIt() throws Exception {
		CountDownLatch initializationStarted = new CountDownLatch(1);
		CountDownLatch releaseInitialization = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		try (Fixture fixture = fixture(false)) {
			doAnswer(ignored -> {
				initializationStarted.countDown();
				try {
					releaseInitialization.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					interrupted.set(true);
					Thread.currentThread().interrupt();
				}
				return null;
			}).when(fixture.pow).prepareForMining(anyLong());

			CompletableFuture<ExactOneMiningOutcome> result = fixture.service.mineExactlyOne(
					new ExactOneMiningRequest(Optional.empty(), Duration.ofMillis(30)));
			assertThat(initializationStarted.await(1, TimeUnit.SECONDS)).isTrue();
			ExactOneMiningOutcome outcome = result.get(300, TimeUnit.MILLISECONDS);

			assertThat(outcome.code()).isEqualTo(ExactOneMiningOutcome.Code.TIMED_OUT);
			assertThat(interrupted).isFalse();
			releaseInitialization.countDown();
			fixture.mainExecutor.submit(() -> { }).get(1, TimeUnit.SECONDS);
			verify(fixture.pow, never()).openMiningHasher();
		}
	}

	@Test
	void exactDeadlineStartsAtAdmissionWhileExecutorIsOccupied() throws Exception {
		CountDownLatch executorOccupied = new CountDownLatch(1);
		CountDownLatch releaseExecutor = new CountDownLatch(1);
		try (Fixture fixture = fixture(false)) {
			fixture.mainExecutor.submit(() -> {
				executorOccupied.countDown();
				try {
					releaseExecutor.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertThat(executorOccupied.await(1, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<ExactOneMiningOutcome> result = fixture.service.mineExactlyOne(
					new ExactOneMiningRequest(Optional.empty(), Duration.ofMillis(25)));
			assertThat(result.get(300, TimeUnit.MILLISECONDS).code())
					.isEqualTo(ExactOneMiningOutcome.Code.TIMED_OUT);
			ExactOneMiningOutcome busy = fixture.service.mineExactlyOne(
					new ExactOneMiningRequest(Optional.empty(), Duration.ofSeconds(1)))
					.get(1, TimeUnit.SECONDS);
			assertThat(busy.code()).isEqualTo(ExactOneMiningOutcome.Code.REJECTED_BUSY);

			releaseExecutor.countDown();
			fixture.mainExecutor.submit(() -> { }).get(1, TimeUnit.SECONDS);
			verify(fixture.assembler, never()).createBlockTemplate(fixture.parentBlock);
			verify(fixture.assembler, never()).createBlockTemplate(
					eq(fixture.parentBlock), any(BlockTimestampReservation.class));
		}
	}

	@Test
	void shutdownBeforeSubmissionLinearizationRejectsAndForbidsIngestion() throws Exception {
		CountDownLatch signingStarted = new CountDownLatch(1);
		CountDownLatch releaseSigning = new CountDownLatch(1);
		try (Fixture fixture = fixture(false)) {
			doAnswer(ignored -> {
				signingStarted.countDown();
				try {
					releaseSigning.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return Signature.ZERO;
			}).when(fixture.privateKey).sign(any());

			CompletableFuture<ExactOneMiningOutcome> result = fixture.service.mineExactlyOne(
					new ExactOneMiningRequest(Optional.empty(), Duration.ofSeconds(2)));
			assertThat(signingStarted.await(1, TimeUnit.SECONDS)).isTrue();

			fixture.service.stopMining();
			releaseSigning.countDown();

			assertThat(result.get(1, TimeUnit.SECONDS).code())
					.isEqualTo(ExactOneMiningOutcome.Code.REJECTED_SHUTDOWN);
			verify(fixture.ingestion, never()).processBlock(any(), any(), any(), any());
		}
	}

	@Test
	void shutdownAfterSubmissionLinearizationPreservesRealIngestionOutcome() throws Exception {
		CountDownLatch ingestionStarted = new CountDownLatch(1);
		CountDownLatch releaseIngestion = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		try (Fixture fixture = fixture(false)) {
			doAnswer(ignored -> {
				ingestionStarted.countDown();
				try {
					releaseIngestion.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					interrupted.set(true);
					Thread.currentThread().interrupt();
				}
				return BlockIngestionOutcome.of(BlockIngestionOutcome.Code.ACCEPTED);
			}).when(fixture.ingestion).processBlock(any(), any(), any(), any());

			CompletableFuture<ExactOneMiningOutcome> result = fixture.service.mineExactlyOne(
					new ExactOneMiningRequest(Optional.empty(), Duration.ofSeconds(2)));
			assertThat(ingestionStarted.await(1, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<Void> stopping = CompletableFuture.runAsync(fixture.service::stopMining);
			Thread.sleep(50);

			assertThat(result.isDone()).isFalse();
			assertThat(interrupted).isFalse();
			releaseIngestion.countDown();

			assertThat(result.get(1, TimeUnit.SECONDS).code()).isEqualTo(ExactOneMiningOutcome.Code.ACCEPTED);
			stopping.get(1, TimeUnit.SECONDS);
			assertThat(interrupted).isFalse();
		}
	}

	@Test
	void admittedExactRequestCompletesWhenShutdownImmediatelyDrainsItsTask() throws Exception {
		CountDownLatch executorOccupied = new CountDownLatch(1);
		CountDownLatch releaseExecutor = new CountDownLatch(1);
		try (Fixture fixture = fixture(false)) {
			fixture.mainExecutor.submit(() -> {
				executorOccupied.countDown();
				try {
					releaseExecutor.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertThat(executorOccupied.await(1, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<ExactOneMiningOutcome> result = fixture.service.mineExactlyOne(
					new ExactOneMiningRequest(Optional.empty(), Duration.ofSeconds(1)));

			fixture.service.stopMining();
			releaseExecutor.countDown();

			assertThat(result.get(1, TimeUnit.SECONDS).code())
					.isEqualTo(ExactOneMiningOutcome.Code.REJECTED_SHUTDOWN);
			assertThat(ReflectionTestUtils.getField(fixture.service, "exactOneOperation")).isNull();
		}
	}

	@Test
	void pauseDoesNotInterruptNativeInitializationAndOnlyReportsQuiescentAfterItFinishes() throws Exception {
		CountDownLatch initializationStarted = new CountDownLatch(1);
		CountDownLatch releaseInitialization = new CountDownLatch(1);
		AtomicBoolean initializationInProgress = new AtomicBoolean();
		AtomicBoolean interrupted = new AtomicBoolean();
		try (Fixture fixture = fixture(true)) {
			when(fixture.pow.isInitializationInProgress()).thenAnswer(ignored -> initializationInProgress.get());
			doAnswer(ignored -> {
				initializationInProgress.set(true);
				initializationStarted.countDown();
				try {
					releaseInitialization.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					interrupted.set(true);
					Thread.currentThread().interrupt();
				} finally {
					initializationInProgress.set(false);
				}
				return null;
			}).when(fixture.pow).prepareForMining(anyLong());

			fixture.service.startMining();
			assertThat(initializationStarted.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(fixture.service.pauseAutonomousMining(Duration.ofMillis(50))).isFalse();
			assertThat(interrupted).isFalse();

			releaseInitialization.countDown();
			assertThat(fixture.service.pauseAutonomousMining(Duration.ofSeconds(2))).isTrue();
			assertThat(interrupted).isFalse();
		}
	}

	@Test
	void pauseWaitsForQueuedAutonomousTaskToExitBeforeReportingQuiescence() throws Exception {
		CountDownLatch executorOccupied = new CountDownLatch(1);
		CountDownLatch releaseExecutor = new CountDownLatch(1);
		try (Fixture fixture = fixture(true)) {
			fixture.mainExecutor.submit(() -> {
				executorOccupied.countDown();
				try {
					releaseExecutor.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertThat(executorOccupied.await(1, TimeUnit.SECONDS)).isTrue();

			fixture.service.startMining();
			AutonomousMiningState queued = fixture.service.getAutonomousMiningState();
			assertThat(queued.scheduled()).isTrue();
			assertThat(queued.active()).isFalse();
			assertThat(queued.quiescent()).isFalse();
			CompletableFuture<Boolean> pause = CompletableFuture.supplyAsync(
					() -> fixture.service.pauseAutonomousMining(Duration.ofSeconds(2)));
			Thread.sleep(50);
			assertThat(pause.isDone()).isFalse();

			releaseExecutor.countDown();
			assertThat(pause.get(1, TimeUnit.SECONDS)).isTrue();
			assertThat(fixture.service.getAutonomousMiningState().quiescent()).isTrue();
		}
	}

	@Test
	void cancellationDuringLazyHashingWorkerCreationClosesThePublishedWorker() throws Exception {
		CountDownLatch workerCreationStarted = new CountDownLatch(1);
		CountDownLatch releaseWorkerCreation = new CountDownLatch(1);
		ThreadFactory blockingFactory = task -> {
			workerCreationStarted.countDown();
			try {
				releaseWorkerCreation.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return Executors.defaultThreadFactory().newThread(task);
		};
		try (Fixture fixture = fixture(false, input -> {
			byte[] hash = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
			Arrays.fill(hash, (byte) 0xff);
			return hash;
		}, blockingFactory)) {
			CompletableFuture<ExactOneMiningOutcome> result = fixture.service.mineExactlyOne(
					new ExactOneMiningRequest(Optional.empty(), Duration.ofSeconds(2)));
			assertThat(workerCreationStarted.await(1, TimeUnit.SECONDS)).isTrue();

			BlockConnectedEvent connected = mock(BlockConnectedEvent.class);
			Block competing = mock(Block.class);
			when(competing.getHash()).thenReturn(hash(9));
			when(connected.getBlock()).thenReturn(competing);
			CompletableFuture<Void> cancellation = CompletableFuture.runAsync(
					() -> fixture.service.onNewBlockConnected(connected));
			Thread.sleep(25);
			releaseWorkerCreation.countDown();
			cancellation.get(1, TimeUnit.SECONDS);

			assertThat(result.get(1, TimeUnit.SECONDS).code()).isEqualTo(ExactOneMiningOutcome.Code.STALE_PARENT);
			assertThat(ReflectionTestUtils.getField(fixture.service, "blockHashingWorker")).isNull();
		}
	}

	@Test
	void shutdownReturnsBoundedlyWithoutInterruptingLongNativeInitialization() throws Exception {
		CountDownLatch initializationStarted = new CountDownLatch(1);
		CountDownLatch releaseInitialization = new CountDownLatch(1);
		AtomicBoolean initializationInProgress = new AtomicBoolean();
		AtomicBoolean interrupted = new AtomicBoolean();
		try (Fixture fixture = fixture(true)) {
			when(fixture.pow.isInitializationInProgress()).thenAnswer(ignored -> initializationInProgress.get());
			doAnswer(ignored -> {
				initializationInProgress.set(true);
				initializationStarted.countDown();
				try {
					releaseInitialization.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					interrupted.set(true);
					Thread.currentThread().interrupt();
				} finally {
					initializationInProgress.set(false);
				}
				return null;
			}).when(fixture.pow).prepareForMining(anyLong());

			fixture.service.startMining();
			assertThat(initializationStarted.await(1, TimeUnit.SECONDS)).isTrue();
			long startedAt = System.nanoTime();
			fixture.service.stopMining();
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

			assertThat(elapsedMillis).isLessThan(3_000);
			assertThat(interrupted).isFalse();
			releaseInitialization.countDown();
			assertThat(fixture.mainExecutor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Fixture fixture(boolean enabled) {
		return fixture(enabled, input -> new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES]);
	}

	private Fixture fixture(boolean enabled, Function<byte[], byte[]> hashFunction) {
		return fixture(enabled, hashFunction, Executors.defaultThreadFactory());
	}

	private Fixture fixture(
			boolean enabled,
			Function<byte[], byte[]> hashFunction,
			ThreadFactory minerThreadFactory) {
		MiningProperties properties = new MiningProperties();
		properties.setEnable(enabled);
		properties.setHashingThreads(1);
		ExecutorService mainExecutor = Executors.newSingleThreadExecutor();
		MiningBlockAssemblerService assembler = mock(MiningBlockAssemblerService.class);
		ProofOfWorkProvider pow = mock(ProofOfWorkProvider.class);
		ChainClock chainClock = mock(ChainClock.class);
		BlockTimestampReservation reservation = mock(BlockTimestampReservation.class);
		BlockIngestionService ingestion = mock(BlockIngestionService.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		IdentityService identity = mock(IdentityService.class);
		MempoolManager mempool = mock(MempoolManager.class);
		Block parentBlock = mock(Block.class);
		BlockHeader parentHeader = mock(BlockHeader.class);
		StoredBlock parentStored = mock(StoredBlock.class);
		PrivateKey privateKey = mock(PrivateKey.class);

		when(parentBlock.getHash()).thenReturn(PARENT_HASH);
		when(parentBlock.getHeader()).thenReturn(parentHeader);
		when(parentStored.getBlock()).thenReturn(parentBlock);
		when(parentStored.getHash()).thenReturn(PARENT_HASH);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(parentStored);
		when(chainClock.reserveNextBlockTimestamp(any(), any())).thenReturn(reservation);
		when(pow.openMiningHasher()).thenAnswer(ignored -> new ProofOfWorkHasher(hashFunction, () -> { }));
		when(identity.getPrivateKey()).thenReturn(privateKey);
		when(identity.getNodeIdentityAddress()).thenReturn(Address.ZERO);
		when(privateKey.sign(any())).thenReturn(Signature.ZERO);
		when(ingestion.processBlock(any(), any(), any(), any()))
				.thenReturn(BlockIngestionOutcome.of(BlockIngestionOutcome.Code.ACCEPTED));

		MiningBlockAssemblerService.BlockHeaderTemplate template = MiningBlockAssemblerService.BlockHeaderTemplate
				.builder()
				.version(BlockVersion.V1)
				.height(12L)
				.timestamp(BLOCK_TIME)
				.previousHash(PARENT_HASH)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.valueOf(2))
				.coinbase(Address.fromHexString("0x0000000000000000000000000000000000000001"))
				.build();
		MiningBlockAssemblerService.AssembledBlock assembled = MiningBlockAssemblerService.AssembledBlock.builder()
				.blockTemplate(template)
				.txs(List.of())
				.invalidTxs(List.of())
				.build();
		try {
			when(assembler.createBlockTemplate(parentBlock, reservation)).thenReturn(Optional.of(assembled));
			when(assembler.createBlockTemplate(parentBlock)).thenReturn(Optional.of(assembled));
		} catch (Exception e) {
			throw new AssertionError(e);
		}

		MiningService service = new MiningService(
				new SimpleMeterRegistry(), new ReentrantLock(), mainExecutor, assembler, identity, mempool,
				chainQuery, properties, pow, chainClock, ingestion, minerThreadFactory);
		return new Fixture(
				service, mainExecutor, assembler, pow, chainClock, reservation, ingestion, parentBlock, privateKey);
	}

	private static Hash hash(int suffix) {
		return Hash.fromHexString(String.format("0x%064x", suffix));
	}

	private record Fixture(
			MiningService service,
			ExecutorService mainExecutor,
			MiningBlockAssemblerService assembler,
			ProofOfWorkProvider pow,
			ChainClock chainClock,
			BlockTimestampReservation reservation,
			BlockIngestionService ingestion,
			Block parentBlock,
			PrivateKey privateKey) implements AutoCloseable {

		@Override
		public void close() {
			service.stopMining();
		}
	}
}
