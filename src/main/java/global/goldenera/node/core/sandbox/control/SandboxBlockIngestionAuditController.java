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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;
import global.goldenera.node.core.sandbox.control.SandboxControlAuditLog.Action;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.BlockIngestionAudit;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.PowRuntimeAudit;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.EquivocationRuntimeAudit;
import global.goldenera.node.core.sandbox.control.SandboxControlDtos.SyncRuntimeAudit;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.BlockIngestionService;
import global.goldenera.node.core.sync.BlockSyncManagerService;
import global.goldenera.node.core.sync.BlockSyncManagerService.SyncRuntimeSnapshot;
import global.goldenera.node.core.mining.MiningService;
import global.goldenera.node.core.monitoring.EquivocationDetectionService;

/** Bounded read-only evidence seam for blocks delivered through normal P2P. */
@RestController
@Profile("sandbox")
@ConditionalOnProperty(
		prefix = "ge.sandbox.control-api",
		name = "enabled",
		havingValue = "true")
@RequestMapping("/api/sandbox/v1/control")
final class SandboxBlockIngestionAuditController {

	private final ChainQuery chainQuery;
	private final BlockIngestionService ingestionService;
	private final SandboxControlAuditLog auditLog;
	private final BlockSyncManagerService syncManager;
	private final ProofOfWorkProvider proofOfWorkProvider;
	private final MiningService miningService;
	private final EquivocationDetectionService equivocationDetectionService;

	SandboxBlockIngestionAuditController(
			ChainQuery chainQuery,
			BlockIngestionService ingestionService,
			SandboxControlAuditLog auditLog,
			BlockSyncManagerService syncManager,
			ProofOfWorkProvider proofOfWorkProvider,
			MiningService miningService,
			EquivocationDetectionService equivocationDetectionService) {
		this.chainQuery = chainQuery;
		this.ingestionService = ingestionService;
		this.auditLog = auditLog;
		this.syncManager = syncManager;
		this.proofOfWorkProvider = proofOfWorkProvider;
		this.miningService = miningService;
		this.equivocationDetectionService = equivocationDetectionService;
	}

	@GetMapping("/runtime/equivocation")
	EquivocationRuntimeAudit equivocationRuntime() {
		var snapshot = equivocationDetectionService.runtimeSnapshot();
		EquivocationRuntimeAudit result = new EquivocationRuntimeAudit(
				snapshot.queueCapacity(),
				snapshot.processingDelayMs(),
				snapshot.delayAfterObservations(),
				snapshot.submittedObservations(),
				snapshot.startedObservations(),
				snapshot.pendingObservations(),
				snapshot.droppedObservations());
		auditLog.record(Action.READ_EQUIVOCATION_RUNTIME, "OK", null);
		return result;
	}

	@GetMapping("/blocks/{blockHash}")
	BlockIngestionAudit audit(@PathVariable String blockHash) {
		Hash hash;
		try {
			hash = Hash.fromHexString(blockHash);
		} catch (RuntimeException e) {
			throw new SandboxControlException(
					HttpStatus.BAD_REQUEST, "INVALID_BLOCK_HASH", "blockHash must be a canonical 32-byte hash");
		}
		StoredBlock stored = chainQuery.getStoredBlockHeaderByHash(hash).orElse(null);
		if (stored != null) {
			boolean canonical = chainQuery.getBlockHashByHeight(stored.getHeight())
					.map(hash::equals)
					.orElse(false);
			String status = canonical ? "CANONICAL" : "STORED_SIDE_BRANCH";
			auditLog.record(Action.READ_BLOCK_INGESTION, status, hash.toString());
			return new BlockIngestionAudit(hash.toString(), status, stored.getHeight(), canonical,
					ingestionService.recentOutcome(hash).map(Enum::name).orElse(null));
		}
		if (ingestionService.isOrphan(hash)) {
			auditLog.record(Action.READ_BLOCK_INGESTION, "ORPHAN_BUFFERED", hash.toString());
			return new BlockIngestionAudit(hash.toString(), "ORPHAN_BUFFERED", null, false,
					ingestionService.recentOutcome(hash).map(Enum::name).orElse("ORPHAN_BUFFERED"));
		}
		String outcomeCode = ingestionService.recentOutcome(hash).map(Enum::name).orElse(null);
		auditLog.record(Action.READ_BLOCK_INGESTION, "NOT_OBSERVED", hash.toString());
		return new BlockIngestionAudit(hash.toString(), "NOT_OBSERVED", null, false, outcomeCode);
	}

	@GetMapping("/sync")
	SyncRuntimeAudit syncRuntime() {
		SyncRuntimeSnapshot snapshot = syncManager.runtimeSnapshot();
		auditLog.record(Action.READ_SYNC_RUNTIME, "OK", null);
		return new SyncRuntimeAudit(
				snapshot.synced(),
				snapshot.activeCycle(),
				snapshot.localHeight(),
				snapshot.pendingHeaderRequests(),
				snapshot.pendingBodyRequests(),
				snapshot.pendingBroadcastDownloads(),
				snapshot.headerRequestsIssued(),
				snapshot.bodyRequestsIssued(),
				snapshot.firstHeaderRequestSequence(),
				snapshot.firstBodyRequestSequence(),
				snapshot.headerBatchLimit(),
				snapshot.bodyBatchLimit(),
				snapshot.pipelineDepthLimit(),
				snapshot.persistenceBatchLimit(),
				snapshot.bodyInflightByteLimit(),
				snapshot.bodyInflightReservedBytes(),
				snapshot.bodyInflightPeakReservedBytes(),
				snapshot.activeBodyRequests(),
				snapshot.peakActiveBodyRequests(),
				snapshot.activeBodyPeers(),
				snapshot.peakActiveBodyPeers(),
				snapshot.persistenceBatchByteLimit(),
				snapshot.persistenceBatchCurrentBytes(),
				snapshot.persistenceBatchPeakBytes());
	}

	@GetMapping("/runtime/pow")
	PowRuntimeAudit powRuntime() {
		PowRuntimeAudit result;
		if (proofOfWorkProvider instanceof RandomXProofOfWorkProvider randomX) {
			result = new PowRuntimeAudit(
					"RANDOMX",
					randomX.getMiningMemoryMode().name(),
					randomX.isDatasetAllocated(),
					randomX.getActiveVmLeaseCount(),
					randomX.isInitializationInProgress(),
					miningService.getProofOfWorkInvocationCount());
		} else {
			result = new PowRuntimeAudit(
					"DETERMINISTIC_SHA256_V1", "NOT_APPLICABLE", false, 0,
					proofOfWorkProvider.isInitializationInProgress(),
					miningService.getProofOfWorkInvocationCount());
		}
		auditLog.record(Action.READ_POW_RUNTIME, "OK", null);
		return result;
	}
}
