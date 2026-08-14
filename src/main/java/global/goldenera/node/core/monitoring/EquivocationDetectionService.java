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
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.storage.blockchain.EquivocationEvidenceRepository;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence;
import global.goldenera.node.core.storage.blockchain.domain.EquivocationEvidence.SignedHeader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;

/** Detects signed same-height conflicts without changing block validity. */
@Service
@Slf4j
public class EquivocationDetectionService {

	static final int MAX_SIGNED_HEADERS_PER_EVIDENCE = 64;
	private static final Comparator<SignedHeader> HEADER_ORDER = Comparator
			.comparing(header -> header.blockHash().toHexString());

	private final EquivocationEvidenceRepository repository;
	private final AtomicLong evidenceCount;
	private final Counter detectionCounter;

	public EquivocationDetectionService(EquivocationEvidenceRepository repository, MeterRegistry registry) {
		this.repository = repository;
		this.evidenceCount = new AtomicLong(repository.countConflicts());
		this.detectionCounter = registry.counter("blockchain.equivocation.detections");
		registry.gauge("blockchain.equivocation.evidence", evidenceCount);
	}

	/**
	 * Records a header only when its signature cryptographically authenticates its
	 * recovered identity. The caller is responsible for stateless PoW/header
	 * validation before invoking this method.
	 *
	 * @return true only when this observation creates new equivocation evidence
	 */
	public synchronized boolean observeValidatedHeader(BlockHeader header, Instant seenAt) {
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
			observations.add(new SignedHeader(blockHash, signature));
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
