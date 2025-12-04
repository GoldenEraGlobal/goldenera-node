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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
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

    public ChainSwitchService(
            ChainQuery chainQueryService,
            BlockRepository blockRepository,
            WorldStateFactory worldStateFactory,
            StateProcessor stateProcessor,
            BlockValidator blockValidationService,
            ApplicationEventPublisher applicationEventPublisher,
            @Qualifier("masterChainLock") ReentrantLock masterChainLock,
            EntityIndexRepository entityIndexRepository) {
        this.chainQueryService = chainQueryService;
        this.blockRepository = blockRepository;
        this.worldStateFactory = worldStateFactory;
        this.stateProcessor = stateProcessor;
        this.blockValidationService = blockValidationService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.masterChainLock = masterChainLock;
        this.entityIndexRepository = entityIndexRepository;
    }

    public void executeAtomicReorgSwap(
            @NonNull StoredBlock commonAncestor,
            @NonNull List<StoredBlock> newChainHeaders,
            boolean saveTipData) throws Exception {

        masterChainLock.lock();
        try {
            StoredBlock currentBestBlock = chainQueryService.getLatestStoredBlockOrThrow();
            List<Block> oldChain = chainQueryService.findChainFrom(
                    commonAncestor.getBlock().getHash(), currentBestBlock.getBlock().getHash());
            Collections.reverse(oldChain);

            List<BlockDisconnectedEvent> blockDisconnectedEvents = new ArrayList<>();
            List<BlockConnectedEvent> blockConnectedEvents = new ArrayList<>();

            log.info("REORG STARTING: disconnecting {} blocks, connecting {} blocks (common ancestor at height {})",
                    oldChain.size(), newChainHeaders.size(), commonAncestor.getBlock().getHeight());

            try {
                blockRepository.getRepository().executeAtomicBatch(batch -> {
                    for (Block blockToDisconnect : oldChain) {
                        StoredBlock storedBlockToDisconnect = chainQueryService
                                .getStoredBlockByHash(blockToDisconnect.getHash())
                                .orElse(null);

                        StoredBlock parent = chainQueryService
                                .getStoredBlockByHash(blockToDisconnect.getHeader().getPreviousHash())
                                .orElseThrow(() -> new GEFailedException("Reorg parent not found"));

                        if (storedBlockToDisconnect == null) {
                            log.warn(
                                    "Corruption detected! Block {} to disconnect not found in DB. Forcing index removal.",
                                    blockToDisconnect.getHash());
                            blockRepository.forceDisconnectBlockIndex(batch, blockToDisconnect.getHeight(),
                                    parent.getHash());
                        } else {
                            blockRepository.addDisconnectBlockIndexToBatch(batch, storedBlockToDisconnect, parent);
                        }

                        entityIndexRepository.revertEntities(batch, blockToDisconnect);
                        blockDisconnectedEvents.add(new BlockDisconnectedEvent(this, blockToDisconnect));
                    }

                    Block previousBlock = commonAncestor.getBlock();

                    WorldState worldState = worldStateFactory
                            .createForValidation(previousBlock.getHeader().getStateRootHash());

                    for (int i = 0; i < newChainHeaders.size(); i++) {
                        StoredBlock storedBlockToConnect = newChainHeaders.get(i);
                        Block blockToConnect = storedBlockToConnect.getBlock();
                        NetworkParamsState params = worldState.getParams();

                        try {
                            blockValidationService.validateHeaderContext(
                                    blockToConnect.getHeader(), previousBlock.getHeader(), params);
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

                        worldState.persistToBatch(batch);
                        entityIndexRepository.saveEntities(batch, blockToConnect, worldState);

                        if (saveTipData && i == newChainHeaders.size() - 1) {
                            blockRepository.addBlockToBatch(batch, storedBlockToConnect);
                        } else {
                            blockRepository.connectBlockIndexToBatch(batch, storedBlockToConnect);
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
                                worldState.getDirtyAddressAliases(),
                                worldState.getAliasesRemovedWithState(),
                                result.getMinerTotalFees(),
                                result.getMinerActualRewardPaid(),
                                storedBlockToConnect.getCumulativeDifficulty(),
                                result.getActualBurnAmounts(),
                                null,
                                Instant.now());

                        blockConnectedEvents.add(event);
                        worldState.prepareForNextBlock();
                        previousBlock = blockToConnect;
                    }
                });
                entityIndexRepository.invalidateCaches();
            } catch (RuntimeException e) {
                log.error("Reorg DB write failed", e);
                if (e.getCause() instanceof GEFailedException) {
                    throw (GEFailedException) e.getCause();
                }
                throw new GEFailedException("Reorg DB commit failed: " + e.getMessage(), e);
            }

            // 3. PUBLISH EVENTS
            StoredBlock newTip = newChainHeaders.get(newChainHeaders.size() - 1);
            log.info("REORG COMPLETE: new tip at height {} with hash {}",
                    newTip.getBlock().getHeight(), newTip.getBlock().getHash().toShortLogString());

            blockDisconnectedEvents.forEach(applicationEventPublisher::publishEvent);
            blockConnectedEvents.forEach(applicationEventPublisher::publishEvent);
        } finally {
            masterChainLock.unlock();
        }
    }
}
