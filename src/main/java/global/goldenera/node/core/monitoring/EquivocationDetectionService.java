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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.storage.blockchain.EquivocationEvidenceRepository;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence.SignedHeader;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/** Detects signed same-height conflicts without changing block validity. */
@Service
@Slf4j
public class EquivocationDetectionService {

	static final int MAX_SIGNED_HEADERS_PER_EVIDENCE = 64;
	static final int MAX_PENDING_AUDIT_OBSERVATIONS = 1_024;
	static final int MIN_PENDING_AUDIT_OBSERVATIONS = 1;
	static final long MAX_SANDBOX_AUDIT_DELAY_MS = 60_000;
	private static final Comparator<SignedHeader> HEADER_ORDER = Comparator
			.comparing(header -> header.blockHash().toHexString());

	private final EquivocationEvidenceRepository repository;
	private final AtomicLong evidenceCount;
	private final Counter detectionCounter;
	private final Counter droppedCounter;
	private final ThreadPoolExecutor auditExecutor;
	private final int auditQueueCapacity;
	private final long auditProcessingDelayMs;
	private final long auditDelayAfterObservations;
	private final AtomicLong submittedAuditObservations = new AtomicLong();
	private final AtomicLong startedAuditObservations = new AtomicLong();

	public EquivocationDetectionService(EquivocationEvidenceRepository repository, MeterRegistry registry) {
		this(repository, registry, MAX_PENDING_AUDIT_OBSERVATIONS, 0, 64);
	}

	@Autowired
	public EquivocationDetectionService(
			EquivocationEvidenceRepository repository,
			MeterRegistry registry,
			@Value("${ge.equivocation.audit-queue-capacity:1024}") int configuredAuditQueueCapacity,
			@Value("${ge.equivocation.audit-processing-delay-ms:0}") long configuredAuditProcessingDelayMs,
			@Value("${ge.equivocation.audit-delay-after-observations:0}") long configuredDelayAfterObservations,
			SandboxRuntimeContext runtimeContext) {
		this(repository, registry, runtimeContext.isSandbox()
				? configuredAuditQueueCapacity : MAX_PENDING_AUDIT_OBSERVATIONS,
				runtimeContext.isSandbox() ? configuredAuditProcessingDelayMs : 0,
				runtimeContext.isSandbox() ? configuredDelayAfterObservations : 64);
	}

	EquivocationDetectionService(
			EquivocationEvidenceRepository repository,
			MeterRegistry registry,
			int auditQueueCapacity) {
		this(repository, registry, auditQueueCapacity, 0, 64);
	}

	private EquivocationDetectionService(
			EquivocationEvidenceRepository repository,
			MeterRegistry registry,
			int auditQueueCapacity,
			long auditProcessingDelayMs,
			long auditDelayAfterObservations) {
		if (auditQueueCapacity < MIN_PENDING_AUDIT_OBSERVATIONS
				|| auditQueueCapacity > MAX_PENDING_AUDIT_OBSERVATIONS) {
			throw new IllegalArgumentException("Equivocation audit queue capacity must be between 1 and 1024");
		}
		if (auditProcessingDelayMs < 0 || auditProcessingDelayMs > MAX_SANDBOX_AUDIT_DELAY_MS) {
			throw new IllegalArgumentException("Equivocation sandbox audit delay must be between 0 and 60000 ms");
		}
		if (auditDelayAfterObservations < 0 || auditDelayAfterObservations > 64) {
			throw new IllegalArgumentException("Equivocation sandbox audit delay threshold must be between 0 and 64");
		}
		this.auditQueueCapacity = auditQueueCapacity;
		this.auditProcessingDelayMs = auditProcessingDelayMs;
		this.auditDelayAfterObservations = auditDelayAfterObservations;
		this.repository = repository;
		this.evidenceCount = new AtomicLong(repository.countConflicts());
		this.detectionCounter = registry.counter("blockchain.equivocation.detections");
		this.droppedCounter = registry.counter("blockchain.equivocation.audit.dropped");
		ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();
		this.auditExecutor = new ThreadPoolExecutor(
				1, 1, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(auditQueueCapacity),
				runnable -> {
					Thread thread = defaultThreadFactory.newThread(runnable);
					thread.setName("equivocation-audit");
					thread.setDaemon(true);
					return thread;
				});
		registry.gauge("blockchain.equivocation.evidence", evidenceCount);
	}

