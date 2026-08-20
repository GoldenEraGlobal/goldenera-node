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

import static global.goldenera.node.core.config.CoreAsyncConfig.BLOCK_MINING_EXECUTOR;
import static global.goldenera.node.core.config.CoreAsyncConfig.MINER_THREAD_FACTORY;
import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkHasher;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkMiningException;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkTarget;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.time.BlockTimestampReservation;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.core.exceptions.GETxValidationFailedException;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.BlockIngestionOutcome;
import global.goldenera.node.core.sync.BlockIngestionService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@FieldDefaults(level = PRIVATE)
@Slf4j
public class MiningService {

	private static final Duration SHUTDOWN_INITIALIZATION_GRACE = Duration.ofSeconds(1);
	private static final Duration SHUTDOWN_TERMINATION_GRACE = Duration.ofSeconds(1);

	final ReentrantLock masterChainLock;
	final AtomicBoolean isMining = new AtomicBoolean(false);
	final ReentrantLock coordinationLock = new ReentrantLock();
	final Condition coordinationChanged = coordinationLock.newCondition();
	final EnumSet<MiningSuspensionReason> suspensions = EnumSet.noneOf(MiningSuspensionReason.class);
	final AtomicBoolean shutdown = new AtomicBoolean(false);
	final AtomicBoolean autonomousDesired = new AtomicBoolean(true);
	final AtomicLong proofOfWorkInvocationCount = new AtomicLong();
	final int hashingThreads;

	final MeterRegistry registry;
	final ExecutorService blockMiningExecutor;
	final ScheduledExecutorService exactOneDeadlineExecutor;

	// Worker pool for parallel hashing (re-used)
	volatile ExecutorService blockHashingWorker;
	final Object hashingWorkerLock = new Object();
	// Reference to the main mining loop thread for interruption
	volatile Thread miningThread;
	volatile ActiveWork activeWork = ActiveWork.NONE;
	volatile MiningAttemptContext activeAttempt;
	ExactOneOperation exactOneOperation;
	volatile boolean proofOfWorkInitializationActive;

	final MiningBlockAssemblerService miningBlockAssemblerService;
	final IdentityService identityService;
	final MempoolManager mempoolService;
	final ChainQuery chainQueryService;
	final MiningProperties miningConfig;
	final ProofOfWorkProvider proofOfWorkProvider;
	final ChainClock chainClock;
	final BlockIngestionService blockIngestionService;
	final ThreadFactory minerThreadFactory;

	public MiningService(
			MeterRegistry registry,
			@Qualifier("masterChainLock") ReentrantLock masterChainLock,
			@Qualifier(BLOCK_MINING_EXECUTOR) ExecutorService blockMiningExecutor,
			MiningBlockAssemblerService miningBlockAssemblerService,
			IdentityService identityService,
			MempoolManager mempoolService,
			ChainQuery chainQueryService,
			MiningProperties miningConfig,
			ProofOfWorkProvider proofOfWorkProvider,
			ChainClock chainClock,
			BlockIngestionService blockIngestionService,
			@Qualifier(MINER_THREAD_FACTORY) ThreadFactory minerThreadFactory) {
		this.registry = registry;
		this.masterChainLock = masterChainLock;
		this.blockMiningExecutor = blockMiningExecutor;
		this.miningBlockAssemblerService = miningBlockAssemblerService;
		this.identityService = identityService;
		this.mempoolService = mempoolService;
		this.chainQueryService = chainQueryService;
		this.miningConfig = miningConfig;
		this.proofOfWorkProvider = proofOfWorkProvider;
		this.chainClock = chainClock;
		this.blockIngestionService = blockIngestionService;
		this.minerThreadFactory = minerThreadFactory;
		this.exactOneDeadlineExecutor =
				Executors.newSingleThreadScheduledExecutor(MiningService::newDeadlineThread);
		this.hashingThreads = getHashingThreads();
		log.info("Mining initialized with {} hashing threads", this.hashingThreads);
	}

	/**
	 * Produces a signed proof-of-work candidate without submitting it locally.
	 * This method is intentionally exposed only to the sandbox control service;
	 * production API wiring never calls it.
	 */
	public SandboxCandidateAuthoringOutcome authorSandboxCandidate(
			Duration deadline, boolean includeExecutionInvalidTransactions, List<Tx> retainedTransactions) {
		return authorSandboxCandidate(deadline, includeExecutionInvalidTransactions, retainedTransactions, 0, 1);
	}

