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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

final class SandboxControlDtos {

	private SandboxControlDtos() {
	}

	record Capabilities(
			String apiVersion,
			String executionDomain,
			String incarnationId,
			String chainId,
			String genesisHash,
			String manifestFingerprint,
			String chainIdentitySource,
			boolean disposable,
			boolean controlApi,
			String proofOfWork,
			String clock,
			String coreReadiness,
			List<String> actions,
			int maxRequestBodyBytes,
			int maxConcurrentRequests,
			long rateBurst,
			long rateRefillPerSecond,
			int maxOperationEntries,
			long operationTtlSeconds,
			long maxExactOneDeadlineMs,
			int maxAuditPageSize) {
	}

	record AutonomousState(
			boolean configured,
			boolean desired,
			boolean scheduled,
			boolean active,
			boolean shutdown,
			boolean controlPaused,
			boolean syncing,
			Set<String> suspensions) {
	}

	record P2pMaintenance(boolean queued, int connectedPeers) {
	}

	record MempoolClear(long clearedTransactions) {
	}

	@JsonIgnoreProperties(ignoreUnknown = false)
	record AutonomousRequest(Boolean enabled) {
	}

	@JsonIgnoreProperties(ignoreUnknown = false)
	record CandidateBatchRequest(
			Integer count,
			Long deadlineMs,
			Boolean bypassPolicyPrecheck,
			Boolean includeExecutionInvalidTransactions,
			List<String> retainedCanonicalTransactionsBase64) {
	}

	record CandidateBatch(List<Candidate> candidates) {
	}

	@JsonIgnoreProperties(ignoreUnknown = false)
	record ExactOneRequest(Instant scheduledTimestamp, Long deadlineMs) {
	}

	@JsonIgnoreProperties(ignoreUnknown = false)
	record ExactBatchRequest(Integer count, Long deadlineMs) {
	}

	@JsonIgnoreProperties(ignoreUnknown = false)
	record CandidateRequest(
			Long deadlineMs,
			Boolean bypassPolicyPrecheck,
			Boolean includeExecutionInvalidTransactions,
			List<String> retainedCanonicalTransactionsBase64) {
	}

	record Candidate(
			String code,
			String parentHash,
			Long blockHeight,
			String blockHash,
			String canonicalBlockBase64) {
	}

	record Operation(
			String operationId,
			String status,
			Instant createdAt,
			Instant completedAt,
			Outcome outcome) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Outcome(
			String code,
			String parentHash,
			Long blockHeight,
			String blockHash,
			String ingestionCode) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Error(String code, String message, String operationId) {
		Error(String code, String message) {
			this(code, message, null);
		}
	}

	record AuditPage(
			List<SandboxControlAuditLog.Event> events,
			boolean hasMore,
			Long nextAfter) {
	}

	record BlockIngestionAudit(
			String blockHash,
			String status,
			Long height,
			boolean canonical,
			String outcomeCode) {
	}

	record SyncRuntimeAudit(
			boolean synced,
			boolean activeCycle,
			long localHeight,
			int pendingHeaderRequests,
			int pendingBodyRequests,
			int pendingBroadcastDownloads,
			long headerRequestsIssued,
			long bodyRequestsIssued,
			long firstHeaderRequestSequence,
			long firstBodyRequestSequence,
			int headerBatchLimit,
			int bodyBatchLimit,
			int pipelineDepthLimit,
			int persistenceBatchLimit,
			long bodyInflightByteLimit,
			long bodyInflightReservedBytes,
			long bodyInflightPeakReservedBytes,
			int activeBodyRequests,
			int peakActiveBodyRequests,
			int activeBodyPeers,
			int peakActiveBodyPeers,
			long persistenceBatchByteLimit,
			long persistenceBatchCurrentBytes,
			long persistenceBatchPeakBytes,
			int maxHeaderPageRequested,
			long legacyHeaderPageRequests,
			long v2HeaderPageRequests,
			int headerPrefetchDepthLimit,
			int bufferedHeaderWindows,
			int bufferedHeaderCount,
			long bufferedHeaderBytes,
			int peakBufferedHeaderWindows,
			int peakBufferedHeaderCount,
			long peakBufferedHeaderBytes,
			int validatedAheadHeaders,
			long discardedPrefetchHeaders) {
	}

	record PowRuntimeAudit(
			String provider,
			String memoryMode,
			boolean datasetAllocated,
			int activeVmLeases,
			boolean initializationInProgress,
			long proofOfWorkInvocationCount) {
	}

	record EquivocationRuntimeAudit(
			int queueCapacity,
			long processingDelayMs,
			long delayAfterObservations,
			long submittedObservations,
			long startedObservations,
			int pendingObservations,
			long droppedAuditObservations) {
	}
}
