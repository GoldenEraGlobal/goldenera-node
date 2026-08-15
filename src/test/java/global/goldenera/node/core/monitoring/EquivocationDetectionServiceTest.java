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
import static org.mockito.Mockito.mock;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
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
	void invalidSignatureIdentityPairIsNotRecorded() throws Exception {
		try (EvidenceStore store = EvidenceStore.open(tempDir.resolve("invalid"))) {
			BlockHeader unsigned = unsignedHeader(40, 1);
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
			when(spoofed.getHash()).thenReturn(Hash.hash(Bytes32.fromHexString("0x01")));

			assertThat(store.service().observeValidatedHeader(spoofed, SEEN)).isFalse();
			assertThat(store.repository.find(40, KEY_B.getAddress())).isEmpty();
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

	private static final class EvidenceStore implements AutoCloseable {
		private final RocksDB database;
		private final List<ColumnFamilyHandle> handles;
		private final List<ColumnFamilyOptions> options;
		private final DBOptions dbOptions;
		private final EquivocationEvidenceRepository repository;
		private final EquivocationDetectionService service;
		private final SimpleMeterRegistry registry;

		private EvidenceStore(RocksDB database, List<ColumnFamilyHandle> handles,
				List<ColumnFamilyOptions> options, DBOptions dbOptions,
				EquivocationEvidenceRepository repository, EquivocationDetectionService service,
				SimpleMeterRegistry registry) {
			this.database = database;
			this.handles = handles;
			this.options = options;
			this.dbOptions = dbOptions;
			this.repository = repository;
			this.service = service;
			this.registry = registry;
		}

		static EvidenceStore open(Path path) throws Exception {
			RocksDB.loadLibrary();
			List<ColumnFamilyOptions> options = List.of(new ColumnFamilyOptions(), new ColumnFamilyOptions());
			List<ColumnFamilyDescriptor> descriptors = List.of(
					new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, options.get(0)),
					new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_EQUIVOCATIONS
							.getBytes(StandardCharsets.UTF_8), options.get(1)));
			List<ColumnFamilyHandle> handles = new ArrayList<>();
			DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
			RocksDB database = RocksDB.open(dbOptions, path.toString(), descriptors, handles);
			RocksDbColumnFamilies families = new RocksDbColumnFamilies();
			families.addHandle("default", handles.get(0));
			families.addHandle(RocksDbColumnFamilies.CF_EQUIVOCATIONS, handles.get(1));
			RocksDBRepository rocksRepository = new RocksDBRepository(database, families);
			EquivocationEvidenceRepository repository = new EquivocationEvidenceRepository(
					rocksRepository, families, new EquivocationEvidenceCodec());
			SimpleMeterRegistry registry = new SimpleMeterRegistry();
			EquivocationDetectionService service = new EquivocationDetectionService(repository, registry);
			return new EvidenceStore(database, handles, options, dbOptions, repository, service, registry);
		}

		EquivocationDetectionService service() {
			return service;
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
}
