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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

import global.goldenera.node.core.blockchain.pow.DeterministicSha256ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.time.DeterministicSandboxChainClock;
import global.goldenera.node.core.blockchain.time.ProductionChainClock;
import global.goldenera.node.core.mining.AutonomousMiningState;
import global.goldenera.node.core.mining.ExactOneMiningOutcome;
import global.goldenera.node.core.mining.ExactOneMiningRequest;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.mining.MiningSuspensionReason;
import global.goldenera.node.core.mining.SandboxCandidateAuthoringOutcome;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadiness;
import global.goldenera.node.core.sandbox.control.SandboxControlAuditLog.Action;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.AuditPage;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.AutonomousRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.AutonomousState;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Capabilities;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Candidate;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.CandidateBatch;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.CandidateBatchRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.CandidateRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.ExactOneRequest;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.Operation;
import global.goldenera.node.core.sandbox.control.SandboxControlOperationRegistry.Admission;
import global.goldenera.node.core.sandbox.control.SandboxControlOperationRegistry.AdmissionKind;
import global.goldenera.node.core.sandbox.control.SandboxControlOperationRegistry.MutationLease;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.serialization.block.BlockEncoder;
import global.goldenera.cryptoj.serialization.tx.TxDecoder;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import org.apache.tuweni.bytes.Bytes;

final class SandboxControlService {
	private static final int MAX_CANDIDATE_CANONICAL_BYTES = 16 * 1024 * 1024;
	private static final long MAX_CANDIDATE_BATCH_CANONICAL_BYTES = 64L * 1024 * 1024;

	static final Duration AUTONOMOUS_PAUSE_TIMEOUT = Duration.ofSeconds(5);
	private static final int MAX_RETAINED_CANDIDATE_TRANSACTIONS = 32;
	private static final int MAX_RETAINED_TRANSACTION_BYTES = 100_000;
	private static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{22}");

	private final MiningService miningService;
	private final SandboxControlActivation activation;
	private final SandboxControlOperationRegistry operations;
	private final SandboxControlAuditLog auditLog;
	private final ProofOfWorkProvider proofOfWorkProvider;
	private final ChainClock chainClock;
	private final MiningProperties miningProperties;
	private final AuthoritativeChainIdentityProvider identityProvider;
	private final CoreRuntimeReadiness coreReadiness;

	SandboxControlService(
			MiningService miningService,
			SandboxControlActivation activation,
			SandboxControlOperationRegistry operations,
			SandboxControlAuditLog auditLog,
			ProofOfWorkProvider proofOfWorkProvider,
			ChainClock chainClock,
			MiningProperties miningProperties,
			AuthoritativeChainIdentityProvider identityProvider,
			CoreRuntimeReadiness coreReadiness) {
		this.miningService = miningService;
		this.activation = activation;
		this.operations = operations;
		this.auditLog = auditLog;
		this.proofOfWorkProvider = proofOfWorkProvider;
		this.chainClock = chainClock;
		this.miningProperties = miningProperties;
		this.identityProvider = identityProvider;
		this.coreReadiness = coreReadiness;
	}

