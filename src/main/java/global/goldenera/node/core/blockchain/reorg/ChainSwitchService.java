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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockReorgEvent;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
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
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ChainSwitchService {

    /**
     * Distinguishes between sync (catching up to chain tip) and reorg (switching to
     * a better fork).
     */
    public enum SwitchType {
        SYNC, // Catching up to the chain tip, no blocks being disconnected
        REORG // Switching to a different fork, blocks being disconnected
    }

    ChainQuery chainQueryService;
    BlockRepository blockRepository;
    WorldStateFactory worldStateFactory;
    StateProcessor stateProcessor;
    BlockValidator blockValidationService;
    ApplicationEventPublisher applicationEventPublisher;
    ReentrantLock masterChainLock;
    EntityIndexRepository entityIndexRepository;
    BlockEventExtractor blockEventExtractor;

    public ChainSwitchService(
            ChainQuery chainQueryService,
            BlockRepository blockRepository,
            WorldStateFactory worldStateFactory,
            StateProcessor stateProcessor,
            BlockValidator blockValidationService,
            ApplicationEventPublisher applicationEventPublisher,
            @Qualifier("masterChainLock") ReentrantLock masterChainLock,
            EntityIndexRepository entityIndexRepository,
            BlockEventExtractor blockEventExtractor) {
        this.chainQueryService = chainQueryService;
        this.blockRepository = blockRepository;
        this.worldStateFactory = worldStateFactory;
        this.stateProcessor = stateProcessor;
        this.blockValidationService = blockValidationService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.masterChainLock = masterChainLock;
        this.entityIndexRepository = entityIndexRepository;
        this.blockEventExtractor = blockEventExtractor;
    }

    public void executeAtomicReorgSwap(
            @NonNull StoredBlock commonAncestor,
            @NonNull List<StoredBlock> newChainHeaders,
            boolean saveTipData,
            @NonNull SwitchType switchType) throws Exception {

        masterChainLock.lock();
        try {
            StoredBlock currentBestBlock = chainQueryService.getLatestStoredBlockOrThrow();
            // findChainFrom returns List<StoredBlock> - use StoredBlock.getHash() for
            // comparison
            List<StoredBlock> oldChainStored = chainQueryService.findChainFrom(
                    commonAncestor.getHash(), currentBestBlock.getHash());
            Collections.reverse(oldChainStored);

            List<BlockDisconnectedEvent> blockDisconnectedEvents = new ArrayList<>();
            List<BlockConnectedEvent> blockConnectedEvents = new ArrayList<>();

            String opName = switchType == SwitchType.REORG ? "REORG" : "SYNC";
            boolean isReorg = switchType == SwitchType.REORG;

            if (isReorg || oldChainStored.size() > 0) {
                log.info("{} STARTING: disconnecting {} blocks, connecting {} blocks (common ancestor at height {})",
                        opName, oldChainStored.size(), newChainHeaders.size(), commonAncestor.getHeight());
            } else {
                log.info("SYNC: connecting {} blocks from height {}",
                        newChainHeaders.size(), commonAncestor.getHeight() + 1);
            }

            try {
                blockRepository.getRepository().executeAtomicBatch(batch -> {
                    for (StoredBlock storedBlockToDisconnect : oldChainStored) {
                        Block blockToDisconnect = storedBlockToDisconnect.getBlock();

                        StoredBlock parent = chainQueryService
                                .getStoredBlockByHash(blockToDisconnect.getHeader().getPreviousHash())
                                .orElseThrow(() -> new GEFailedException("Reorg parent not found"));

                        // Use StoredBlock.getHash() for pre-computed hash
                        blockRepository.addDisconnectBlockIndexToBatch(batch, storedBlockToDisconnect, parent);

                        entityIndexRepository.revertEntities(batch, blockToDisconnect);
                        blockDisconnectedEvents.add(new BlockDisconnectedEvent(this, blockToDisconnect));
                    }

                    Block previousBlock = commonAncestor.getBlock();

                    WorldState worldState = worldStateFactory
                            .createForValidation(previousBlock.getHeader().getStateRootHash());

                    long batchStart = System.currentTimeMillis();
                    int progressInterval = Math.max(50, newChainHeaders.size() / 10); // Log every 10% or 50 blocks

                    for (int i = 0; i < newChainHeaders.size(); i++) {
                        StoredBlock storedBlockToConnect = newChainHeaders.get(i);
                        Block blockToConnect = storedBlockToConnect.getBlock();
                        NetworkParamsState params = worldState.getParams();

                        try {
                            blockValidationService.validateHeaderContext(
                                    blockToConnect.getHeader(), previousBlock.getHeader(), worldState);
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
                                params.getBlockRewardPoolAddress(),
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
                                ConnectedSource.REORG,
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
                                Instant.now());

                        blockConnectedEvents.add(event);
                        worldState.prepareForNextBlock();
                        previousBlock = blockToConnect;

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
                        newTip.getBlock().getHash().toShortLogString());
            }

            blockDisconnectedEvents.forEach(applicationEventPublisher::publishEvent);
            blockConnectedEvents.forEach(applicationEventPublisher::publishEvent);

            // Publish BlockReorgEvent for webhook notifications when it's a real reorg
            if (isReorg && !oldChainStored.isEmpty()) {
                StoredBlock oldTip = oldChainStored.get(0); // First in reversed list is the old tip
                applicationEventPublisher.publishEvent(new BlockReorgEvent(
                        this,
                        oldTip.getHeight(),
                        oldTip.getHash(),
                        newTip.getHeight(),
                        newTip.getHash()));
            }
        } finally {
            masterChainLock.unlock();
        }
    }
}