	/**
	 * Enqueues audit work without allowing monitoring storage latency to block a
	 * consensus or reorg thread.
	 *
	 * @return false when the bounded audit queue is full or shutting down
	 */
	public boolean enqueueValidatedHeader(BlockHeader header, Instant seenAt) {
		submittedAuditObservations.incrementAndGet();
		try {
			auditExecutor.execute(() -> delayedObserveValidatedHeader(header, seenAt));
			return true;
		} catch (RejectedExecutionException e) {
			droppedCounter.increment();
			return false;
		}
	}

	private void delayedObserveValidatedHeader(BlockHeader header, Instant seenAt) {
		long sequence = startedAuditObservations.incrementAndGet();
		if (auditProcessingDelayMs > 0 && sequence > auditDelayAfterObservations) {
			try {
				Thread.sleep(auditProcessingDelayMs);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				droppedCounter.increment();
				return;
			}
		}
		observeValidatedHeader(header, seenAt);
	}

	public long droppedAuditObservations() {
		return (long) droppedCounter.count();
	}

	public AuditRuntimeSnapshot runtimeSnapshot() {
		return new AuditRuntimeSnapshot(
				auditQueueCapacity,
				auditProcessingDelayMs,
				auditDelayAfterObservations,
				submittedAuditObservations.get(),
				startedAuditObservations.get(),
				auditExecutor.getQueue().size(),
				droppedAuditObservations());
	}

	public record AuditRuntimeSnapshot(
			int queueCapacity,
			long processingDelayMs,
			long delayAfterObservations,
			long submittedObservations,
			long startedObservations,
			int pendingObservations,
			long droppedObservations) {
	}

	@PreDestroy
	public void close() {
		auditExecutor.shutdown();
		try {
			if (!auditExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				int abandoned = auditExecutor.shutdownNow().size();
				droppedCounter.increment(abandoned);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			int abandoned = auditExecutor.shutdownNow().size();
			droppedCounter.increment(abandoned);
		}
	}

	/**
	 * Records a header only when its signature cryptographically authenticates its
	 * recovered identity. The caller is responsible for stateless PoW/header
	 * validation before invoking this method.
	 *
	 * @return true only when this observation creates new equivocation evidence
	 */
	synchronized boolean observeValidatedHeader(BlockHeader header, Instant seenAt) {
		try {
			Signature signature = header.getSignature();
			Address identity = header.getIdentity();
			if (signature == null || signature.equals(Signature.ZERO)
					|| identity == null || identity.equals(Address.ZERO)
					|| !signature.validate(BlockHeaderUtil.hashForSigning(header), identity)) {
				log.debug("Ignoring unauthenticated equivocation observation at height {}", header.getHeight());
				return false;
			}

			Hash blockHash = header.getHash();
			EquivocationEvidence existing = repository.find(header.getHeight(), identity).orElse(null);
			if (existing != null && existing.signedHeaders().stream()
					.anyMatch(observation -> observation.blockHash().equals(blockHash))) {
				return false;
			}

			List<SignedHeader> observations = new ArrayList<>();
			if (existing != null) {
				observations.addAll(existing.signedHeaders());
			}
			observations.add(SignedHeader.from(header));
			observations.sort(HEADER_ORDER);
			if (observations.size() > MAX_SIGNED_HEADERS_PER_EVIDENCE) {
				observations = new ArrayList<>(observations.subList(0, MAX_SIGNED_HEADERS_PER_EVIDENCE));
			}
			if (existing != null && observations.equals(existing.signedHeaders())) {
				return false;
			}

			Instant firstSeenAt = existing == null || seenAt.isBefore(existing.firstSeenAt())
					? seenAt : existing.firstSeenAt();
			Instant lastSeenAt = existing == null || seenAt.isAfter(existing.lastSeenAt())
					? seenAt : existing.lastSeenAt();
			EquivocationEvidence updated = new EquivocationEvidence(
					header.getHeight(), identity, observations, firstSeenAt, lastSeenAt);
			repository.save(updated);

			boolean newlyDetected = existing != null && !existing.isConflict() && updated.isConflict();
			if (newlyDetected) {
				evidenceCount.incrementAndGet();
				detectionCounter.increment();
				log.warn("Equivocation detected at height {} for validator {} ({} distinct signed headers)",
						header.getHeight(), identity.toChecksumAddress(), observations.size());
			}
			return newlyDetected;
		} catch (Exception e) {
			// Monitoring storage must never become an extra consensus rejection rule.
			log.error("Failed to record equivocation observation at height {}", header.getHeight(), e);
			return false;
		}
	}
}
