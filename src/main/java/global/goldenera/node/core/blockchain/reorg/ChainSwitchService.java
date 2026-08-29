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
package global.goldenera.node.core.blockchain.reorg;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.units.ethereum.Wei;
import org.rocksdb.WriteBatch;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockConnectionBatchCompletedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockReorgEvent;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.blockchain.state.ValidatedReorgPlan;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalAppender;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalDraft;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ChainSwitchService {

    ChainQuery chainQueryService;
    BlockRepository blockRepository;
    WorldStateFactory worldStateFactory;
    StateProcessor stateProcessor;
    BlockValidator blockValidationService;
    ApplicationEventPublisher applicationEventPublisher;
    ReentrantLock masterChainLock;
    EntityIndexRepository entityIndexRepository;
    BlockEventExtractor blockEventExtractor;
    CoreSnapshotCheckpointFloorPolicy checkpointFloorPolicy;
    LifecycleJournalAppender lifecycleJournalAppender;

    public ChainSwitchService(
            ChainQuery chainQueryService,
            BlockRepository blockRepository,
            WorldStateFactory worldStateFactory,
            StateProcessor stateProcessor,
            BlockValidator blockValidationService,
            ApplicationEventPublisher applicationEventPublisher,
            @Qualifier("masterChainLock") ReentrantLock masterChainLock,
            EntityIndexRepository entityIndexRepository,
            BlockEventExtractor blockEventExtractor,
            CoreSnapshotCheckpointFloorPolicy checkpointFloorPolicy,
            LifecycleJournalAppender lifecycleJournalAppender) {
        this.chainQueryService = chainQueryService;
        this.blockRepository = blockRepository;
        this.worldStateFactory = worldStateFactory;
        this.stateProcessor = stateProcessor;
        this.blockValidationService = blockValidationService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.masterChainLock = masterChainLock;
        this.entityIndexRepository = entityIndexRepository;
        this.blockEventExtractor = blockEventExtractor;
        this.checkpointFloorPolicy = checkpointFloorPolicy;
        this.lifecycleJournalAppender = lifecycleJournalAppender;
    }

    void executeAtomicSyncSwap(
            @NonNull StoredBlock commonAncestor,
            @NonNull List<StoredBlock> newChainHeaders) throws Exception {
        executeAtomicReorgSwap(commonAncestor, newChainHeaders, false);
    }

    void stageValidatedSyncBatch(
            @NonNull StoredBlock branchParent,
            @NonNull List<StoredBlock> blocks) {
        masterChainLock.lock();
        try {
            StoredBlock storedParent = chainQueryService.getStoredBlockByHash(branchParent.getHash())
                    .orElseThrow(() -> new GEFailedException(
                            "Sync staging parent is not stored: " + branchParent.getHash()));
            validateCandidateMetadata(storedParent, blocks);
            blockRepository.executeAtomicBatch(batch -> stageCandidateBlocks(batch, storedParent, blocks));
        } catch (RuntimeException e) {
            if (e.getCause() instanceof GEFailedException failure) {
                throw failure;
            }
            throw new GEFailedException("SYNC fork staging failed: " + e.getMessage(), e);
        } finally {
            masterChainLock.unlock();
        }
    }

    public void executeAtomicReorgSwap(@NonNull ValidatedReorgPlan plan) throws Exception {
        executeAtomicReorgSwap(plan.commonAncestor(), plan.blocks(), true);
    }

    private void executeAtomicReorgSwap(
            @NonNull StoredBlock commonAncestor,
            @NonNull List<StoredBlock> newChainHeaders,
            boolean saveTipData) throws Exception {

        List<Object> eventsToPublish = new ArrayList<>();
        masterChainLock.lock();
        try {
            StoredBlock currentBestBlock = chainQueryService.getLatestStoredBlockOrThrow();
            StoredBlock canonicalAncestor = validateCandidateWork(
                    commonAncestor, newChainHeaders, currentBestBlock);
            checkpointFloorPolicy.assertCommonAncestorAllowed(canonicalAncestor);
            // findChainFrom returns List<StoredBlock> - use StoredBlock.getHash() for
            // comparison
            List<StoredBlock> oldChainStored = chainQueryService.findChainFrom(
                    canonicalAncestor.getHash(), currentBestBlock.getHash());
            Collections.reverse(oldChainStored);

            List<BlockDisconnectedEvent> blockDisconnectedEvents = new ArrayList<>();
            List<BlockConnectedEvent> blockConnectedEvents = new ArrayList<>();

            boolean isReorg = !oldChainStored.isEmpty();
            String opName = isReorg ? "REORG" : "SYNC";

            if (isReorg || oldChainStored.size() > 0) {
                log.info("{} STARTING: disconnecting {} blocks, connecting {} blocks (common ancestor at height {})",
                        opName, oldChainStored.size(), newChainHeaders.size(), canonicalAncestor.getHeight());
            } else {
                log.info("SYNC: connecting {} blocks from height {}",
                        newChainHeaders.size(), canonicalAncestor.getHeight() + 1);
            }

            try {
                UUID journalGroupId = UUID.randomUUID();
                Instant journalOccurredAt = Instant.now();
                int journalGroupSize = oldChainStored.size() + newChainHeaders.size() + (isReorg ? 1 : 0);
                List<LifecycleJournalDraft> journalDrafts = new ArrayList<>(journalGroupSize);
                blockRepository.executeAtomicBatch(batch -> {
                    for (StoredBlock storedBlockToDisconnect : oldChainStored) {
                        Block blockToDisconnect = storedBlockToDisconnect.getBlock();

                        StoredBlock parent = chainQueryService
                                .getStoredBlockByHash(blockToDisconnect.getHeader().getPreviousHash())
                                .orElseThrow(() -> new GEFailedException("Reorg parent not found"));

                        // Use StoredBlock.getHash() for pre-computed hash
                        blockRepository.addDisconnectBlockIndexToBatch(batch, storedBlockToDisconnect, parent);

                        entityIndexRepository.revertEntities(batch, blockToDisconnect);
                        blockDisconnectedEvents.add(new BlockDisconnectedEvent(this, blockToDisconnect));
                        journalDrafts.add(LifecycleJournalDraft.disconnect(
                                journalGroupId, journalDrafts.size(), journalGroupSize,
                                blockToDisconnect.getHeight(), blockToDisconnect.getHash(),
                                blockToDisconnect.getHeader().getPreviousHash(), journalOccurredAt,
                                ConnectedSource.REORG.getCode()));
                    }

                    Block previousBlock = canonicalAncestor.getBlock();

                    WorldState worldState = worldStateFactory
                            .createForValidation(previousBlock.getHeader().getStateRootHash());
                    Map<Long, BlockHeader> branchHeaders = new HashMap<>();
                    branchHeaders.put(canonicalAncestor.getHeight(), previousBlock.getHeader());

                    long batchStart = System.currentTimeMillis();
                    int progressInterval = Math.max(50, newChainHeaders.size() / 10); // Log every 10% or 50 blocks

                    for (int i = 0; i < newChainHeaders.size(); i++) {
                        StoredBlock storedBlockToConnect = newChainHeaders.get(i);
                        Block blockToConnect = storedBlockToConnect.getBlock();
                        NetworkParamsState params = worldState.getParams();
                        BlockHeader difficultyAnchor = resolveDifficultyAnchor(
                                params.getAsertAnchorHeight(), canonicalAncestor, branchHeaders);

                        try {
                            blockValidationService.validateHeaderContext(
                                    blockToConnect.getHeader(), previousBlock.getHeader(), worldState, difficultyAnchor);
                        } catch (Exception e) {
                            throw new GEFailedException(
                                    "Reorg failed: Block " + blockToConnect.getHeight() + " invalid: " + e.getMessage(),
                                    e);
                        }

                        ExecutionResult result;
                        try {
                            result = stateProcessor.executeTransactions(
                                    worldState, new SimpleBlock(blockToConnect), blockToConnect.getTxs(), params);
                        } catch (Exception e) {
                            throw new GEFailedException(
                                    "Reorg failed execution for block " + blockToConnect.getHash(), e);
                        }

                        if (!worldState.calculateRootHash().equals(blockToConnect.getHeader().getStateRootHash())) {
                            throw new GEFailedException(
                                    "Reorg failed: Invalid StateRoot for " + blockToConnect.getHash());
                        }

                        // Extract block events from execution result
                        Wei totalFees = result.getTotalFeesCollected();
                        Wei actualRewardPaid = result.getMinerActualRewardPaid();
                        Wei blockRewardFromPool = actualRewardPaid.subtract(totalFees);

                        List<BlockEvent> blockEvents = blockEventExtractor.extractEvents(
                                blockRewardFromPool,
                                totalFees,
								blockToConnect.getHeader().getCoinbase(),
								result.getMinerRewardPoolAddress(),
								result.getMinerRewardUnlockBlockHeight(),
                                worldState.getBipDiffs(),
                                worldState.getTokenDiffs(),
                                result.getActualBurnAmounts(),
                                worldState.getParamsDiff());

                        // Update StoredBlock with extracted events
                        StoredBlock storedBlockWithEvents = storedBlockToConnect.toBuilder()
                                .events(blockEvents)
                                .build();

                        worldState.persistToBatch(batch);
                        entityIndexRepository.saveEntities(batch, blockToConnect, worldState);

                        // Save block with events. All blocks (not just tip) need full persistence
                        // since they arrive from sync without events and get them during execution.
                        // For SYNC: use optimized method that skips cache (populated on-demand)
                        // For REORG: use regular method to populate cache (blocks will be accessed
                        // soon)
                        if (saveTipData && i == newChainHeaders.size() - 1) {
                            blockRepository.addBlockToBatch(batch, storedBlockWithEvents);
                        } else if (isReorg) {
                            blockRepository.saveBlockDataToBatch(batch, storedBlockWithEvents);
                            blockRepository.connectBlockIndexToBatch(batch, storedBlockWithEvents);
                        } else {
                            // SYNC - skip cache population for performance
                            blockRepository.saveBlockDataToBatchForSync(batch, storedBlockWithEvents);
                            blockRepository.connectBlockIndexToBatch(batch, storedBlockWithEvents);
                        }

                        BlockConnectedEvent event = new BlockConnectedEvent(
                                this,
                                isReorg ? ConnectedSource.REORG : ConnectedSource.SYNC,
                                blockToConnect,
                                worldState.getBalanceDiffs(),
                                worldState.getNonceDiffs(),
                                worldState.getTokenDiffs(),
                                worldState.getBipDiffs(),
                                worldState.getParamsDiff(),
                                worldState.getDirtyAuthorities(),
                                worldState.getAuthoritiesRemovedWithState(),
                                worldState.getDirtyValidators(),
                                worldState.getValidatorsRemovedWithState(),
                                worldState.getDirtyAddressAliases(),
                                worldState.getAliasesRemovedWithState(),
                                result.getMinerTotalFees(),
                                result.getMinerActualRewardPaid(),
                                storedBlockToConnect.getCumulativeDifficulty(),
                                result.getActualBurnAmounts(),
                                blockEvents,
                                null,
                                Instant.now(),
                                !isReorg);

                        blockConnectedEvents.add(event);
                        journalDrafts.add(LifecycleJournalDraft.connect(
                                journalGroupId, journalDrafts.size(), journalGroupSize,
                                blockToConnect.getHeight(), blockToConnect.getHash(),
                                blockToConnect.getHeader().getPreviousHash(), journalOccurredAt,
                                event.getConnectedSource().getCode(), null));
                        worldState.prepareForNextBlock();
                        previousBlock = blockToConnect;
                        branchHeaders.put(blockToConnect.getHeight(), blockToConnect.getHeader());

                        // Progress logging (only for large batches)
                        if (newChainHeaders.size() >= 50
                                && ((i + 1) % progressInterval == 0 || i == newChainHeaders.size() - 1)) {
                            long elapsed = System.currentTimeMillis() - batchStart;
                            double blocksPerSec = (i + 1) * 1000.0 / elapsed;
                            log.info("{} PROGRESS: {}/{} blocks processed ({}%) - {} blocks/sec",
                                    opName, i + 1, newChainHeaders.size(),
                                    (i + 1) * 100 / newChainHeaders.size(),
                                    String.format("%.1f", blocksPerSec));
                        }
                    }
                    if (isReorg) {
                        StoredBlock oldTip = oldChainStored.get(0);
                        StoredBlock newTip = newChainHeaders.get(newChainHeaders.size() - 1);
                        journalDrafts.add(LifecycleJournalDraft.reorgCommit(
                                journalGroupId, journalDrafts.size(), journalGroupSize,
                                newTip.getHeight(), newTip.getHash(), oldTip.getHash(),
                                journalOccurredAt, ConnectedSource.REORG.getCode()));
                    }
                    lifecycleJournalAppender.appendCanonicalToBatch(batch, journalDrafts);
                });
                entityIndexRepository.invalidateCaches();
            } catch (RuntimeException e) {
                log.error("{} DB write failed", opName, e);
                if (e.getCause() instanceof GEFailedException) {
                    throw (GEFailedException) e.getCause();
                }
                throw new GEFailedException(opName + " DB commit failed: " + e.getMessage(), e);
            }

            // 3. PUBLISH EVENTS
            StoredBlock newTip = newChainHeaders.get(newChainHeaders.size() - 1);
            if (isReorg || newChainHeaders.size() >= 50) {
                int totalTxCount = blockConnectedEvents.stream()
                        .mapToInt(e -> e.getBlock().getTxs().size()).sum();
                log.info("{} COMPLETE: {} blocks ({} txs) connected, new tip at height {} ({})",
                        opName, newChainHeaders.size(), totalTxCount,
                        newTip.getBlock().getHeight(),
                        newTip.getHash().toShortLogString());
            }

            eventsToPublish.addAll(blockDisconnectedEvents);
            eventsToPublish.addAll(blockConnectedEvents);

            if (!isReorg && !blockConnectedEvents.isEmpty()) {
                eventsToPublish.add(new BlockConnectionBatchCompletedEvent(
                        this, ConnectedSource.SYNC, blockConnectedEvents));
            }

            // Publish BlockReorgEvent for webhook notifications when it's a real reorg
            if (isReorg && !oldChainStored.isEmpty()) {
                StoredBlock oldTip = oldChainStored.get(0); // First in reversed list is the old tip
                eventsToPublish.add(new BlockReorgEvent(
                        this,
                        oldTip.getHeight(),
                        oldTip.getHash(),
                        newTip.getHeight(),
                        newTip.getHash()));
            }
        } finally {
            masterChainLock.unlock();
        }
        eventsToPublish.forEach(applicationEventPublisher::publishEvent);
    }

    private void stageCandidateBlocks(
            WriteBatch batch,
            StoredBlock branchParent,
            List<StoredBlock> blocks) throws Exception {
        Block previousBlock = branchParent.getBlock();
        WorldState worldState = worldStateFactory
                .createForValidation(previousBlock.getHeader().getStateRootHash());
        Map<Long, BlockHeader> branchHeaders = new HashMap<>();
        branchHeaders.put(branchParent.getHeight(), previousBlock.getHeader());

        for (StoredBlock candidate : blocks) {
            Block block = candidate.getBlock();
            NetworkParamsState params = worldState.getParams();
            BlockHeader difficultyAnchor = resolveDifficultyAnchor(
                    params.getAsertAnchorHeight(), branchParent, branchHeaders);
            blockValidationService.validateHeaderContext(
                    block.getHeader(), previousBlock.getHeader(), worldState, difficultyAnchor);

            ExecutionResult result = stateProcessor.executeTransactions(
                    worldState, new SimpleBlock(block), block.getTxs(), params);
            if (!worldState.calculateRootHash().equals(block.getHeader().getStateRootHash())) {
                throw new GEFailedException("SYNC staging failed: Invalid StateRoot for " + block.getHash());
            }

            Wei totalFees = result.getTotalFeesCollected();
            Wei actualRewardPaid = result.getMinerActualRewardPaid();
            List<BlockEvent> blockEvents = blockEventExtractor.extractEvents(
                    actualRewardPaid.subtract(totalFees),
                    totalFees,
                    block.getHeader().getCoinbase(),
                    result.getMinerRewardPoolAddress(),
                    result.getMinerRewardUnlockBlockHeight(),
                    worldState.getBipDiffs(),
                    worldState.getTokenDiffs(),
                    result.getActualBurnAmounts(),
                    worldState.getParamsDiff());

            StoredBlock staged = candidate.toBuilder().events(blockEvents).build();
            worldState.persistToBatch(batch);
            blockRepository.saveForkBlockDataToBatch(batch, staged);
            worldState.prepareForNextBlock();
            previousBlock = block;
            branchHeaders.put(block.getHeight(), block.getHeader());
        }
    }

    private BlockHeader resolveDifficultyAnchor(
            long anchorHeight,
            StoredBlock branchParent,
            Map<Long, BlockHeader> branchHeaders) {
        BlockHeader candidateAnchor = branchHeaders.get(anchorHeight);
        if (candidateAnchor != null) {
            return candidateAnchor;
        }
        if (anchorHeight < 0 || anchorHeight > branchParent.getHeight()) {
            throw new GEFailedException("Candidate branch is missing ASERT anchor at height " + anchorHeight);
        }

        if (chainQueryService.getCanonicalStoredBlockHeaderByHash(branchParent.getHash()).isPresent()) {
            BlockHeader canonicalAnchor = chainQueryService.getStoredBlockHeaderByHeight(anchorHeight)
                    .map(storedBlock -> storedBlock.getBlock().getHeader())
                    .orElse(null);
            if (canonicalAnchor == null) {
                // Let the validator's canonical resolver fail closed in production. Some
                // isolated consensus fixtures deliberately omit pre-ancestor history.
                return null;
            }
            branchHeaders.put(anchorHeight, canonicalAnchor);
            return canonicalAnchor;
        }

        BlockHeader cursor = branchParent.getBlock().getHeader();
        while (cursor.getHeight() > anchorHeight) {
            long missingHeight = cursor.getHeight() - 1;
            cursor = chainQueryService.getStoredBlockHeaderByHash(cursor.getPreviousHash())
                    .map(storedBlock -> storedBlock.getBlock().getHeader())
                    .orElseThrow(() -> new GEFailedException(
                            "Staged candidate branch is missing block " + missingHeight));
        }
        branchHeaders.put(anchorHeight, cursor);
        return cursor;
    }

    /**
     * Recomputes the candidate's cumulative work from canonical data while the
     * master-chain lock is held. Advertised peer difficulty and caller-provided
     * cumulative-difficulty metadata are never sufficient to authorize a swap.
     */
    private StoredBlock validateCandidateWork(
            StoredBlock requestedAncestor,
            List<StoredBlock> candidateBlocks,
            StoredBlock currentBestBlock) {
        if (candidateBlocks.isEmpty()) {
            throw new GEFailedException("Chain switch candidate must contain at least one block");
        }

        StoredBlock canonicalAncestor = chainQueryService
                .getCanonicalStoredBlockByHash(requestedAncestor.getHash())
                .orElseThrow(() -> new GEFailedException(
                        "Chain switch ancestor is no longer canonical: " + requestedAncestor.getHash()));

        BigInteger currentWork = requireCumulativeDifficulty(currentBestBlock, "current head");
        BigInteger candidateWork = validateCandidateMetadata(canonicalAncestor, candidateBlocks);

        if (candidateWork.compareTo(currentWork) <= 0) {
            throw new GEFailedException(
                    "Chain switch candidate does not have more cumulative difficulty than current head"
                            + " (candidate: " + candidateWork + ", current: " + currentWork + ")");
        }
        return canonicalAncestor;
    }

    private BigInteger validateCandidateMetadata(StoredBlock branchParent, List<StoredBlock> candidateBlocks) {
        if (candidateBlocks.isEmpty()) {
            throw new GEFailedException("Chain switch candidate must contain at least one block");
        }
        BigInteger candidateWork = requireCumulativeDifficulty(branchParent, "candidate branch parent");
        StoredBlock previous = branchParent;

        for (StoredBlock candidate : candidateBlocks) {
            Block block = candidate.getBlock();
            if (!block.getHeader().getPreviousHash().equals(previous.getHash())
                    || block.getHeight() != previous.getHeight() + 1) {
                throw new GEFailedException(
                        "Chain switch candidate is not contiguous at height " + block.getHeight());
            }

            BigInteger difficulty = block.getHeader().getDifficulty();
            if (difficulty == null || difficulty.signum() <= 0) {
                throw new GEFailedException(
                        "Chain switch candidate has invalid difficulty at height " + block.getHeight());
            }
            candidateWork = candidateWork.add(difficulty);
            if (!candidateWork.equals(candidate.getCumulativeDifficulty())) {
                throw new GEFailedException(
                        "Chain switch cumulative difficulty mismatch at height " + block.getHeight());
            }
            previous = candidate;
        }
        return candidateWork;
    }

    private BigInteger requireCumulativeDifficulty(StoredBlock block, String description) {
        BigInteger cumulativeDifficulty = block.getCumulativeDifficulty();
        if (cumulativeDifficulty == null || cumulativeDifficulty.signum() <= 0) {
            throw new GEFailedException("Invalid cumulative difficulty for " + description);
        }
        return cumulativeDifficulty;
    }

}
