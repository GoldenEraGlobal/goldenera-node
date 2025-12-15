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
package global.goldenera.node.explorer.services.indexer.business;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.Constants;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.ExecutionResult;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateDiff;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.datatypes.BalanceKey;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExIndexerEventReconstructionService {

        ChainQuery chainQuery;
        WorldStateFactory worldStateFactory;
        StateProcessor stateProcessor;

        public BlockConnectedEvent reconstructEvent(StoredBlock storedBlock) {
                Block block = storedBlock.getBlock();
                long height = block.getHeight();
                Address receivedFrom = storedBlock.getReceivedFrom();
                Instant receivedAt = storedBlock.getReceivedAt();
                ConnectedSource connectedSource = storedBlock.getConnectedSource();

                // --- GENESIS BLOCK: Special handling ---
                // Genesis has no transactions, state is set explicitly in GenesisInitializer.
                // We read the final state directly from the stored block's stateRoot.
                if (height == 0) {
                        return reconstructGenesisEvent(block, storedBlock, connectedSource, receivedFrom, receivedAt);
                }

                // --- NORMAL BLOCK RECONSTRUCTION ---
                Block prevBlock = chainQuery.getStoredBlockByHash(block.getHeader().getPreviousHash())
                                .map(sb -> sb.getBlock())
                                .orElseThrow(() -> new IllegalStateException(
                                                "Parent block not found for reconstruction: " + height));

                Hash startStateRoot = prevBlock.getHeader().getStateRootHash();

                block.getTxs().parallelStream().forEach(tx -> {
                        // Warmup cache
                        tx.getHash();
                        tx.getSize();
                        tx.getSender();
                });

                // 1. Create WorldState (Validation Mode = No Journal, Initial State Capture ON)
                WorldState worldState = worldStateFactory.createForValidation(startStateRoot);
                NetworkParamsState params = worldState.getParams();

                // 2. Execute Transactions (To get Diffs & Fees)
                ExecutionResult executionResult = stateProcessor.executeTransactions(worldState,
                                new StateProcessor.SimpleBlock(block),
                                block.getTxs(), params);

                // 3. Extract Data
                BigInteger cumulativeDifficulty = storedBlock.getCumulativeDifficulty();
                Wei totalFees = executionResult.getTotalFeesCollected();
                Wei actualRewardPaid = executionResult.getMinerActualRewardPaid();
                Map<Hash, Wei> actualBurnAmounts = executionResult.getActualBurnAmounts();
                List<BlockEvent> blockEvents = storedBlock.getEvents();

                return new BlockConnectedEvent(
                                this,
                                connectedSource,
                                block,

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

                                totalFees,
                                actualRewardPaid,
                                cumulativeDifficulty,
                                actualBurnAmounts,
                                blockEvents,
                                receivedFrom,
                                receivedAt);
        }

        /**
         * Reconstructs the BlockConnectedEvent for the genesis block.
         * 
         * Genesis block has no transactions - its state is set explicitly by
         * GenesisInitializer. To avoid duplicating the genesis state logic,
         * we read the ACTUAL state directly from the stored genesis block's
         * stateRoot. This ensures a single source of truth.
         * 
         * The diff is computed as: EMPTY_STATE -> GENESIS_STATE
         */
        private BlockConnectedEvent reconstructGenesisEvent(
                        Block block,
                        StoredBlock storedBlock,
                        ConnectedSource connectedSource,
                        Address receivedFrom,
                        Instant receivedAt) {

                log.info("Reconstructing genesis block event from stored state...");

                // Read the actual genesis state from the stored block's stateRoot
                Hash genesisStateRoot = block.getHeader().getStateRootHash();
                WorldState genesisState = worldStateFactory.createForValidation(genesisStateRoot);

                // 1. Network Params: ZERO -> actual genesis params
                NetworkParamsState genesisParams = genesisState.getParams();
                StateDiff<NetworkParamsState> paramsDiff = new WorldStateDiff<>(
                                NetworkParamsStateImpl.ZERO, genesisParams);

                // 2. Native Token: ZERO -> actual genesis token
                TokenState nativeToken = genesisState.getToken(Address.NATIVE_TOKEN);
                Map<Address, StateDiff<TokenState>> tokenDiffs = new LinkedHashMap<>();
                if (!TokenStateImpl.ZERO.equals(nativeToken)) {
                        tokenDiffs.put(Address.NATIVE_TOKEN, new WorldStateDiff<>(TokenStateImpl.ZERO, nativeToken));
                }

                // 3. Authorities: Read from Constants (addresses only) and get state from trie
                // This is the only place we still reference Constants, but only for the ADDRESS
                // list,
                // not the state structure. The actual AuthorityState comes from the stored
                // genesis.
                List<Address> authorityAddresses = Constants.getSettings().genesisAuthorityAddresses();
                Map<Address, AuthorityState> authoritiesToAdd = new LinkedHashMap<>();
                for (Address authority : authorityAddresses) {
                        AuthorityState authState = genesisState.getAuthority(authority);
                        if (!AuthorityStateImpl.ZERO.equals(authState)) {
                                authoritiesToAdd.put(authority, authState);
                        }
                }

                // 3b. Validators: Read from Constants (addresses only) and get state from trie
                List<Address> validatorAddresses = Constants.getSettings().genesisValidatorAddresses();
                Map<Address, ValidatorState> validatorsToAdd = new LinkedHashMap<>();
                for (Address validator : validatorAddresses) {
                        ValidatorState validatorState = genesisState.getValidator(validator);
                        if (!ValidatorStateImpl.ZERO.equals(validatorState)) {
                                validatorsToAdd.put(validator, validatorState);
                        }
                }

                // 4. Balance Diffs: Create diffs for genesis initial mints
                Map<BalanceKey, StateDiff<AccountBalanceState>> balanceDiffs = new LinkedHashMap<>();
                NetworkSettings settings = Constants.getSettings();

                // First authority balance
                Address firstAuthority = authorityAddresses.get(0);
                Wei authorityMint = settings.genesisNetworkInitialMintForAuthority();
                if (authorityMint.compareTo(Wei.ZERO) > 0) {
                        BalanceKey authorityKey = new BalanceKey(firstAuthority, Address.NATIVE_TOKEN);
                        AccountBalanceState authorityBalance = genesisState.getBalance(firstAuthority,
                                        Address.NATIVE_TOKEN);
                        balanceDiffs.put(authorityKey,
                                        new WorldStateDiff<>(AccountBalanceStateImpl.ZERO, authorityBalance));
                }

                // Block reward pool balance
                Address blockRewardPool = settings.genesisNetworkBlockRewardPoolAddress();
                Wei blockRewardMint = settings.genesisNetworkInitialMintForBlockReward();
                if (blockRewardMint.compareTo(Wei.ZERO) > 0) {
                        BalanceKey rewardPoolKey = new BalanceKey(blockRewardPool, Address.NATIVE_TOKEN);
                        AccountBalanceState rewardPoolBalance = genesisState.getBalance(blockRewardPool,
                                        Address.NATIVE_TOKEN);
                        balanceDiffs.put(rewardPoolKey,
                                        new WorldStateDiff<>(AccountBalanceStateImpl.ZERO, rewardPoolBalance));
                }

                // Genesis has no nonce changes, bip changes, or aliases
                Map<Address, StateDiff<AccountNonceState>> nonceDiffs = Collections.emptyMap();
                Map<Hash, StateDiff<BipState>> bipDiffs = Collections.emptyMap();

                return new BlockConnectedEvent(
                                this,
                                connectedSource,
                                block,

                                balanceDiffs,
                                nonceDiffs,
                                tokenDiffs,
                                bipDiffs,
                                paramsDiff,

                                authoritiesToAdd,
                                Collections.emptyMap(), // No authorities removed in genesis

                                validatorsToAdd,
                                Collections.emptyMap(), // No validators removed in genesis

                                Collections.emptyMap(), // No aliases added in genesis
                                Collections.emptyMap(), // No aliases removed in genesis

                                Wei.ZERO, // No fees in genesis
                                Wei.ZERO, // No miner reward in genesis
                                storedBlock.getCumulativeDifficulty(),
                                Collections.emptyMap(), // No burns in genesis
                                List.of(),
                                receivedFrom,
                                receivedAt);
        }
}