	public SandboxCandidateAuthoringOutcome authorSandboxCandidate(
			Duration deadline,
			boolean includeExecutionInvalidTransactions,
			List<Tx> retainedTransactions,
			int nonceSearchOffset,
			int nonceSearchStride) {
		if (deadline == null || deadline.isZero() || deadline.isNegative()
				|| deadline.compareTo(ExactOneMiningRequest.MAX_DEADLINE) > 0) {
			throw new IllegalArgumentException("Sandbox candidate deadline is out of range");
		}
		if (nonceSearchOffset < 0 || nonceSearchStride < 1 || nonceSearchOffset >= nonceSearchStride
				|| nonceSearchStride > 1_152) {
			throw new IllegalArgumentException("Sandbox nonce search partition is out of range");
		}
		MiningAttemptContext context = MiningAttemptContext.exactOne(deadline);
		Block parentBlock;
		masterChainLock.lock();
		try {
			parentBlock = chainQueryService.getLatestStoredBlockOrThrow().getBlock();
			context.parentHash = parentBlock.getHash();
		} finally {
			masterChainLock.unlock();
		}
		try (BlockTimestampReservation timestamp = chainClock.reserveNextBlockTimestamp(
				parentBlock.getHeader(), Optional.empty())) {
			Optional<MiningBlockAssemblerService.AssembledBlock> assembled =
					miningBlockAssemblerService.createSandboxCandidateTemplate(parentBlock, timestamp);
			if (assembled.isEmpty()) {
				return sandboxCandidateOutcome(SandboxCandidateAuthoringOutcome.Code.NOT_ELIGIBLE, context, null, null);
			}
			MiningBlockAssemblerService.AssembledBlock candidate = assembled.orElseThrow();
			List<Tx> body = includeExecutionInvalidTransactions
					? new ArrayList<>(candidate.getSelectedTxs())
					: new ArrayList<>(candidate.getTxs());
			for (Tx retained : Objects.requireNonNull(retainedTransactions, "retainedTransactions")) {
				if (body.stream().noneMatch(existing -> existing.getHash().equals(retained.getHash()))) {
					body.add(retained);
				}
			}
			MiningBlockAssemblerService.BlockHeaderTemplate template = candidate.getBlockTemplate();
			if ((includeExecutionInvalidTransactions && !candidate.getInvalidTxs().isEmpty())
					|| !retainedTransactions.isEmpty()) {
				template = MiningBlockAssemblerService.BlockHeaderTemplate.builder()
						.version(template.getVersion())
						.height(template.getHeight())
						.timestamp(template.getTimestamp())
						.previousHash(template.getPreviousHash())
						.difficulty(template.getDifficulty())
						.coinbase(template.getCoinbase())
						.txRootHash(TxRootUtil.txRootHash(body))
						.stateRootHash(template.getStateRootHash())
						.build();
			}
			prepareProofOfWorkForMining(template.getHeight(), context);
			Long nonce = findNonce(template,
					DifficultyUtil.calculateTargetFromDifficulty(template.getDifficulty()), context,
					nonceSearchOffset, nonceSearchStride);
			if (nonce == null || context.deadlineExceeded()) {
				return sandboxCandidateOutcome(SandboxCandidateAuthoringOutcome.Code.TIMED_OUT,
						context, template.getHeight(), null);
			}
			BlockHeaderImpl unsignedHeader = BlockHeaderImpl.builder()
					.version(template.getVersion())
					.height(template.getHeight())
					.timestamp(template.getTimestamp())
					.previousHash(template.getPreviousHash())
					.difficulty(template.getDifficulty())
					.coinbase(template.getCoinbase())
					.txRootHash(template.getTxRootHash())
					.stateRootHash(template.getStateRootHash())
					.nonce(nonce)
					.build();
			Signature signature = identityService.getPrivateKey().sign(BlockHeaderUtil.hashForSigning(unsignedHeader));
			Block block = BlockImpl.builder()
					.header(unsignedHeader.toBuilder().signature(signature).build())
					.txs(body)
					.build();
			masterChainLock.lock();
			try {
				if (!chainQueryService.getLatestStoredBlockOrThrow().getHash().equals(parentBlock.getHash())) {
					return sandboxCandidateOutcome(SandboxCandidateAuthoringOutcome.Code.STALE_PARENT,
							context, template.getHeight(), null);
				}
			} finally {
				masterChainLock.unlock();
			}
			return sandboxCandidateOutcome(SandboxCandidateAuthoringOutcome.Code.AUTHORED,
					context, template.getHeight(), block);
		} catch (Exception failure) {
			log.error("Sandbox candidate authoring failed", failure);
			return sandboxCandidateOutcome(SandboxCandidateAuthoringOutcome.Code.FAILED, context, null, null);
		}
	}

	private SandboxCandidateAuthoringOutcome sandboxCandidateOutcome(
			SandboxCandidateAuthoringOutcome.Code code,
			MiningAttemptContext context,
			Long height,
			Block block) {
		return new SandboxCandidateAuthoringOutcome(code, context.parentHash, height, block);
	}

	@PreDestroy
	public void stopMining() {
		coordinationLock.lock();
		try {
			if (!shutdown.compareAndSet(false, true)) {
				return;
			}
			log.info("Stopping mining service...");
			autonomousDesired.set(false);
			isMining.set(false);
			if (exactOneOperation != null && exactOneOperation.state != OperationState.SUBMITTING) {
				ExactOneOperation operation = exactOneOperation;
				operation.context.cancelled.set(true);
				terminalizeOperationLocked(operation,
						ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.REJECTED_SHUTDOWN));
				exactOneOperation = null;
			}
			if (activeAttempt != null && !isExactOneSubmittingLocked()) {
				activeAttempt.cancelled.set(true);
			}
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}

		requestActiveAttemptCancellation(false);
		ExecutorService hashingWorker = shutdownHashingWorker();
		exactOneDeadlineExecutor.shutdownNow();
		boolean nonInterruptibleWorkFinished = awaitNonInterruptibleWork(SHUTDOWN_INITIALIZATION_GRACE);
		if (nonInterruptibleWorkFinished) {
			blockMiningExecutor.shutdownNow();
		} else {
			log.warn("Non-interruptible mining work is still active; mining executor will terminate cooperatively");
			blockMiningExecutor.shutdown();
		}