	Capabilities capabilities() {
		SandboxManifest manifest = activation.manifestContext().manifest();
		StoredChainIdentity identity = identityProvider.identity();
		auditLog.record(Action.READ_CAPABILITIES, "OK", null);
		return new Capabilities(
				"v1",
				"SANDBOX",
				operations.incarnationId(),
				identity.chainId(),
				identity.genesisHash(),
				identity.manifestFingerprint(),
				"AUTHORITATIVE_ROCKSDB",
				manifest.disposable(),
				manifest.features().controlApi(),
				runtimeProofOfWork(),
				runtimeClock(),
				coreReadiness.isReady() ? "READY" : "NOT_READY",
				List.of(
						"READ_CAPABILITIES",
						"READ_AUTONOMOUS_STATE",
						"SET_AUTONOMOUS_STATE",
						"MINE_EXACTLY_ONE",
						"AUTHOR_CANDIDATE_WITHOUT_INGESTION",
						"AUTHOR_CANDIDATES_WITHOUT_INGESTION",
						"READ_REQUEST_RESULT",
						"READ_BLOCK_INGESTION",
						"READ_SYNC_RUNTIME",
						"READ_POW_RUNTIME",
						"READ_EQUIVOCATION_RUNTIME",
						"READ_AUDIT"),
				SandboxControlSecurityFilter.MAX_REQUEST_BODY_BYTES,
				SandboxControlSecurityFilter.MAX_CONCURRENT_REQUESTS,
				SandboxControlConfiguration.RATE_CAPACITY,
				SandboxControlConfiguration.RATE_REFILL_PER_SECOND,
				SandboxControlOperationRegistry.MAX_ENTRIES,
				SandboxControlOperationRegistry.ENTRY_TTL.toSeconds(),
				ExactOneMiningRequest.MAX_DEADLINE.toMillis(),
				SandboxControlAuditLog.MAX_PAGE_SIZE);
	}

	AutonomousState state() {
		AutonomousState state = map(miningService.getAutonomousMiningState());
		auditLog.record(Action.READ_STATE, "OK", null);
		return state;
	}

	AutonomousState setAutonomous(AutonomousRequest request) {
		if (request == null || request.enabled() == null) {
			throw badRequest("INVALID_AUTONOMOUS_REQUEST", "enabled is required");
		}
		requireCoreReady();
		MutationLease lease = operations.tryAcquireMutation();
		if (lease == null) {
			throw busy();
		}
		try (lease) {
			if (request.enabled()) {
				AutonomousMiningState current = miningService.getAutonomousMiningState();
				if (!current.configured()) {
					throw new SandboxControlException(
							HttpStatus.CONFLICT,
							"AUTONOMOUS_NOT_CONFIGURED",
							"Autonomous mining is disabled by node configuration");
				}
				miningService.resumeAutonomousMining();
				miningService.startMining();
			} else if (!miningService.pauseAutonomousMining(AUTONOMOUS_PAUSE_TIMEOUT)) {
				throw new SandboxControlException(
						HttpStatus.SERVICE_UNAVAILABLE,
						"QUIESCENCE_TIMEOUT",
						"Autonomous mining did not become quiescent before the control deadline");
			}
			AutonomousState result = map(miningService.getAutonomousMiningState());
			auditLog.record(Action.SET_AUTONOMOUS, request.enabled() ? "ENABLED" : "PAUSED", null);
			return result;
		}
	}

