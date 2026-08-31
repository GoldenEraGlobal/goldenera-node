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
package global.goldenera.node.core.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.test.util.ReflectionTestUtils;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.exceptions.CryptoJException;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.properties.EquivocationProperties;
import global.goldenera.node.core.storage.blockchain.EquivocationEvidenceRepository;
import global.goldenera.node.core.storage.blockchain.RocksDBRepository;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence;
import global.goldenera.node.core.storage.blockchain.serialization.EquivocationEvidenceCodec;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class EquivocationDetectionServiceTest {

	private static final Instant SEEN = Instant.parse("2026-01-01T00:00:00Z");
	private static final PrivateKey KEY_A = key(1);
	private static final PrivateKey KEY_B = key(2);

	@TempDir
	Path tempDir;

	@Test
	void duplicateIsIdempotentAndDifferentIdentityAtSameHeightIsNotConflict() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("duplicates"))) {
			EquivocationDetectionService service = store.service();
			BlockHeader first = signedHeader(12, 1, KEY_A);
			BlockHeader otherIdentity = signedHeader(12, 2, KEY_B);

			assertThat(service.observeValidatedHeader(first, SEEN)).isFalse();
			assertThat(service.observeValidatedHeader(first, SEEN.plusSeconds(1))).isFalse();
			assertThat(service.observeValidatedHeader(otherIdentity, SEEN.plusSeconds(2))).isFalse();

			assertThat(store.repository.find(12, first.getIdentity()).orElseThrow().signedHeaders()).hasSize(1);
			assertThat(store.repository.find(12, otherIdentity.getIdentity()).orElseThrow().signedHeaders()).hasSize(1);
			assertThat(store.repository.findConflicts(100)).isEmpty();
		}
	}

	@Test
	void sameIdentityAndHeightWithDifferentHashesCreatesPersistentEvidence() throws Exception {
		Path dbPath = tempDir.resolve("restart");
		BlockHeader first = signedHeader(20, 10, KEY_A);
		BlockHeader second = signedHeader(20, 11, KEY_A);
		try (EvidenceStore store = EvidenceStore.open(dbPath)) {
			assertThat(store.service().enqueueValidatedHeader(first, SEEN)).isTrue();
			assertThat(store.service().enqueueValidatedHeader(second, SEEN.plusSeconds(5))).isTrue();
			store.service().close();
		}

		try (EvidenceStore reopened = EvidenceStore.open(dbPath)) {
			EquivocationEvidence evidence = reopened.repository.findConflicts(100).getFirst();
			assertThat(reopened.repository.countConflicts()).isEqualTo(1);
			assertThat(evidence.height()).isEqualTo(20);
			assertThat(evidence.identity()).isEqualTo(KEY_A.getAddress());
			assertThat(evidence.signedHeaders()).extracting(EquivocationEvidence.SignedHeader::blockHash)
					.containsExactlyInAnyOrder(first.getHash(), second.getHash());
			assertThat(evidence.signedHeaders()).extracting(EquivocationEvidence.SignedHeader::signature)
					.containsExactlyInAnyOrder(first.getSignature(), second.getSignature());
			assertThat(evidence.firstSeenAt()).isEqualTo(SEEN);
			assertThat(evidence.lastSeenAt()).isEqualTo(SEEN.plusSeconds(5));
		}
	}

	@Test
	void shutdownDrainsQueuedObservationsBeforeRepositoryCloses() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("async-drain"))) {
			BlockHeader first = signedHeader(21, 10, KEY_A);
			BlockHeader second = signedHeader(21, 11, KEY_A);
			assertThat(store.service().enqueueValidatedHeader(first, SEEN)).isTrue();
			assertThat(store.service().enqueueValidatedHeader(second, SEEN.plusSeconds(1))).isTrue();

			store.service().close();

			assertThat(store.repository.findConflicts(100)).hasSize(1);
		}
	}

	@Test
	void blockedRepositoryCannotBlockConsensusCallerAndAuditQueueIsBounded() throws Exception {
		EquivocationEvidenceRepository repository = mock(EquivocationEvidenceRepository.class);
		when(repository.countConflicts()).thenReturn(0L);
		CountDownLatch repositoryEntered = new CountDownLatch(1);
		CountDownLatch releaseRepository = new CountDownLatch(1);
		when(repository.find(anyLong(), any())).thenAnswer(invocation -> {
			repositoryEntered.countDown();
			releaseRepository.await();
			return Optional.empty();
		});
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		EquivocationDetectionService service = new EquivocationDetectionService(repository, registry);
		BlockHeader header = signedHeader(22, 1, KEY_A);
		try {
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
			assertThat(repositoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
			assertTimeout(Duration.ofSeconds(2), () -> {
				for (int index = 0; index < EquivocationDetectionService.MAX_PENDING_AUDIT_OBSERVATIONS; index++) {
					assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
				}
				assertThat(service.enqueueValidatedHeader(header, SEEN)).isFalse();
			});
			assertThat(registry.counter("blockchain.equivocation.audit.dropped").count()).isEqualTo(1);
		} finally {
			releaseRepository.countDown();
			service.close();
			registry.close();
		}
	}

	@Test
	void sandboxSizedAuditQueueRetainsTheSameBoundedDropSemantics() throws Exception {
		EquivocationEvidenceRepository repository = mock(EquivocationEvidenceRepository.class);
		when(repository.countConflicts()).thenReturn(0L);
		CountDownLatch repositoryEntered = new CountDownLatch(1);
		CountDownLatch releaseRepository = new CountDownLatch(1);
		when(repository.find(anyLong(), any())).thenAnswer(invocation -> {
			repositoryEntered.countDown();
			releaseRepository.await();
			return Optional.empty();
		});
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		EquivocationDetectionService service = new EquivocationDetectionService(repository, registry, 2);
		BlockHeader header = signedHeader(23, 1, KEY_A);
		try {
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
			assertThat(repositoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isFalse();
			assertThat(service.droppedAuditObservations()).isEqualTo(1);
		} finally {
			releaseRepository.countDown();
			service.close();
			registry.close();
		}
	}

	@Test
	void auditQueueOverrideCannotExceedProductionBound() {
		EquivocationEvidenceRepository repository = mock(EquivocationEvidenceRepository.class);
		when(repository.countConflicts()).thenReturn(0L);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try {
			assertThatThrownBy(() -> new EquivocationDetectionService(repository, registry, 0))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new EquivocationDetectionService(repository, registry, 1_025))
					.isInstanceOf(IllegalArgumentException.class);
		} finally {
			registry.close();
		}
	}

	@Test
	void productionRuntimeIgnoresSandboxAuditQueueOverride() {
		EquivocationEvidenceRepository repository = mock(EquivocationEvidenceRepository.class);
		when(repository.countConflicts()).thenReturn(0L);
		SandboxRuntimeContext runtimeContext = mock(SandboxRuntimeContext.class);
		when(runtimeContext.isSandbox()).thenReturn(false);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		EquivocationDetectionService service = new EquivocationDetectionService(
				repository, registry, 2, 30_000, 2, runtimeContext);
		try {
			ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "auditExecutor");
			assertThat(executor.getQueue().remainingCapacity())
					.isEqualTo(EquivocationDetectionService.MAX_PENDING_AUDIT_OBSERVATIONS);
			assertThat(ReflectionTestUtils.getField(service, "auditProcessingDelayMs")).isEqualTo(0L);
		} finally {
			service.close();
			registry.close();
		}
	}

	@Test
	void sandboxRuntimeSnapshotExposesEffectiveBoundedQueueConfigurationAndCounters() throws Exception {
		EquivocationEvidenceRepository repository = mock(EquivocationEvidenceRepository.class);
		when(repository.countConflicts()).thenReturn(0L);
		SandboxRuntimeContext runtimeContext = mock(SandboxRuntimeContext.class);
		when(runtimeContext.isSandbox()).thenReturn(true);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		EquivocationDetectionService service = new EquivocationDetectionService(
				repository, registry, 2, 60_000, 0, runtimeContext);
		BlockHeader header = signedHeader(24, 1, KEY_A);
		try {
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (service.runtimeSnapshot().startedObservations() == 0 && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isTrue();
			assertThat(service.enqueueValidatedHeader(header, SEEN)).isFalse();

			var snapshot = service.runtimeSnapshot();
			assertThat(snapshot.queueCapacity()).isEqualTo(2);
			assertThat(snapshot.processingDelayMs()).isEqualTo(60_000);
			assertThat(snapshot.delayAfterObservations()).isZero();
			assertThat(snapshot.submittedObservations()).isEqualTo(4);
			assertThat(snapshot.startedObservations()).isEqualTo(1);
			assertThat(snapshot.pendingObservations()).isEqualTo(2);
			assertThat(snapshot.droppedObservations()).isEqualTo(1);
		} finally {
			service.close();
			registry.close();
		}
	}

	@Test
	void orderingAndConcurrentObservationsAreDeterministicAndLossless() throws Exception {
		List<BlockHeader> headers = List.of(
				signedHeader(30, 31, KEY_A),
				signedHeader(30, 32, KEY_A),
				signedHeader(30, 33, KEY_A));
		List<Hash> expected = headers.stream().map(BlockHeader::getHash).sorted().toList();

		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("concurrent"));
				ExecutorService executor = Executors.newFixedThreadPool(headers.size())) {
			CountDownLatch ready = new CountDownLatch(headers.size());
			CountDownLatch start = new CountDownLatch(1);
			for (BlockHeader header : headers) {
				executor.submit(() -> {
					ready.countDown();
					start.await();
					store.service().enqueueValidatedHeader(header, SEEN);
					return null;
				});
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			store.service().close();

			List<Hash> actual = store.repository.findConflicts(100).getFirst().signedHeaders().stream()
					.map(EquivocationEvidence.SignedHeader::blockHash).toList();
			assertThat(actual).containsExactlyElementsOf(expected);
		}
	}

	@Test
	void unsignedObservationIsNotRecorded() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("invalid"))) {
			BlockHeader unsigned = unsignedHeader(40, 1);

			assertThat(store.service().observeValidatedHeader(unsigned, SEEN)).isFalse();
			assertThat(store.repository.statistics().singles()).isZero();
		}
	}

	@Test
	void sixtyFifthHeaderIsBoundedByDeterministicHashOrderAcrossArrivalOrders() throws Exception {
		List<BlockHeader> headers = new ArrayList<>();
		for (int index = 0; index < 65; index++) {
			headers.add(signedHeader(50, 1_000L + index, KEY_A));
		}
		List<Hash> expected = headers.stream().map(BlockHeader::getHash).sorted().limit(64).toList();

		List<Hash> forward;
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("cap-forward"))) {
			for (int index = 0; index < headers.size(); index++) {
				store.service().observeValidatedHeader(headers.get(index), SEEN.plusSeconds(index));
			}
			forward = store.repository.find(50, KEY_A.getAddress()).orElseThrow().signedHeaders().stream()
					.map(EquivocationEvidence.SignedHeader::blockHash).toList();
			assertThat(store.registry.counter("blockchain.equivocation.detections").count()).isEqualTo(1);
			assertThat(store.registry.get("blockchain.equivocation.evidence").gauge().value()).isEqualTo(1);
		}

		List<Hash> reverse;
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("cap-reverse"))) {
			for (int index = headers.size() - 1; index >= 0; index--) {
				store.service().observeValidatedHeader(headers.get(index), SEEN.plusSeconds(index));
			}
			reverse = store.repository.find(50, KEY_A.getAddress()).orElseThrow().signedHeaders().stream()
					.map(EquivocationEvidence.SignedHeader::blockHash).toList();
			assertThat(store.registry.counter("blockchain.equivocation.detections").count()).isEqualTo(1);
		}

		assertThat(forward).hasSize(64).containsExactlyElementsOf(expected);
		assertThat(reverse).containsExactlyElementsOf(expected);
	}

	@Test
	void evidenceCodecRejectsUnknownVersionTruncationAndTrailingBytes() {
		EquivocationEvidenceCodec codec = new EquivocationEvidenceCodec();
		BlockHeader header = signedHeader(60, 1, KEY_A);
		EquivocationEvidence evidence = new EquivocationEvidence(60, KEY_A.getAddress(),
				List.of(EquivocationEvidence.SignedHeader.from(header)), SEEN, SEEN);
		byte[] encoded = codec.encode(evidence);
		assertThat(ByteBuffer.wrap(encoded).getInt()).isEqualTo(1);

		byte[] unknownVersion = encoded.clone();
		unknownVersion[Integer.BYTES - 1] = 2;
		assertThat(catchThrowable(() -> codec.decode(unknownVersion)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported equivocation evidence version");
		assertThat(catchThrowable(
				() -> codec.decode(Arrays.copyOf(encoded, encoded.length - 1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("signed header length");
		assertThat(catchThrowable(
				() -> codec.decode(Arrays.copyOf(encoded, encoded.length + 1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("payload length");
	}

	@Test
	void conflictCountReadsEncodedSummaryWithoutCryptographicDecode() throws Exception {
		EquivocationEvidenceCodec codec = spy(new EquivocationEvidenceCodec());
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("fast-conflict-count"), codec)) {
			store.service().observeValidatedHeader(signedHeader(62, 1, KEY_A), SEEN);
			store.service().observeValidatedHeader(signedHeader(62, 2, KEY_A), SEEN.plusSeconds(1));
			store.service().observeValidatedHeader(signedHeader(63, 1, KEY_B), SEEN.plusSeconds(2));
			clearInvocations(codec);

			assertThat(store.repository.countConflicts()).isEqualTo(1);

			verify(codec, never()).isEncodedConflict(any());
			verify(codec, never()).decode(any());
			clearInvocations(codec);

			assertThat(store.repository.findConflicts(100)).hasSize(1);

			verify(codec, times(1)).isEncodedConflict(any());
			verify(codec).decode(any());
		}
	}

	@Test
	void conflictIndexSupportsDeterministicCursorPaginationWithoutScanningSingletons() throws Exception {
		EquivocationEvidenceCodec codec = spy(new EquivocationEvidenceCodec());
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("conflict-index-pagination"), codec)) {
			store.service().observeValidatedHeader(signedHeader(90, 1, KEY_A), SEEN);
			store.service().observeValidatedHeader(signedHeader(90, 2, KEY_A), SEEN.plusSeconds(1));
			store.service().observeValidatedHeader(signedHeader(91, 1, KEY_B), SEEN.plusSeconds(2));
			store.service().observeValidatedHeader(signedHeader(91, 2, KEY_B), SEEN.plusSeconds(3));
			store.service().observeValidatedHeader(signedHeader(92, 1, KEY_A), SEEN.plusSeconds(4));
			clearInvocations(codec);

			var first = store.repository.findConflictPage(null, 1);
			var second = store.repository.findConflictPage(first.nextCursor(), 1);
			var exhausted = store.repository.findConflictPage(second.nextCursor(), 1);

			assertThat(first.evidence()).extracting(EquivocationEvidence::height).containsExactly(90L);
			assertThat(second.evidence()).extracting(EquivocationEvidence::height).containsExactly(91L);
			assertThat(exhausted.evidence()).isEmpty();
			verify(codec, times(2)).isEncodedConflict(any());
			verify(codec, times(2)).decode(any());
		}
	}

	@Test
	void legacyDatabaseBackfillsChecksummedMetadataOnceAndRestartsInConstantTime() throws Exception {
		Path path = tempDir.resolve("metadata-migration");
		try (EvidenceStore store = EvidenceStore.open(path)) {
			store.service().observeValidatedHeader(signedHeader(70, 1, KEY_A), SEEN);
			store.service().observeValidatedHeader(signedHeader(70, 2, KEY_A), SEEN.plusSeconds(1));
			store.service().observeValidatedHeader(signedHeader(71, 1, KEY_B), SEEN.plusSeconds(2));
			store.deleteStorageMetadata();
			store.deleteStorageBarrier();
		}

		EquivocationEvidenceCodec migrationCodec = spy(new EquivocationEvidenceCodec());
		try (EvidenceStore migrated = EvidenceStore.open(path, migrationCodec)) {
			verify(migrationCodec, times(2)).isEncodedConflict(any());
			verify(migrationCodec, never()).decode(any());
			assertThat(migrated.repository.statistics())
					.isEqualTo(new EquivocationEvidenceRepository.StorageStatistics(1, 1, 71, 0, 0, -1));
			assertThat(migrated.repository.findConflicts(100)).hasSize(1);
		}

		EquivocationEvidenceCodec restartCodec = spy(new EquivocationEvidenceCodec());
		try (EvidenceStore restarted = EvidenceStore.open(path, restartCodec)) {
			verify(restartCodec, never()).isEncodedConflict(any());
			verify(restartCodec, never()).decode(any());
			assertThat(restarted.repository.countConflicts()).isEqualTo(1);
		}
	}

	@Test
	void corruptStorageMetadataIsDetectedAndRebuiltFromLegacyEvidence() throws Exception {
		Path path = tempDir.resolve("metadata-corruption");
		try (EvidenceStore store = EvidenceStore.open(path)) {
			store.service().observeValidatedHeader(signedHeader(72, 1, KEY_A), SEEN);
			store.service().observeValidatedHeader(signedHeader(72, 2, KEY_A), SEEN.plusSeconds(1));
			store.corruptStorageMetadata();
		}

		EquivocationEvidenceCodec codec = spy(new EquivocationEvidenceCodec());
		try (EvidenceStore repaired = EvidenceStore.open(path, codec)) {
			assertThat(repaired.repository.countConflicts()).isEqualTo(1);
			verify(codec, times(1)).isEncodedConflict(any());
			clearInvocations(codec);
			assertThat(repaired.repository.countConflicts()).isEqualTo(1);
			verify(codec, never()).isEncodedConflict(any());
		}
	}

	@Test
	void finiteRetentionPrunesOnlySinglesAndKeepsConflictsPermanently() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("retention"), 3, 1)) {
			store.service().observeValidatedHeader(signedHeader(1, 1, KEY_A), SEEN);
			store.service().observeValidatedHeader(signedHeader(1, 2, KEY_A), SEEN.plusSeconds(1));
			store.service().observeValidatedHeader(signedHeader(2, 1, KEY_B), SEEN.plusSeconds(2));
			store.service().observeValidatedHeader(signedHeader(10, 1, KEY_A), SEEN.plusSeconds(3));

			assertThat(store.repository.find(1, KEY_A.getAddress())).isPresent();
			assertThat(store.repository.find(2, KEY_B.getAddress())).isPresent();

			store.service().observeValidatedHeader(signedHeader(2, 2, KEY_B), SEEN.plusSeconds(4));
			assertThat(store.repository.find(2, KEY_B.getAddress()).orElseThrow().isConflict()).isTrue();

			store.service().observeValidatedHeader(signedHeader(11, 1, KEY_A), SEEN.plusSeconds(5));
			assertThat(store.repository.find(1, KEY_A.getAddress()).orElseThrow().isConflict()).isTrue();
			assertThat(store.repository.find(2, KEY_B.getAddress()).orElseThrow().isConflict()).isTrue();
			assertThat(store.repository.countConflicts()).isEqualTo(2);
			assertThat(store.repository.statistics().singles()).isEqualTo(2);

			store.service().observeValidatedHeader(signedHeader(3, 1, KEY_B), SEEN.plusSeconds(6));
			assertThat(store.repository.find(3, KEY_B.getAddress())).isEmpty();
		}
	}

	@Test
	void finiteRetentionIncrementallyPrunesLegacySingletons() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("legacy-retention"), 3, 100)) {
			store.service().observeValidatedHeader(signedHeader(1, 1, KEY_A), SEEN);
			store.service().observeValidatedHeader(signedHeader(1, 2, KEY_A), SEEN.plusSeconds(1));
			store.service().observeValidatedHeader(signedHeader(2, 1, KEY_B), SEEN.plusSeconds(2));
			store.service().observeValidatedHeader(signedHeader(10, 1, KEY_A), SEEN.plusSeconds(3));

			assertThat(store.repository.find(1, KEY_A.getAddress()).orElseThrow().isConflict()).isTrue();
			assertThat(store.repository.find(2, KEY_B.getAddress())).isEmpty();
			assertThat(store.repository.statistics())
					.isEqualTo(new EquivocationEvidenceRepository.StorageStatistics(1, 1, 10, 3, 0, 8));
		}
	}

	@Test
	void defaultRetentionPreservesLegacyUnboundedSingletonSemantics() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("unbounded-retention"))) {
			store.service().observeValidatedHeader(signedHeader(1, 1, KEY_A), SEEN);
			store.service().observeValidatedHeader(signedHeader(1_000_000, 1, KEY_A), SEEN.plusSeconds(1));

			assertThat(store.repository.find(1, KEY_A.getAddress())).isPresent();
			assertThat(store.repository.statistics().singles()).isEqualTo(2);
		}
	}

	@Test
	void retentionPolicyChangesResetGenerationAndResumeFromASafeCursor() throws Exception {
		Path path = tempDir.resolve("retention-policy-changes");
		try (EvidenceStore unbounded = EvidenceStore.open(path)) {
			unbounded.service().observeValidatedHeader(signedHeader(1, 1, KEY_A), SEEN);
			unbounded.service().observeValidatedHeader(signedHeader(10, 1, KEY_A), SEEN.plusSeconds(1));
			assertThat(unbounded.repository.statistics().retentionGeneration()).isZero();
		}

		try (EvidenceStore narrowed = EvidenceStore.open(path, 3, 100)) {
			assertThat(narrowed.repository.statistics().retentionGeneration()).isEqualTo(1);
			assertThat(narrowed.repository.find(1, KEY_A.getAddress())).isPresent();
			narrowed.service().observeValidatedHeader(signedHeader(11, 1, KEY_A), SEEN.plusSeconds(2));
			assertThat(narrowed.repository.find(1, KEY_A.getAddress())).isEmpty();
			assertThat(narrowed.repository.statistics().pruneCutoff()).isEqualTo(9);
		}

		try (EvidenceStore disabled = EvidenceStore.open(path, 0, 100)) {
			assertThat(disabled.repository.statistics().retentionGeneration()).isEqualTo(2);
			assertThat(disabled.repository.statistics().pruneCutoff()).isEqualTo(-1);
			disabled.service().observeValidatedHeader(signedHeader(2, 1, KEY_B), SEEN.plusSeconds(3));
			assertThat(disabled.repository.find(2, KEY_B.getAddress())).isPresent();
		}

		try (EvidenceStore widened = EvidenceStore.open(path, 20, 100)) {
			assertThat(widened.repository.statistics().retentionGeneration()).isEqualTo(3);
			widened.service().observeValidatedHeader(signedHeader(3, 1, KEY_B), SEEN.plusSeconds(4));
			assertThat(widened.repository.find(2, KEY_B.getAddress())).isPresent();
			assertThat(widened.repository.find(3, KEY_B.getAddress())).isPresent();
		}

		try (EvidenceStore narrowedAgain = EvidenceStore.open(path, 5, 100)) {
			assertThat(narrowedAgain.repository.statistics().retentionGeneration()).isEqualTo(4);
			narrowedAgain.service().observeValidatedHeader(signedHeader(12, 1, KEY_A), SEEN.plusSeconds(5));
			assertThat(narrowedAgain.repository.find(2, KEY_B.getAddress())).isEmpty();
			assertThat(narrowedAgain.repository.find(3, KEY_B.getAddress())).isEmpty();
			assertThat(narrowedAgain.repository.statistics().pruneCutoff()).isEqualTo(8);
		}
	}

	@Test
	void oneWayStorageBarrierRejectsLegacyStartupBeforeItCanWrite() throws Exception {
		Path path = tempDir.resolve("storage-barrier");
		try (EvidenceStore upgraded = EvidenceStore.open(path)) {
			upgraded.service().observeValidatedHeader(signedHeader(80, 1, KEY_A), SEEN);
			assertThatThrownBy(() -> upgraded.simulateLegacyStartupAndWrite(signedHeader(81, 1, KEY_B)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Unsupported equivocation evidence version: 2");
			assertThat(upgraded.repository.find(81, KEY_B.getAddress())).isEmpty();
		}

		try (EvidenceStore current = EvidenceStore.open(path)) {
			assertThat(current.repository.statistics().singles()).isEqualTo(1);
		}
	}

	@Test
	void bypassedLegacyWriteSelfHealsIndexesBeforeConflictTransition() throws Exception {
		Path path = tempDir.resolve("legacy-write-self-heal");
		BlockHeader first = signedHeader(82, 1, KEY_B);
		BlockHeader second = signedHeader(82, 2, KEY_B);
		try (EvidenceStore upgraded = EvidenceStore.open(path)) {
			upgraded.putLegacySingleton(first);
		}

		try (EvidenceStore reopened = EvidenceStore.open(path)) {
			assertThat(reopened.service().observeValidatedHeader(second, SEEN.plusSeconds(1))).isTrue();
			assertThat(reopened.repository.countConflicts()).isEqualTo(1);
			assertThat(reopened.repository.statistics().singles()).isZero();
			assertThat(reopened.repository.findConflicts(100)).hasSize(1);
		}
	}

	@Test
	void failedAtomicWriteChangesNeitherEvidenceNorPersistedCounter() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("atomic-write"))) {
			BlockHeader first = signedHeader(73, 1, KEY_A);
			BlockHeader second = signedHeader(73, 2, KEY_A);
			store.service().observeValidatedHeader(first, SEEN);

			store.failNextWrite();
			assertThat(store.service().observeValidatedHeader(second, SEEN.plusSeconds(1))).isFalse();
			assertThat(store.repository.find(73, KEY_A.getAddress()).orElseThrow().signedHeaders()).hasSize(1);
			assertThat(store.repository.countConflicts()).isZero();
			assertThat(store.repository.findConflicts(100)).isEmpty();
			assertThat(store.repository.statistics().singles()).isEqualTo(1);

			assertThat(store.service().observeValidatedHeader(second, SEEN.plusSeconds(2))).isTrue();
			assertThat(store.repository.find(73, KEY_A.getAddress()).orElseThrow().signedHeaders()).hasSize(2);
			assertThat(store.repository.countConflicts()).isEqualTo(1);
			assertThat(store.repository.findConflicts(100)).hasSize(1);
		}
	}

	@Test
	void permanentConflictCannotBeDowngradedToASingleObservation() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("conflict-downgrade"))) {
			BlockHeader first = signedHeader(75, 1, KEY_A);
			BlockHeader second = signedHeader(75, 2, KEY_A);
			store.service().observeValidatedHeader(first, SEEN);
			store.service().observeValidatedHeader(second, SEEN.plusSeconds(1));
			EquivocationEvidence singleton = new EquivocationEvidence(
					75, KEY_A.getAddress(), List.of(EquivocationEvidence.SignedHeader.from(first)), SEEN, SEEN);

			assertThatThrownBy(() -> store.repository.save(singleton))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot be downgraded");
			assertThat(store.repository.find(75, KEY_A.getAddress()).orElseThrow().signedHeaders()).hasSize(2);
			assertThat(store.repository.countConflicts()).isEqualTo(1);
		}
	}

	@Test
	void liveObservationRecoversCanonicalSignerExactlyOnceWithoutValidateCall() throws Exception {
		BlockHeaderImpl signed = (BlockHeaderImpl) signedHeader(74, 1, KEY_A);
		CountingSignature signature = new CountingSignature(signed.getSignature());
		BlockHeaderImpl observed = spy(signed);
		doReturn(signature).when(observed).getSignature();

		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("single-recovery"))) {
			assertThat(store.service().observeValidatedHeader(observed, SEEN)).isFalse();
			verify(observed, never()).getIdentity();
			assertThat(signature.recoveries).isEqualTo(1);
			assertThat(signature.validations).isZero();
			assertThat(store.repository.find(74, KEY_A.getAddress())).isPresent();
		}
	}

	@Test
	void spoofedIdentityAccessorCannotChangeTheCryptographicallyRecoveredStorageKey() throws Exception {
		BlockHeader unsigned = unsignedHeader(76, 1);
		Signature signature = KEY_A.sign(BlockHeaderUtil.hashForSigning(unsigned));
		BlockHeader spoofed = mock(BlockHeader.class);
		when(spoofed.getVersion()).thenReturn(unsigned.getVersion());
		when(spoofed.getHeight()).thenReturn(unsigned.getHeight());
		when(spoofed.getTimestamp()).thenReturn(unsigned.getTimestamp());
		when(spoofed.getPreviousHash()).thenReturn(unsigned.getPreviousHash());
		when(spoofed.getTxRootHash()).thenReturn(unsigned.getTxRootHash());
		when(spoofed.getStateRootHash()).thenReturn(unsigned.getStateRootHash());
		when(spoofed.getDifficulty()).thenReturn(unsigned.getDifficulty());
		when(spoofed.getCoinbase()).thenReturn(unsigned.getCoinbase());
		when(spoofed.getNonce()).thenReturn(unsigned.getNonce());
		when(spoofed.getSignature()).thenReturn(signature);
		when(spoofed.getIdentity()).thenReturn(KEY_B.getAddress());

		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("spoofed-identity"))) {
			assertThat(store.service().observeValidatedHeader(spoofed, SEEN)).isFalse();
			assertThat(store.repository.find(76, KEY_A.getAddress())).isPresent();
			assertThat(store.repository.find(76, KEY_B.getAddress())).isEmpty();
			verify(spoofed, never()).getIdentity();
		}
	}

	@Test
	void encodedConflictSummaryRejectsInvalidMetadataWithoutVerifyingHeaders() {
		EquivocationEvidenceCodec codec = new EquivocationEvidenceCodec();
		BlockHeader first = signedHeader(63, 1, KEY_A);
		BlockHeader second = signedHeader(63, 2, KEY_A);
		byte[] single = codec.encode(new EquivocationEvidence(
				63, KEY_A.getAddress(), List.of(EquivocationEvidence.SignedHeader.from(first)), SEEN, SEEN));
		byte[] conflict = codec.encode(new EquivocationEvidence(
				63, KEY_A.getAddress(), List.of(
						EquivocationEvidence.SignedHeader.from(first),
						EquivocationEvidence.SignedHeader.from(second)).stream()
						.sorted((left, right) -> left.blockHash().compareTo(right.blockHash()))
						.toList(), SEEN, SEEN.plusSeconds(1)));

		assertThat(codec.isEncodedConflict(single)).isFalse();
		assertThat(codec.isEncodedConflict(conflict)).isTrue();

		byte[] unknownVersion = conflict.clone();
		unknownVersion[Integer.BYTES - 1] = 2;
		assertThatThrownBy(() -> codec.isEncodedConflict(unknownVersion))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported equivocation evidence version");
		assertThatThrownBy(() -> codec.isEncodedConflict(Arrays.copyOf(conflict, 60)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid equivocation evidence length");
	}

	@Test
	void evidenceCodecRejectsTamperedCanonicalSignedHeader() {
		EquivocationEvidenceCodec codec = new EquivocationEvidenceCodec();
		BlockHeader header = signedHeader(61, 1, KEY_A);
		EquivocationEvidence evidence = new EquivocationEvidence(61, KEY_A.getAddress(),
				List.of(EquivocationEvidence.SignedHeader.from(header)), SEEN, SEEN);
		byte[] encoded = codec.encode(evidence);
		encoded[encoded.length - 1] ^= 1;

		assertThat(catchThrowable(() -> codec.decode(encoded)))
				.isInstanceOf(RuntimeException.class);
	}

	private static BlockHeader signedHeader(long height, long nonce, PrivateKey key) {
		BlockHeaderImpl unsigned = unsignedHeader(height, nonce);
		return unsigned.toBuilder().signature(key.sign(BlockHeaderUtil.hashForSigning(unsigned))).build();
	}

	private static BlockHeaderImpl unsignedHeader(long height, long nonce) {
		return BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(height)
				.timestamp(SEEN.plusSeconds(height))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE)
				.coinbase(KEY_A.getAddress())
				.nonce(nonce)
				.build();
	}

	private static PrivateKey key(long value) {
		return PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", value)));
	}

	private static final class CountingSignature extends Signature {
		private int recoveries;
		private int validations;

		private CountingSignature(Signature signature) {
			super(signature.copy(), false);
		}

		@Override
		public Address recoverAddress(Hash hash) throws CryptoJException {
			recoveries++;
			return super.recoverAddress(hash);
		}

		@Override
		public boolean validate(Hash hash, Address address) {
			validations++;
			return super.validate(hash, address);
		}
	}

	private static final class EvidenceStore implements AutoCloseable {
		private final RocksDB database;
		private final List<ColumnFamilyHandle> handles;
		private final List<ColumnFamilyOptions> options;
		private final DBOptions dbOptions;
		private final EquivocationEvidenceRepository repository;
		private final FailingRocksDBRepository rocksRepository;
		private final EquivocationDetectionService service;
		private final SimpleMeterRegistry registry;

		private EvidenceStore(RocksDB database, List<ColumnFamilyHandle> handles,
				List<ColumnFamilyOptions> options, DBOptions dbOptions,
				EquivocationEvidenceRepository repository, FailingRocksDBRepository rocksRepository,
				EquivocationDetectionService service,
				SimpleMeterRegistry registry) {
			this.database = database;
			this.handles = handles;
			this.options = options;
			this.dbOptions = dbOptions;
			this.repository = repository;
			this.rocksRepository = rocksRepository;
			this.service = service;
			this.registry = registry;
		}

		static EvidenceStore open(Path path) throws Exception {
			return open(path, new EquivocationEvidenceCodec());
		}

		static EvidenceStore open(Path path, EquivocationEvidenceCodec codec) throws Exception {
			return open(path, codec, 0, 1_000);
		}

		static EvidenceStore open(Path path, long retentionBlocks, int pruneBatchSize) throws Exception {
			return open(path, new EquivocationEvidenceCodec(), retentionBlocks, pruneBatchSize);
		}

		static EvidenceStore open(
				Path path,
				EquivocationEvidenceCodec codec,
				long retentionBlocks,
				int pruneBatchSize) throws Exception {
			RocksDB.loadLibrary();
			List<ColumnFamilyOptions> options = List.of(
					new ColumnFamilyOptions(), new ColumnFamilyOptions(), new ColumnFamilyOptions());
			List<ColumnFamilyDescriptor> descriptors = List.of(
					new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, options.get(0)),
					new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_METADATA
							.getBytes(StandardCharsets.UTF_8), options.get(1)),
					new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_EQUIVOCATIONS
							.getBytes(StandardCharsets.UTF_8), options.get(2)));
			List<ColumnFamilyHandle> handles = new ArrayList<>();
			DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
			RocksDB database = RocksDB.open(dbOptions, path.toString(), descriptors, handles);
			RocksDbColumnFamilies families = new RocksDbColumnFamilies();
			families.addHandle("default", handles.get(0));
			families.addHandle(RocksDbColumnFamilies.CF_METADATA, handles.get(1));
			families.addHandle(RocksDbColumnFamilies.CF_EQUIVOCATIONS, handles.get(2));
			FailingRocksDBRepository rocksRepository = new FailingRocksDBRepository(database, families);
			EquivocationProperties properties = new EquivocationProperties();
			properties.setSingleObservationRetentionBlocks(retentionBlocks);
			properties.setPruneBatchSize(pruneBatchSize);
			EquivocationEvidenceRepository repository = new EquivocationEvidenceRepository(
					rocksRepository, families, codec, properties);
			SimpleMeterRegistry registry = new SimpleMeterRegistry();
			EquivocationDetectionService service = new EquivocationDetectionService(repository, registry);
			return new EvidenceStore(
					database, handles, options, dbOptions, repository, rocksRepository, service, registry);
		}

		EquivocationDetectionService service() {
			return service;
		}

		void failNextWrite() {
			rocksRepository.failNextWrite = true;
		}

		void deleteStorageMetadata() throws RocksDBException {
			try (var iterator = database.newIterator(handles.get(1))) {
				iterator.seekToFirst();
				while (iterator.isValid()) {
					database.delete(handles.get(1), iterator.key());
					iterator.next();
				}
				iterator.status();
			}
		}

		void deleteStorageBarrier() throws RocksDBException {
			try (var iterator = database.newIterator(handles.get(2))) {
				for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
					if (iterator.key().length != Long.BYTES + Address.SIZE) {
						database.delete(handles.get(2), iterator.key());
						return;
					}
				}
				throw new IllegalStateException("Equivocation storage barrier is missing");
			}
		}

		void corruptStorageMetadata() throws RocksDBException {
			try (var iterator = database.newIterator(handles.get(1))) {
				for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
					byte[] value = iterator.value();
					if (value.length > Long.BYTES) {
						byte[] corrupted = value.clone();
						corrupted[corrupted.length - 1] ^= 1;
						database.put(handles.get(1), iterator.key(), corrupted);
						return;
					}
				}
				throw new IllegalStateException("Equivocation storage metadata is missing");
			}
		}

		void simulateLegacyStartupAndWrite(BlockHeader header) throws RocksDBException {
			EquivocationEvidenceCodec legacyCodec = new EquivocationEvidenceCodec();
			try (var iterator = database.newIterator(handles.get(2))) {
				for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
					legacyCodec.decode(iterator.value());
				}
				iterator.status();
			}
			putLegacySingleton(header);
		}

		void putLegacySingleton(BlockHeader header) throws RocksDBException {
			EquivocationEvidence evidence = new EquivocationEvidence(
					header.getHeight(), header.getIdentity(),
					List.of(EquivocationEvidence.SignedHeader.from(header)), SEEN, SEEN);
			byte[] key = ByteBuffer.allocate(Long.BYTES + Address.SIZE)
					.putLong(header.getHeight())
					.put(header.getIdentity().toArray())
					.array();
			database.put(handles.get(2), key, new EquivocationEvidenceCodec().encode(evidence));
		}

		@Override
		public void close() {
			service.close();
			registry.close();
			handles.forEach(ColumnFamilyHandle::close);
			database.close();
			options.forEach(ColumnFamilyOptions::close);
			dbOptions.close();
		}
	}

	private static final class FailingRocksDBRepository extends RocksDBRepository {
		private boolean failNextWrite;

		private FailingRocksDBRepository(RocksDB blockchainDB, RocksDbColumnFamilies columnFamilies) {
			super(blockchainDB, columnFamilies);
		}

		@Override
		public void executeAtomicBatch(BatchOperation operation) {
			if (failNextWrite) {
				failNextWrite = false;
				throw new IllegalStateException("Injected atomic write failure");
			}
			super.executeAtomicBatch(operation);
		}
	}
}