		try {
			if (!blockMiningExecutor.awaitTermination(
					SHUTDOWN_TERMINATION_GRACE.toNanos(), TimeUnit.NANOSECONDS)) {
				log.warn("Mining executor termination deferred; no native initialization was interrupted");
			}
			if (hashingWorker != null && !hashingWorker.awaitTermination(
					SHUTDOWN_TERMINATION_GRACE.toNanos(), TimeUnit.NANOSECONDS)) {
				log.warn("Hashing workers did not terminate in time.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		signalCoordinationChanged();
		log.info("Mining service stopped gracefully.");
	}

	/**
	 * Pauses mining (stops the loop) but keeps the main executor alive.
	 * Used during synchronization.
	 */
	public void pauseMining() {
		if (!suspendAutonomousMining(MiningSuspensionReason.SYNC, Duration.ofSeconds(30))) {
			log.warn("Mining remains safely suspended while proof-of-work initialization finishes; sync will continue");
		}
	}

	/**
	 * Resumes mining (alias for startMining).
	 * Used during synchronization.
	 */
	public void resumeMining() {
		resumeAutonomousMining(MiningSuspensionReason.SYNC);
	}

	public void startMining() {
		autonomousDesired.set(true);
		scheduleAutonomousMiningIfAllowed();
	}

	public boolean pauseAutonomousMining(Duration timeout) {
		return suspendAutonomousMining(MiningSuspensionReason.SANDBOX_CONTROL, timeout);
	}

	public void resumeAutonomousMining() {
		resumeAutonomousMining(MiningSuspensionReason.SANDBOX_CONTROL);
	}

	public boolean suspendAutonomousMining(MiningSuspensionReason reason, Duration timeout) {
		validateWaitTimeout(timeout);
		coordinationLock.lock();
		try {
			suspensions.add(reason);
			long remaining = timeout.toNanos();
			while (!isQuiescentFor(reason) && remaining > 0) {
				if (activeWork == ActiveWork.AUTONOMOUS
						|| reason == MiningSuspensionReason.SYNC && activeWork == ActiveWork.EXACT_ONE) {
					requestActiveAttemptCancellation(false);
				}
				try {
					remaining = coordinationChanged.awaitNanos(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return isQuiescentFor(reason);
		} finally {
			coordinationLock.unlock();
		}
	}

	private boolean isQuiescentFor(MiningSuspensionReason reason) {
		if (activeWork == ActiveWork.AUTONOMOUS || isMining.get()) {
			return false;
		}
		return reason != MiningSuspensionReason.SYNC
				|| activeWork != ActiveWork.EXACT_ONE && exactOneOperation == null;
	}

	public void resumeAutonomousMining(MiningSuspensionReason reason) {
		coordinationLock.lock();
		try {
			suspensions.remove(reason);
		} finally {
			coordinationLock.unlock();
		}
		scheduleAutonomousMiningIfAllowed();
	}

	public AutonomousMiningState getAutonomousMiningState() {
		coordinationLock.lock();
		try {
			return new AutonomousMiningState(
					miningConfig.getEnable(),
					autonomousDesired.get(),
					isMining.get() && activeWork != ActiveWork.AUTONOMOUS,
					activeWork == ActiveWork.AUTONOMOUS,
					shutdown.get(),
					Set.copyOf(suspensions));
		} finally {
			coordinationLock.unlock();
		}
	}

	/**
	 * Interrupts the current PoW search when a new block is received via P2P.
	 */
	public void stopCurrentNonceSearch() {
		requestActiveAttemptCancellation(true);
	}

	private void runMiningLoop() {
		beginActiveWork(ActiveWork.AUTONOMOUS, MiningAttemptContext.autonomous());
		try {
			while (autonomousMiningAllowed()) {
				Thread.interrupted();
				MiningAttemptContext context = MiningAttemptContext.autonomous();
				activeAttempt = context;
				ExactOneMiningOutcome outcome = runMiningAttempt(Optional.empty(), context);
				if (outcome.code() == ExactOneMiningOutcome.Code.FAILED) {
					log.error("Fatal mining attempt failure. Autonomous mining stopped.");
					autonomousDesired.set(false);
					break;
				}
				if (outcome.code() == ExactOneMiningOutcome.Code.RETRYABLE && context.retryDelayMillis > 0
						&& autonomousMiningAllowed()) {
					try {
						Thread.sleep(context.retryDelayMillis);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				if (outcome.code() == ExactOneMiningOutcome.Code.NOT_ELIGIBLE && autonomousMiningAllowed()) {
					try {
						Thread.sleep(10_000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
		} finally {
			activeAttempt = null;
			isMining.set(false);
			endActiveWork();
		}
	}

	public CompletableFuture<ExactOneMiningOutcome> mineExactlyOne(ExactOneMiningRequest request) {
		return mineExactly(request, 1);
	}

	public CompletableFuture<ExactOneMiningOutcome> mineExactly(
			ExactOneMiningRequest request, int blockCount) {
		Objects.requireNonNull(request, "request");
		if (blockCount < 1 || blockCount > 1_000) {
			throw new IllegalArgumentException("blockCount must be in range 1..1000");
		}
		MiningAttemptContext context = MiningAttemptContext.exactOne(request.deadline());
		CompletableFuture<ExactOneMiningOutcome> result = new CompletableFuture<>();
		ExactOneOperation operation = new ExactOneOperation(request, blockCount, context, result);
		coordinationLock.lock();
		try {
			if (shutdown.get()) {
				return CompletableFuture.completedFuture(
						ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.REJECTED_SHUTDOWN));
			}
			if (suspensions.contains(MiningSuspensionReason.SYNC)) {
				return CompletableFuture.completedFuture(
						ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.REJECTED_SYNCING));
			}
			if (miningConfig.getEnable() && !suspensions.contains(MiningSuspensionReason.SANDBOX_CONTROL)) {
				return CompletableFuture.completedFuture(
						ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.REJECTED_NOT_PAUSED));
			}
			if (activeWork != ActiveWork.NONE || exactOneOperation != null) {
				return CompletableFuture.completedFuture(
						ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.REJECTED_BUSY));
			}
			exactOneOperation = operation;
			result.whenComplete((ignored, failure) -> {
				if (result.isCancelled()) {
					cancelExactOneOperation(operation);
				}
			});
			try {
				operation.deadlineTask = exactOneDeadlineExecutor.schedule(
						() -> expireExactOneOperation(operation),
						operation.context.remainingNanos(),
						TimeUnit.NANOSECONDS);
				blockMiningExecutor.submit(() -> runExactOne(operation));
			} catch (RejectedExecutionException e) {
				exactOneOperation = null;
				terminalizeOperationLocked(operation,
						ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.REJECTED_SHUTDOWN));
				coordinationChanged.signalAll();
			}
			return result;
		} finally {
			coordinationLock.unlock();
		}
	}

	private void runExactOne(ExactOneOperation operation) {
		if (!beginExactOne(operation)) {
			return;
		}
		try {
			ExactOneMiningOutcome outcome = null;
			for (int index = 0; index < operation.blockCount; index++) {
				Optional<Instant> timestamp = index == 0
						? operation.request.scheduledTimestamp()
						: Optional.empty();
				outcome = runMiningAttempt(timestamp, operation.context);
				if (!outcome.accepted()) {
					break;
				}
				if (index + 1 < operation.blockCount && !resumeExactBatch(operation)) {
					break;
				}
			}
			completeExactOneOperation(operation, Objects.requireNonNull(outcome, "mining outcome"));
		} catch (RuntimeException | LinkageError failure) {
			log.error("Exact-one mining attempt failed", failure);
			completeExactOneOperation(operation,
					ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.FAILED));
		} finally {
			completeExactOneOperation(operation,
					ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.FAILED));
			finishExactOne(operation);
			scheduleAutonomousMiningIfAllowed();
		}
	}

	private boolean resumeExactBatch(ExactOneOperation operation) {
		coordinationLock.lock();
		try {
			if (exactOneOperation != operation
					|| operation.state != OperationState.SUBMITTING
					|| shutdown.get()) {
				return false;
			}
			long remainingNanos = operation.context.remainingNanos();
			if (remainingNanos <= 0) {
				operation.context.cancelled.set(true);
				terminalizeOperationLocked(operation,
						ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.TIMED_OUT));
				return false;
			}
			operation.state = OperationState.RUNNING;
			operation.deadlineTask = exactOneDeadlineExecutor.schedule(
					() -> expireExactOneOperation(operation), remainingNanos, TimeUnit.NANOSECONDS);
			coordinationChanged.signalAll();
			return true;
		} catch (RejectedExecutionException failure) {
			terminalizeOperationLocked(operation,
					ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.REJECTED_SHUTDOWN));
			return false;
		} finally {
			coordinationLock.unlock();
		}
	}

	private boolean beginExactOne(ExactOneOperation operation) {
		coordinationLock.lock();
		try {
			if (exactOneOperation != operation) {
				return false;
			}
			if (operation.state == OperationState.TERMINAL) {
				exactOneOperation = null;
				coordinationChanged.signalAll();
				return false;
			}
			ExactOneMiningOutcome.Code rejection = null;
			if (shutdown.get()) {
				rejection = ExactOneMiningOutcome.Code.REJECTED_SHUTDOWN;
			} else if (suspensions.contains(MiningSuspensionReason.SYNC)) {
				rejection = ExactOneMiningOutcome.Code.REJECTED_SYNCING;
			} else if (operation.context.isCancelled()) {
				rejection = operation.context.deadlineExceeded()
						? ExactOneMiningOutcome.Code.TIMED_OUT
						: ExactOneMiningOutcome.Code.CANCELLED;
			}
			if (rejection != null) {
				exactOneOperation = null;
				terminalizeOperationLocked(operation, ExactOneMiningOutcome.of(rejection));
				coordinationChanged.signalAll();
				return false;
			}
			operation.state = OperationState.RUNNING;
			activeWork = ActiveWork.EXACT_ONE;
			activeAttempt = operation.context;
			miningThread = Thread.currentThread();
			coordinationChanged.signalAll();
			return true;
		} finally {
			coordinationLock.unlock();
		}
	}

	private void finishExactOne(ExactOneOperation operation) {
		coordinationLock.lock();
		try {
			if (exactOneOperation == operation) {
				exactOneOperation = null;
			}
			if (activeAttempt == operation.context) {
				activeAttempt = null;
				activeWork = ActiveWork.NONE;
				miningThread = null;
			}
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}
	}

	private void cancelExactOneOperation(ExactOneOperation operation) {
		boolean active;
		coordinationLock.lock();
		try {
			if (operation.state == OperationState.SUBMITTING
					|| operation.state == OperationState.TERMINAL) {
				return;
			}
			operation.context.cancelled.set(true);
			terminalizeOperationLocked(operation,
					ExactOneMiningOutcome.of(ExactOneMiningOutcome.Code.CANCELLED));
			active = activeAttempt == operation.context;
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}
		if (active) {
			requestActiveAttemptCancellation(false);
		}
	}

	private void expireExactOneOperation(ExactOneOperation operation) {
		boolean active;
		coordinationLock.lock();
		try {
			if (exactOneOperation != operation
					|| operation.state == OperationState.SUBMITTING
					|| operation.state == OperationState.TERMINAL) {
				return;
			}
			operation.context.cancelled.set(true);
			terminalizeOperationLocked(operation,
					cancelledOutcome(operation.context, null));
			active = activeAttempt == operation.context;
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}
		if (active) {
			requestActiveAttemptCancellation(false);
		}
	}

	private void completeExactOneOperation(
			ExactOneOperation operation,
			ExactOneMiningOutcome outcome) {
		coordinationLock.lock();
		try {
			terminalizeOperationLocked(operation, outcome);
		} finally {
			coordinationLock.unlock();
		}
	}

	private void terminalizeOperationLocked(
			ExactOneOperation operation,
			ExactOneMiningOutcome outcome) {
		if (operation.state == OperationState.TERMINAL) {
			return;
		}
		operation.state = OperationState.TERMINAL;
		ScheduledFuture<?> deadlineTask = operation.deadlineTask;
		if (deadlineTask != null) {
			deadlineTask.cancel(false);
		}
		operation.result.complete(outcome);
	}

	private ExactOneMiningOutcome runMiningAttempt(
			Optional<Instant> requestedTimestamp,
			MiningAttemptContext context) {
		if (context.isCancelled()) {
			return cancelledOutcome(context, null);
		}
		Block parentBlock;
		masterChainLock.lock();
		try {
			parentBlock = chainQueryService.getLatestStoredBlockOrThrow().getBlock();
			context.parentHash = parentBlock.getHash();
		} finally {
			masterChainLock.unlock();
		}

		try {
			if (context.isCancelled()) {
				return cancelledOutcome(context, null);
			}
			Optional<MiningBlockAssemblerService.AssembledBlock> assembledBlockOptional = assembleBlock(
					parentBlock, requestedTimestamp, context);
			if (context.isCancelled()) {
				return cancelledOutcome(context, null);
			}
			if (assembledBlockOptional.isEmpty()) {
				return outcome(ExactOneMiningOutcome.Code.NOT_ELIGIBLE, context, null, null);
			}

			MiningBlockAssemblerService.AssembledBlock assembledBlock = assembledBlockOptional.orElseThrow();
			removeInvalidTransactions(assembledBlock);
			MiningBlockAssemblerService.BlockHeaderTemplate template = assembledBlock.getBlockTemplate();

			if (context.isCancelled()) {
				return cancelledOutcome(context, template.getHeight());
			}
			prepareProofOfWorkForMining(template.getHeight(), context);
			if (context.isCancelled()) {
				return cancelledOutcome(context, template.getHeight());
			}

			BigInteger target = DifficultyUtil.calculateTargetFromDifficulty(template.getDifficulty());
			log.debug("Mining block {} | Diff: {} | TargetPrefix: {}...",
					template.getHeight(), template.getDifficulty(),
					target.toString(16).substring(0, Math.min(10, target.toString(16).length())));

			long nonceStart = System.currentTimeMillis();
			Long foundNonce = findNonce(template, target, context);
			long durationMs = System.currentTimeMillis() - nonceStart;
			registry.timer("mining.cycle_time").record(Duration.ofMillis(durationMs));
			if (foundNonce == null) {
				return cancelledOutcome(context, template.getHeight());
			}
			if (context.isCancelled()) {
				shutdownHashingWorker();
				return cancelledOutcome(context, template.getHeight());
			}

			registry.counter("mining.blocks_found").increment();
			return processMinedBlock(template, assembledBlock, foundNonce, durationMs, context);
		} catch (GETxValidationFailedException e) {
			mempoolService.removeTransaction(e.getFailedTx().getHash());
			log.warn("Tx validation failed during mining: {}", e.getMessage());
			return outcome(context.exactOne ? ExactOneMiningOutcome.Code.FAILED : ExactOneMiningOutcome.Code.RETRYABLE,
					context, null, null);
		} catch (ProofOfWorkMiningException e) {
			shutdownHashingWorker();
			log.error("Proof-of-work mining attempt failed", e);
			return outcome(ExactOneMiningOutcome.Code.FAILED, context, null, null);
		} catch (Exception e) {
			log.error("Mining attempt failed", e);
			if (!context.exactOne) {
				context.retryDelayMillis = 5_000;
			}
			return outcome(context.exactOne ? ExactOneMiningOutcome.Code.FAILED : ExactOneMiningOutcome.Code.RETRYABLE,
					context, null, null);
		}
	}

	private Optional<MiningBlockAssemblerService.AssembledBlock> assembleBlock(
			Block parentBlock,
			Optional<Instant> requestedTimestamp,
			MiningAttemptContext context) throws Exception {
		if (!context.exactOne) {
			return miningBlockAssemblerService.createBlockTemplate(parentBlock);
		}
		try (BlockTimestampReservation timestamp = chainClock.reserveNextBlockTimestamp(
				parentBlock.getHeader(), requestedTimestamp)) {
			return miningBlockAssemblerService.createBlockTemplate(parentBlock, timestamp);
		}
	}

	private void removeInvalidTransactions(MiningBlockAssemblerService.AssembledBlock assembledBlock) {
		if (assembledBlock.getInvalidTxs() == null || assembledBlock.getInvalidTxs().isEmpty()) {
			return;
		}
		List<Hash> invalidHashes = assembledBlock.getInvalidTxs().stream()
				.map(Tx::getHash)
				.collect(Collectors.toList());
		mempoolService.removeTransactions(invalidHashes);
	}

	private ExactOneMiningOutcome cancelledOutcome(MiningAttemptContext context, Long height) {
		ExactOneMiningOutcome.Code code;
		if (context.parentChanged.get()) {
			code = ExactOneMiningOutcome.Code.STALE_PARENT;
		} else if (context.deadlineExceeded()) {
			code = ExactOneMiningOutcome.Code.TIMED_OUT;
		} else {
			code = ExactOneMiningOutcome.Code.CANCELLED;
		}
		return outcome(code, context, height, null);
	}

	private ExactOneMiningOutcome outcome(
			ExactOneMiningOutcome.Code code,
			MiningAttemptContext context,
			Long height,
			Hash blockHash) {
		return new ExactOneMiningOutcome(code, context.parentHash, height, blockHash, null);
	}

	private void prepareProofOfWorkForMining(long height, MiningAttemptContext context) {
		coordinationLock.lock();
		try {
			if (context.isCancelled() || shutdown.get()) {
				context.cancelled.set(true);
				return;
			}
			proofOfWorkInitializationActive = true;
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}
		try {
			proofOfWorkProvider.prepareForMining(height);
		} catch (ProofOfWorkMiningException failure) {
			throw failure;
		} catch (RuntimeException | LinkageError failure) {
			throw new ProofOfWorkMiningException(
					"Proof-of-work provider initialization failed for height " + height,
					failure);
		} finally {
			coordinationLock.lock();
			try {
				proofOfWorkInitializationActive = false;
				coordinationChanged.signalAll();
			} finally {
				coordinationLock.unlock();
			}
		}
	}

	private ExecutorService shutdownHashingWorker() {
		synchronized (hashingWorkerLock) {
			ExecutorService worker = blockHashingWorker;
			blockHashingWorker = null;
			if (worker != null) {
				worker.shutdownNow();
			}
			return worker;
		}
	}

	private ExecutorService hashingWorkerFor(MiningAttemptContext context) {
		synchronized (hashingWorkerLock) {
			if (context.isCancelled() || shutdown.get()) {
				return null;
			}
			if (blockHashingWorker == null || blockHashingWorker.isShutdown()) {
				blockHashingWorker = Executors.newFixedThreadPool(hashingThreads, minerThreadFactory);
			}
			if (context.isCancelled() || shutdown.get()) {
				blockHashingWorker.shutdownNow();
				blockHashingWorker = null;
				return null;
			}
			return blockHashingWorker;
		}
	}

	private Long findNonce(MiningBlockAssemblerService.BlockHeaderTemplate template, BigInteger target) {
		MiningAttemptContext context = activeAttempt;
		if (context == null) {
			context = MiningAttemptContext.autonomous();
		}
		return findNonce(template, target, context);
	}

	private Long findNonce(MiningBlockAssemblerService.BlockHeaderTemplate template, BigInteger target,
			MiningAttemptContext context) {
		return findNonce(template, target, context, 0, 1);
	}

	private Long findNonce(
			MiningBlockAssemblerService.BlockHeaderTemplate template,
			BigInteger target,
			MiningAttemptContext context,
			int nonceSearchOffset,
			int nonceSearchStride) {
		proofOfWorkInvocationCount.incrementAndGet();

		ExecutorService hashingWorker = hashingWorkerFor(context);
		if (hashingWorker == null) {
			return null;
		}

		ProofOfWorkTarget proofOfWorkTarget = ProofOfWorkTarget.of(target);

		AtomicReference<Long> foundNonce = new AtomicReference<>();
		AtomicReference<Throwable> workerFailure = new AtomicReference<>();
		List<Callable<Void>> tasks = new ArrayList<>(hashingThreads);
		// Range splitting
		final long chunkSize = (Long.MAX_VALUE / hashingThreads);

		for (int i = 0; i < hashingThreads; i++) {
			final long start = (long) i * chunkSize;
			final long end = (i == hashingThreads - 1) ? Long.MAX_VALUE : (start + chunkSize);

			// Optimization: Avoid Tuweni Bytes wrapper in inner loop
			final byte[] baseHeader = BlockHeaderUtil.powInput(template.toBlockHeader());

			tasks.add(() -> {
				try (ProofOfWorkHasher hasher = proofOfWorkProvider.openMiningHasher()) {
					// Optimization: Direct buffer manipulation
					byte[] workBuffer = new byte[baseHeader.length];
					System.arraycopy(baseHeader, 0, workBuffer, 0, baseHeader.length);
					int nonceOffset = workBuffer.length - 8;

					long currentNonce = alignedNonceStart(start, nonceSearchOffset, nonceSearchStride);
					int batchCounter = 0;

					while (currentNonce < end) {
						if (workerFailure.get() != null) {
							return null;
						}
						// Optimization: Bitwise check is faster than modulo
						if ((++batchCounter & 0xFF) == 0) {
							if (foundNonce.get() != null || Thread.currentThread().isInterrupted()
									|| context.isCancelled())
								return null;
						}

						// Write Nonce (Big Endian)
						workBuffer[nonceOffset] = (byte) (currentNonce >>> 56);
						workBuffer[nonceOffset + 1] = (byte) (currentNonce >>> 48);
						workBuffer[nonceOffset + 2] = (byte) (currentNonce >>> 40);
						workBuffer[nonceOffset + 3] = (byte) (currentNonce >>> 32);
						workBuffer[nonceOffset + 4] = (byte) (currentNonce >>> 24);
						workBuffer[nonceOffset + 5] = (byte) (currentNonce >>> 16);
						workBuffer[nonceOffset + 6] = (byte) (currentNonce >>> 8);
						workBuffer[nonceOffset + 7] = (byte) (currentNonce);

						byte[] hashBytes = hasher.hash(workBuffer);

						if (proofOfWorkTarget.accepts(hashBytes)) {
							foundNonce.set(currentNonce);
							return null;
						}

						if (currentNonce > end - nonceSearchStride) {
							break;
						}
						currentNonce += nonceSearchStride;
					}
				} catch (RuntimeException | Error failure) {
					workerFailure.compareAndSet(null, failure);
					throw failure;
				}
				return null;
			});
		}

		try {
			List<Future<Void>> futures;
			if (context.exactOne) {
				long remaining = context.remainingNanos();
				if (remaining <= 0) {
					return null;
				}
				futures = hashingWorker.invokeAll(tasks, remaining, TimeUnit.NANOSECONDS);
			} else {
				futures = hashingWorker.invokeAll(tasks);
			}
			for (Future<Void> future : futures) {
				if (future.isCancelled()) {
					return null;
				}
				future.get();
			}
		} catch (InterruptedException e) {
			// Main thread interrupted (e.g. new block found)
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			throw new ProofOfWorkMiningException("Proof-of-work worker failed", e.getCause());
		} catch (RejectedExecutionException e) {
			// Executor shutdown
			return null;
		} finally {
			if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
				shutdownHashingWorker();
			}
		}

		return foundNonce.get();
	}

	static long alignedNonceStart(long workerStart, int nonceSearchOffset, int nonceSearchStride) {
		long residue = Math.floorMod(workerStart, nonceSearchStride);
		long adjustment = Math.floorMod(nonceSearchOffset - residue, nonceSearchStride);
		return Math.addExact(workerStart, adjustment);
	}

	public long getProofOfWorkInvocationCount() {
		return proofOfWorkInvocationCount.get();
	}

	private ExactOneMiningOutcome processMinedBlock(MiningBlockAssemblerService.BlockHeaderTemplate template,
			MiningBlockAssemblerService.AssembledBlock assembledBlock,
			Long nonce, double durationMs, MiningAttemptContext context) {

		masterChainLock.lock();
		try {
			if (context.isCancelled()) {
				return cancelledOutcome(context, template.getHeight());
			}
			StoredBlock currentTipStored = chainQueryService.getLatestStoredBlockOrThrow();

			// Check if we are still on the correct parent - use StoredBlock.getHash()
			if (template.getPreviousHash().equals(currentTipStored.getHash())) {
				log.info("SUCCESS: Block mined #{} in {}s (Nonce: {})",
						template.getHeight(), String.format("%.2f", durationMs / 1000.0), nonce);

				// 1. Construct Header with the FOUND Nonce
				BlockHeaderImpl headerForSigning = BlockHeaderImpl.builder()
						.version(template.getVersion())
						.height(template.getHeight())
						.timestamp(template.getTimestamp())
						.previousHash(template.getPreviousHash())
						.difficulty(template.getDifficulty())
						.coinbase(template.getCoinbase())
						.txRootHash(template.getTxRootHash())
						.stateRootHash(template.getStateRootHash())
						.nonce(nonce)
						.build();

				// 2. Sign
				Hash hashForSigning = BlockHeaderUtil.hashForSigning(headerForSigning);
				Signature signature = identityService.getPrivateKey().sign(hashForSigning);

				// 3. Create Final Block
				BlockImpl foundBlock = BlockImpl.builder()
						.header(headerForSigning.toBuilder().signature(signature).build())
						.txs(assembledBlock.getTxs())
						.build();

				context.submittedHash = foundBlock.getHash();
				if (!beginBlockSubmission(context)) {
					return cancelledOutcome(context, template.getHeight());
				}
				BlockIngestionOutcome ingestionOutcome = blockIngestionService.processBlock(
						foundBlock,
						ConnectedSource.MINER,
						identityService.getNodeIdentityAddress(),
						foundBlock.getHeader().getTimestamp());
				ExactOneMiningOutcome.Code code = mapIngestionOutcome(ingestionOutcome);
				return new ExactOneMiningOutcome(code, context.parentHash, template.getHeight(), foundBlock.getHash(),
						ingestionOutcome.code());
			} else {
				log.warn("STALE: Block mined but chain moved (Target: {} -> Tip: {})",
						template.getPreviousHash().toShortLogString(),
						currentTipStored.getHash().toShortLogString());
				return outcome(ExactOneMiningOutcome.Code.STALE_PARENT, context, template.getHeight(), null);
			}
		} finally {
			masterChainLock.unlock();
		}
	}

	private boolean beginBlockSubmission(MiningAttemptContext context) {
		coordinationLock.lock();
		try {
			if (!context.exactOne) {
				return !shutdown.get() && !context.isCancelled();
			}
			ExactOneOperation operation = exactOneOperation;
			if (operation == null || operation.context != context
					|| operation.state != OperationState.RUNNING
					|| shutdown.get() || context.isCancelled()) {
				return false;
			}
			operation.state = OperationState.SUBMITTING;
			ScheduledFuture<?> deadlineTask = operation.deadlineTask;
			if (deadlineTask != null) {
				deadlineTask.cancel(false);
			}
			coordinationChanged.signalAll();
			return true;
		} finally {
			coordinationLock.unlock();
		}
	}

	/** Retained for focused legacy tests of stale-parent suppression. */
	@SuppressWarnings("unused")
	private void processMinedBlock(MiningBlockAssemblerService.BlockHeaderTemplate template,
			MiningBlockAssemblerService.AssembledBlock assembledBlock,
			Long nonce, double durationMs) {
		MiningAttemptContext context = MiningAttemptContext.autonomous();
		context.parentHash = template.getPreviousHash();
		processMinedBlock(template, assembledBlock, nonce, durationMs, context);
	}

	private ExactOneMiningOutcome.Code mapIngestionOutcome(BlockIngestionOutcome outcome) {
		return switch (outcome.code()) {
			case ACCEPTED, ALREADY_EXISTS -> ExactOneMiningOutcome.Code.ACCEPTED;
			case ORPHAN_BUFFERED, GAP_DETECTED -> ExactOneMiningOutcome.Code.STALE_PARENT;
			case REJECTED_STATELESS, REJECTED_CONTEXTUAL, REJECTED_CONSENSUS_POLICY,
					REJECTED_EXECUTION, REJECTED_STATE_ROOT -> ExactOneMiningOutcome.Code.REJECTED_BY_INGESTION;
			case INTERNAL_FAILURE -> ExactOneMiningOutcome.Code.FAILED;
		};
	}

	@EventListener
	public void onNewBlockConnected(BlockConnectedEvent event) {
		MiningAttemptContext context = activeAttempt;
		if (context == null) {
			if (isMining.get()) {
				stopCurrentNonceSearch();
			}
			return;
		}
		if (context.submittedHash != null && context.submittedHash.equals(event.getBlock().getHash())) {
			return;
		}
		context.parentChanged.set(true);
		stopCurrentNonceSearch();
	}

	private void scheduleAutonomousMiningIfAllowed() {
		coordinationLock.lock();
		try {
			if (!autonomousMiningAllowed() || exactOneOperation != null || activeWork != ActiveWork.NONE
					|| !isMining.compareAndSet(false, true)) {
				return;
			}
			try {
				blockMiningExecutor.submit(this::runMiningLoop);
				log.info("Mining started");
			} catch (RejectedExecutionException e) {
				isMining.set(false);
			}
		} finally {
			coordinationLock.unlock();
		}
	}

	private boolean autonomousMiningAllowed() {
		coordinationLock.lock();
		try {
			return miningConfig.getEnable() && autonomousDesired.get() && !shutdown.get() && suspensions.isEmpty();
		} finally {
			coordinationLock.unlock();
		}
	}

	private void beginActiveWork(ActiveWork work, MiningAttemptContext context) {
		coordinationLock.lock();
		try {
			activeWork = work;
			activeAttempt = context;
			miningThread = Thread.currentThread();
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}
	}

	private void endActiveWork() {
		coordinationLock.lock();
		try {
			activeWork = ActiveWork.NONE;
			miningThread = null;
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}
	}

	private void requestActiveAttemptCancellation(boolean parentChanged) {
		Thread activeThread;
		boolean initializationActive;
		coordinationLock.lock();
		try {
			MiningAttemptContext context = activeAttempt;
			if (context != null && context.exactOne && isExactOneSubmittingLocked()) {
				return;
			}
			if (context != null) {
				context.cancelled.set(true);
				if (parentChanged) {
					context.parentChanged.set(true);
				}
			}
			activeThread = miningThread;
			initializationActive = proofOfWorkInitializationActive
					|| proofOfWorkProvider.isInitializationInProgress();
		} finally {
			coordinationLock.unlock();
		}
		if (activeThread == null || initializationActive) {
			return;
		}
		activeThread.interrupt();
		shutdownHashingWorker();
	}

	private void signalCoordinationChanged() {
		coordinationLock.lock();
		try {
			coordinationChanged.signalAll();
		} finally {
			coordinationLock.unlock();
		}
	}

	private boolean awaitNonInterruptibleWork(Duration timeout) {
		long now = System.nanoTime();
		long timeoutNanos = timeout.toNanos();
		long deadline = now > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
		coordinationLock.lock();
		try {
			long remaining = Math.max(0, deadline - System.nanoTime());
			while ((proofOfWorkInitializationActive || proofOfWorkProvider.isInitializationInProgress()
					|| isExactOneSubmittingLocked())
					&& remaining > 0) {
				try {
					coordinationChanged.awaitNanos(
							Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
				remaining = Math.max(0, deadline - System.nanoTime());
			}
			return !proofOfWorkInitializationActive && !proofOfWorkProvider.isInitializationInProgress()
					&& !isExactOneSubmittingLocked();
		} finally {
			coordinationLock.unlock();
		}
	}

	private boolean isExactOneSubmittingLocked() {
		return exactOneOperation != null && exactOneOperation.state == OperationState.SUBMITTING;
	}

	private void validateWaitTimeout(Duration timeout) {
		if (timeout == null || timeout.isNegative()) {
			throw new IllegalArgumentException("Mining quiescence timeout cannot be null or negative");
		}
	}

	private int getHashingThreads() {
		return miningConfig.resolveHashingThreads(Runtime.getRuntime().availableProcessors());
	}

	private static Thread newDeadlineThread(Runnable task) {
		Thread thread = new Thread(task, "exact-one-mining-deadline");
		thread.setDaemon(true);
		return thread;
	}

	private enum ActiveWork {
		NONE,
		AUTONOMOUS,
		EXACT_ONE
	}

	private enum OperationState {
		QUEUED,
		RUNNING,
		SUBMITTING,
		TERMINAL
	}

	private static final class ExactOneOperation {
		private final ExactOneMiningRequest request;
		private final int blockCount;
		private final MiningAttemptContext context;
		private final CompletableFuture<ExactOneMiningOutcome> result;
		private OperationState state = OperationState.QUEUED;
		private ScheduledFuture<?> deadlineTask;

		private ExactOneOperation(
				ExactOneMiningRequest request,
				int blockCount,
				MiningAttemptContext context,
				CompletableFuture<ExactOneMiningOutcome> result) {
			this.request = request;
			this.blockCount = blockCount;
			this.context = context;
			this.result = result;
		}
	}

	private static final class MiningAttemptContext {
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final AtomicBoolean parentChanged = new AtomicBoolean();
		private final long deadlineNanos;
		private final boolean exactOne;
		private volatile Hash parentHash;
		private volatile Hash submittedHash;
		private volatile long retryDelayMillis;

		private MiningAttemptContext(long deadlineNanos, boolean exactOne) {
			this.deadlineNanos = deadlineNanos;
			this.exactOne = exactOne;
		}

		private static MiningAttemptContext autonomous() {
			return new MiningAttemptContext(Long.MAX_VALUE, false);
		}

		private static MiningAttemptContext exactOne(Duration deadline) {
			long now = System.nanoTime();
			long nanos = deadline.toNanos();
			long end = now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
			return new MiningAttemptContext(end, true);
		}

		private boolean deadlineExceeded() {
			return deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0;
		}

		private long remainingNanos() {
			if (deadlineNanos == Long.MAX_VALUE) {
				return Long.MAX_VALUE;
			}
			return Math.max(0, deadlineNanos - System.nanoTime());
		}

		private boolean isCancelled() {
			return cancelled.get() || deadlineExceeded();
		}
	}
}
