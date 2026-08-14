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
package global.goldenera.node.core.sandbox.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import global.goldenera.node.core.mining.ExactOneMiningOutcome;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Operation;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Outcome;
import global.goldenera.node.core.sync.BlockIngestionOutcome;

import jakarta.annotation.PreDestroy;

final class SandboxControlOperationRegistry {

	static final int MAX_ENTRIES = 1024;
	static final Duration ENTRY_TTL = Duration.ofMinutes(15);
	static final Duration TERMINAL_GRACE = Duration.ofSeconds(1);
	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

	private final Map<String, Entry> byKey = new LinkedHashMap<>();
	private final Map<String, Entry> byOperationId = new HashMap<>();
	private final String incarnationId = randomIdentifier();
	private final ScheduledExecutorService deadlineExecutor;
	private final SandboxControlAuditLog auditLog;
	private boolean mutationActive;

	SandboxControlOperationRegistry(SandboxControlAuditLog auditLog) {
		this.auditLog = auditLog;
		deadlineExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "sandbox-control-deadline");
			thread.setDaemon(true);
			return thread;
		});
	}

	synchronized Admission admit(String idempotencyKey, String canonicalBody, Duration operationDeadline) {
		validateIdempotencyKey(idempotencyKey);
		if (operationDeadline == null || operationDeadline.isNegative() || operationDeadline.isZero()
				|| operationDeadline.compareTo(ENTRY_TTL) > 0) {
			throw new IllegalArgumentException("operationDeadline must be positive and no longer than the registry TTL");
		}
		cleanupExpired();
		String keyDigest = digest(idempotencyKey);
		String bodyDigest = digest(canonicalBody);
		Entry existing = byKey.get(keyDigest);
		if (existing != null) {
			return MessageDigest.isEqual(
					existing.bodyDigest.getBytes(StandardCharsets.US_ASCII),
					bodyDigest.getBytes(StandardCharsets.US_ASCII))
					? new Admission(AdmissionKind.REPLAY, existing.operationId, existing.keyDigest, snapshot(existing))
					: new Admission(AdmissionKind.CONFLICT, existing.operationId, existing.keyDigest, null);
		}
		if (mutationActive) {
			return new Admission(AdmissionKind.BUSY, null, null, null);
		}
		ensureCapacity();
		if (byKey.size() >= MAX_ENTRIES) {
			return new Admission(AdmissionKind.CAPACITY, null, null, null);
		}
		String operationId = incarnationId + "." + randomIdentifier();
		Entry entry = new Entry(
				keyDigest,
				bodyDigest,
				operationId,
				Instant.now());
		byKey.put(keyDigest, entry);
		byOperationId.put(operationId, entry);
		mutationActive = true;
		long terminalDelayNanos = saturatedAdd(operationDeadline.toNanos(), TERMINAL_GRACE.toNanos());
		entry.deadlineTask = deadlineExecutor.schedule(
				() -> expire(operationId),
				terminalDelayNanos,
				TimeUnit.NANOSECONDS);
		return new Admission(AdmissionKind.NEW, operationId, keyDigest, snapshot(entry));
	}

	private void expire(String operationId) {
		String requestId;
		boolean expired;
		synchronized (this) {
			Entry entry = byOperationId.get(operationId);
			if (entry == null || entry.outcome != null) {
				return;
			}
			requestId = entry.keyDigest;
			expired = complete(operationId, ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.TIMED_OUT));
		}
		if (expired) {
			auditLog.record(SandboxControlAuditLog.Action.MINE_EXACTLY_ONE,
					ExactOneMiningOutcome.Code.TIMED_OUT.name(), requestId, operationId);
		}
	}

	synchronized boolean complete(String operationId, ExactOneMiningOutcome outcome) {
		Entry entry = byOperationId.get(operationId);
		if (entry == null || entry.outcome != null) {
			return false;
		}
		entry.outcome = map(outcome);
		entry.completedAt = Instant.now();
		entry.expiresAtNanos = deadlineFromNow();
		if (entry.deadlineTask != null) {
			entry.deadlineTask.cancel(false);
			entry.deadlineTask = null;
		}
		mutationActive = false;
		return true;
	}

	synchronized Operation find(String operationId) {
		cleanupExpired();
		Entry entry = byOperationId.get(operationId);
		return entry == null ? null : snapshot(entry);
	}

	synchronized MutationLease tryAcquireMutation() {
		cleanupExpired();
		if (mutationActive) {
			return null;
		}
		mutationActive = true;
		return new MutationLease(this);
	}

	String incarnationId() {
		return incarnationId;
	}

	@PreDestroy
	synchronized void close() {
		for (Entry entry : byKey.values()) {
			if (entry.outcome == null) {
				entry.outcome = map(ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.CANCELLED));
				entry.completedAt = Instant.now();
				entry.expiresAtNanos = deadlineFromNow();
			}
			if (entry.deadlineTask != null) {
				entry.deadlineTask.cancel(false);
				entry.deadlineTask = null;
			}
		}
		mutationActive = false;
		deadlineExecutor.shutdown();
	}

	private synchronized void releaseMutation() {
		mutationActive = false;
	}

	private void cleanupExpired() {
		long now = System.nanoTime();
		byKey.values().removeIf(entry -> {
			boolean expired = entry.outcome != null && now - entry.expiresAtNanos >= 0;
			if (expired) {
				byOperationId.remove(entry.operationId);
			}
			return expired;
		});
	}

	private void ensureCapacity() {
		while (byKey.size() >= MAX_ENTRIES) {
			Entry oldest = byKey.values().stream()
					.filter(entry -> entry.outcome != null)
					.min(Comparator.comparing(entry -> entry.completedAt))
					.orElse(null);
			if (oldest == null) {
				return;
			}
			byKey.remove(oldest.keyDigest);
			byOperationId.remove(oldest.operationId);
		}
	}

	private Operation snapshot(Entry entry) {
		return new Operation(
				entry.operationId,
				entry.outcome == null ? "PENDING" : "COMPLETED",
				entry.createdAt,
				entry.completedAt,
				entry.outcome);
	}

	static Outcome map(ExactOneMiningOutcome outcome) {
		return new Outcome(
				mapOutcomeCode(outcome.code()),
				outcome.parentHash() == null ? null : outcome.parentHash().toString(),
				outcome.blockHeight(),
				outcome.blockHash() == null ? null : outcome.blockHash().toString(),
				mapIngestionCode(outcome.ingestionCode()));
	}

	private static String mapOutcomeCode(ExactOneMiningOutcome.Code code) {
		return switch (code) {
			case ACCEPTED -> "ACCEPTED";
			case NOT_ELIGIBLE -> "NOT_ELIGIBLE";
			case STALE_PARENT -> "STALE_PARENT";
			case TIMED_OUT -> "TIMED_OUT";
			case CANCELLED -> "CANCELLED";
			case REJECTED_NOT_PAUSED -> "REJECTED_NOT_PAUSED";
			case REJECTED_SYNCING -> "REJECTED_SYNCING";
			case REJECTED_BUSY -> "REJECTED_BUSY";
			case REJECTED_SHUTDOWN -> "REJECTED_SHUTDOWN";
			case REJECTED_BY_INGESTION -> "REJECTED_BY_INGESTION";
			case RETRYABLE -> "RETRYABLE";
			case FAILED -> "FAILED";
		};
	}

	private static String mapIngestionCode(BlockIngestionOutcome.Code code) {
		if (code == null) {
			return null;
		}
		return switch (code) {
			case ACCEPTED -> "ACCEPTED";
			case REJECTED_STATELESS -> "REJECTED_STATELESS";
			case REJECTED_CONTEXTUAL -> "REJECTED_CONTEXTUAL";
			case REJECTED_CONSENSUS_POLICY -> "REJECTED_CONSENSUS_POLICY";
			case REJECTED_EXECUTION -> "REJECTED_EXECUTION";
			case REJECTED_STATE_ROOT -> "REJECTED_STATE_ROOT";
			case ORPHAN_BUFFERED -> "ORPHAN_BUFFERED";
			case GAP_DETECTED -> "GAP_DETECTED";
			case ALREADY_EXISTS -> "ALREADY_EXISTS";
			case INTERNAL_FAILURE -> "INTERNAL_FAILURE";
		};
	}

	private long deadlineFromNow() {
		long now = System.nanoTime();
		long ttl = ENTRY_TTL.toNanos();
		return now > Long.MAX_VALUE - ttl ? Long.MAX_VALUE : now + ttl;
	}

	private long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private void validateIdempotencyKey(String key) {
		if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
			throw new SandboxControlException(
					HttpStatus.BAD_REQUEST,
					"INVALID_IDEMPOTENCY_KEY",
					"Idempotency-Key must contain 1 to 128 safe ASCII characters");
		}
	}

	private static String digest(String value) {
		try {
			byte[] bytes = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static String randomIdentifier() {
		byte[] bytes = new byte[16];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	enum AdmissionKind {
		NEW,
		REPLAY,
		CONFLICT,
		BUSY,
		CAPACITY
	}

	record Admission(AdmissionKind kind, String operationId, String requestCorrelationId, Operation operation) {
	}

	static final class MutationLease implements AutoCloseable {

		private final SandboxControlOperationRegistry registry;
		private final AtomicBoolean closed = new AtomicBoolean();

		private MutationLease(SandboxControlOperationRegistry registry) {
			this.registry = registry;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				registry.releaseMutation();
			}
		}
	}

	private static final class Entry {

		private final String keyDigest;
		private final String bodyDigest;
		private final String operationId;
		private final Instant createdAt;
		private long expiresAtNanos = Long.MAX_VALUE;
		private Instant completedAt;
		private Outcome outcome;
		private ScheduledFuture<?> deadlineTask;

		private Entry(
				String keyDigest,
				String bodyDigest,
				String operationId,
				Instant createdAt) {
			this.keyDigest = keyDigest;
			this.bodyDigest = bodyDigest;
			this.operationId = operationId;
			this.createdAt = createdAt;
		}
	}
}