	Submission submitExactOne(String idempotencyKey, ExactOneRequest request) {
		validateExactOneRequest(request);
		requireCoreReady();
		String canonicalBody = (request.scheduledTimestamp() == null ? "-" : request.scheduledTimestamp().toString())
				+ "/" + request.deadlineMs();
		Duration deadline = Duration.ofMillis(request.deadlineMs());
		Admission admission = operations.admit(idempotencyKey, canonicalBody, deadline);
		if (admission.kind() == AdmissionKind.CONFLICT) {
			throw new SandboxControlException(
					HttpStatus.CONFLICT,
					"IDEMPOTENCY_CONFLICT",
					"Idempotency-Key was already used with a different request body",
					admission.operationId());
		}
		if (admission.kind() == AdmissionKind.BUSY) {
			throw busy();
		}
		if (admission.kind() == AdmissionKind.CAPACITY) {
			throw new SandboxControlException(
					HttpStatus.SERVICE_UNAVAILABLE,
					"REQUEST_REGISTRY_FULL",
					"The bounded request registry has no available capacity");
		}
		if (admission.kind() == AdmissionKind.REPLAY) {
			auditLog.record(Action.MINE_EXACTLY_ONE, "IDEMPOTENT_REPLAY",
					admission.requestCorrelationId(), admission.operationId());
			return new Submission(admission.operation(), true);
		}
		auditLog.record(Action.MINE_EXACTLY_ONE, "ADMITTED",
				admission.requestCorrelationId(), admission.operationId());

		CompletableFuture<ExactOneMiningOutcome> result;
		try {
			result = miningService.mineExactlyOne(new ExactOneMiningRequest(
					Optional.ofNullable(request.scheduledTimestamp()),
					deadline));
		} catch (RuntimeException e) {
			ExactOneMiningOutcome failure = ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.FAILED);
			if (operations.complete(admission.operationId(), failure)) {
				auditLog.record(Action.MINE_EXACTLY_ONE, failure.code().name(),
						admission.requestCorrelationId(), admission.operationId());
			}
			throw new SandboxControlException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"MINING_ADMISSION_FAILED",
					"Exact-one mining could not be admitted",
					admission.operationId());
		}
		result.whenComplete((outcome, failure) -> {
			ExactOneMiningOutcome terminal = failure == null && outcome != null
					? outcome
					: ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.FAILED);
			if (operations.complete(admission.operationId(), terminal)) {
				auditLog.record(Action.MINE_EXACTLY_ONE, terminal.code().name(),
						admission.requestCorrelationId(), admission.operationId());
			}
		});
		return new Submission(admission.operation(), false);
	}

	Candidate authorCandidate(CandidateRequest request) {
		validateCandidateRequest(request);
		MutationLease lease = operations.tryAcquireMutation();
		if (lease == null) {
			throw busy();
		}
		try (lease) {
			return authorCandidateUnderLease(request, 0, 1);
		}
	}

	private void validateCandidateRequest(CandidateRequest request) {
		if (request == null || request.deadlineMs() == null
				|| !Boolean.TRUE.equals(request.bypassPolicyPrecheck())) {
			throw badRequest("INVALID_CANDIDATE_REQUEST",
					"deadlineMs and explicit bypassPolicyPrecheck=true are required");
		}
		long maxDeadlineMs = ExactOneMiningRequest.MAX_DEADLINE.toMillis();
		if (request.deadlineMs() <= 0 || request.deadlineMs() > maxDeadlineMs) {
			throw badRequest("INVALID_CANDIDATE_DEADLINE",
					"deadlineMs must be between 1 and " + maxDeadlineMs);
		}
		if (request.retainedCanonicalTransactionsBase64() != null
				&& !request.retainedCanonicalTransactionsBase64().isEmpty()
				&& !Boolean.TRUE.equals(request.includeExecutionInvalidTransactions())) {
			throw badRequest("INVALID_RETAINED_TRANSACTIONS",
					"retained transactions require includeExecutionInvalidTransactions=true");
		}
		requireCoreReady();
		AutonomousMiningState miningState = miningService.getAutonomousMiningState();
		if (!miningState.suspensions().contains(MiningSuspensionReason.SANDBOX_CONTROL)
				|| !miningState.quiescent()) {
			throw new SandboxControlException(HttpStatus.CONFLICT, "MINING_NOT_QUIESCENT",
					"Candidate authoring requires sandbox-controlled autonomous mining to be paused");
		}
	}

	private Candidate authorCandidateUnderLease(
			CandidateRequest request, int nonceSearchOffset, int nonceSearchStride) {
		List<Tx> retainedTransactions = decodeRetainedTransactions(
				request.retainedCanonicalTransactionsBase64());
		SandboxCandidateAuthoringOutcome outcome = miningService.authorSandboxCandidate(
				Duration.ofMillis(request.deadlineMs()),
				Boolean.TRUE.equals(request.includeExecutionInvalidTransactions()),
				retainedTransactions,
				nonceSearchOffset,
				nonceSearchStride);
		auditLog.record(Action.AUTHOR_CANDIDATE, outcome.code().name(), null);
		String parentHash = outcome.parentHash() == null ? null : outcome.parentHash().toHexString();
		if (outcome.block() == null) {
			return new Candidate(outcome.code().name(), parentHash, outcome.blockHeight(), null, null);
		}
		byte[] canonical = BlockEncoder.INSTANCE.encode(outcome.block(), true).toArray();
		if (canonical.length > MAX_CANDIDATE_CANONICAL_BYTES) {
			throw new SandboxControlException(HttpStatus.PAYLOAD_TOO_LARGE, "CANDIDATE_TOO_LARGE",
					"Canonical candidate exceeds the control API response bound");
		}
		return new Candidate(
				outcome.code().name(),
				parentHash,
				outcome.blockHeight(),
				outcome.block().getHash().toHexString(),
				Base64.getEncoder().encodeToString(canonical));
	}

	CandidateBatch authorCandidates(CandidateBatchRequest request) {
		if (request == null || request.count() == null || request.count() < 1 || request.count() > 64) {
			throw badRequest("INVALID_CANDIDATE_BATCH", "candidate batch count must be between 1 and 64");
		}
		long maxDeadlineMs = ExactOneMiningRequest.MAX_DEADLINE.toMillis();
		if (request.deadlineMs() == null || request.deadlineMs() < 1 || request.deadlineMs() > maxDeadlineMs) {
			throw badRequest("INVALID_CANDIDATE_DEADLINE",
					"deadlineMs must be between 1 and " + maxDeadlineMs);
		}
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.deadlineMs());
		List<Candidate> candidates = new ArrayList<>(request.count());
		long canonicalBytes = 0;
		MutationLease lease = operations.tryAcquireMutation();
		if (lease == null) {
			throw busy();
		}
		try (lease) {
			for (int index = 0; index < request.count(); index++) {
				long remainingNanos = deadlineNanos - System.nanoTime();
				if (remainingNanos <= 0) {
					throw new SandboxControlException(HttpStatus.REQUEST_TIMEOUT, "CANDIDATE_BATCH_TIMED_OUT",
							"Candidate batch exceeded its shared deadline");
				}
				long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
				CandidateRequest candidate = new CandidateRequest(
						remainingMillis, request.bypassPolicyPrecheck(),
						request.includeExecutionInvalidTransactions(), request.retainedCanonicalTransactionsBase64());
				validateCandidateRequest(candidate);
				Candidate authored = authorCandidateUnderLease(candidate, index, request.count());
				if (authored.canonicalBlockBase64() != null) {
					canonicalBytes += decodedBase64Length(authored.canonicalBlockBase64());
					if (canonicalBytes > MAX_CANDIDATE_BATCH_CANONICAL_BYTES) {
						throw new SandboxControlException(HttpStatus.PAYLOAD_TOO_LARGE,
								"CANDIDATE_BATCH_TOO_LARGE",
								"Canonical candidate batch exceeds the aggregate response bound");
					}
				}
				candidates.add(authored);
			}
		}
		return new CandidateBatch(List.copyOf(candidates));
	}

	private static long decodedBase64Length(String encoded) {
		int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
		return (encoded.length() / 4L) * 3L - padding;
	}

	private List<Tx> decodeRetainedTransactions(List<String> encodedTransactions) {
		if (encodedTransactions == null || encodedTransactions.isEmpty()) {
			return List.of();
		}
		if (encodedTransactions.size() > MAX_RETAINED_CANDIDATE_TRANSACTIONS) {
			throw badRequest("INVALID_RETAINED_TRANSACTIONS", "too many retained candidate transactions");
		}
		List<Tx> decoded = new ArrayList<>(encodedTransactions.size());
		for (String encoded : encodedTransactions) {
			try {
				byte[] canonical = Base64.getDecoder().decode(encoded);
				if (canonical.length == 0 || canonical.length > MAX_RETAINED_TRANSACTION_BYTES) {
					throw new IllegalArgumentException("transaction size is out of range");
				}
				var transaction = TxDecoder.INSTANCE.decode(Bytes.wrap(canonical));
				byte[] roundTrip = TxEncoder.INSTANCE.encode(transaction, true).toArray();
				if (!Arrays.equals(canonical, roundTrip)) {
					throw new IllegalArgumentException("non-canonical transaction");
				}
				decoded.add(transaction);
			} catch (RuntimeException exception) {
				throw badRequest("INVALID_RETAINED_TRANSACTIONS", "retained transaction is not canonical");
			}
		}
		return List.copyOf(decoded);
	}

	Operation operation(String operationId) {
		if (operationId == null || !OPERATION_ID.matcher(operationId).matches()) {
			throw badRequest("INVALID_OPERATION_ID", "The operation ID has an invalid format");
		}
		Operation result = operations.find(operationId);
		if (result == null) {
			throw new SandboxControlException(
					HttpStatus.NOT_FOUND,
					"REQUEST_NOT_FOUND",
					"The request result is unavailable");
		}
		auditLog.record(Action.READ_OPERATION, "OK", operationId);
		return result;
	}

	AuditPage audit(Long after, int limit) {
		if (limit < 1 || limit > SandboxControlAuditLog.MAX_PAGE_SIZE) {
			throw badRequest("INVALID_AUDIT_LIMIT", "limit must be between 1 and 100");
		}
		if (after != null && after < 0) {
			throw badRequest("INVALID_AUDIT_CURSOR", "after must be zero or a positive sequence");
		}
		auditLog.record(Action.READ_AUDIT, "OK", null);
		SandboxControlAuditLog.Page page = auditLog.page(after == null ? 0 : after, limit);
		return new AuditPage(page.events(), page.hasMore(), page.nextAfter());
	}

	private String runtimeProofOfWork() {
		if (proofOfWorkProvider instanceof DeterministicSha256ProofOfWorkProvider) {
			return "DETERMINISTIC_SHA256_V1";
		}
		if (proofOfWorkProvider instanceof RandomXProofOfWorkProvider) {
			return "RANDOMX_" + miningProperties.getMemoryMode().name();
		}
		throw new IllegalStateException("Unsupported active proof-of-work provider");
	}

	private String runtimeClock() {
		if (chainClock instanceof DeterministicSandboxChainClock) {
			return "DETERMINISTIC";
		}
		if (chainClock instanceof ProductionChainClock) {
			return "PRODUCTION";
		}
		throw new IllegalStateException("Unsupported active chain clock");
	}

	private void requireCoreReady() {
		if (!coreReadiness.isReady()) {
			throw new SandboxControlException(
					HttpStatus.SERVICE_UNAVAILABLE,
					"CORE_NOT_READY",
					"Core runtime readiness milestones have not completed");
		}
	}

	private void validateExactOneRequest(ExactOneRequest request) {
		if (request == null || request.deadlineMs() == null) {
			throw badRequest("INVALID_EXACT_ONE_REQUEST", "deadlineMs is required");
		}
		long maxDeadlineMs = ExactOneMiningRequest.MAX_DEADLINE.toMillis();
		if (request.deadlineMs() <= 0 || request.deadlineMs() > maxDeadlineMs) {
			throw badRequest(
					"INVALID_EXACT_ONE_DEADLINE",
					"deadlineMs must be between 1 and " + maxDeadlineMs);
		}
		if (request.scheduledTimestamp() != null
				&& !activation.manifestContext().manifest().features().deterministicClock()) {
			throw badRequest(
					"SCHEDULED_TIMESTAMP_UNSUPPORTED",
					"scheduledTimestamp requires the deterministic sandbox clock");
		}
	}

	private AutonomousState map(AutonomousMiningState state) {
		Set<String> suspensions = state.suspensions().stream()
				.map(Enum::name)
				.collect(Collectors.toUnmodifiableSet());
		return new AutonomousState(
				state.configured(),
				state.desired(),
				state.scheduled(),
				state.active(),
				state.shutdown(),
				state.suspensions().contains(MiningSuspensionReason.SANDBOX_CONTROL),
				state.suspensions().contains(MiningSuspensionReason.SYNC),
				suspensions);
	}

	private SandboxControlException busy() {
		return new SandboxControlException(
				HttpStatus.CONFLICT,
				"MUTATION_BUSY",
				"Another sandbox control mutation is still active");
	}

	private SandboxControlException badRequest(String code, String message) {
		return new SandboxControlException(HttpStatus.BAD_REQUEST, code, message);
	}

	record Submission(Operation operation, boolean replayed) {
	}
}
