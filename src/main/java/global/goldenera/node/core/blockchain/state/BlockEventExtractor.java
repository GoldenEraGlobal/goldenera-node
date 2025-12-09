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
package global.goldenera.node.core.blockchain.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AddressAliasAdded;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AddressAliasRemoved;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AuthorityAdded;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AuthorityRemoved;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BipStateCreated;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BipStateUpdated;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BlockReward;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.FeesCollected;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.NetworkParamsChanged;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenBurned;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenCreated;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenMinted;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenSupplyUpdated;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenUpdated;

/**
 * Extracts BlockEvents from WorldState diffs.
 * These events represent "invisible" state changes that are NOT explicit
 * transactions,
 * such as BIP execution effects and block rewards.
 */
@Service
public class BlockEventExtractor {

        /**
         * Extracts all block events from the world state diffs.
         *
         * @param blockRewardAmount
         *                the actual block reward paid to miner (from pool or minted)
         * @param feesAmount
         *                the total transaction fees collected by miner
         * @param minerAddress
         *                the miner address
         * @param rewardPoolAddress
         *                the reward pool address
         * @param bipDiffs
         *                map of BIP hashes to their state diffs
         * @param tokenDiffs
         *                map of token addresses to their state diffs
         * @param actualBurnAmounts
         *                the actual burn amounts for BIP burns
         * @return list of extracted events
         */
        public List<BlockEvent> extractEvents(
                        Wei blockRewardAmount,
                        Wei feesAmount,
                        Address minerAddress,
                        Address rewardPoolAddress,
                        Map<Hash, StateDiff<BipState>> bipDiffs,
                        Map<Address, StateDiff<TokenState>> tokenDiffs,
                        Map<Hash, Wei> actualBurnAmounts) {

                List<BlockEvent> events = new ArrayList<>();

                // 1. Block reward event (from pool or minted - excludes fees)
                if (blockRewardAmount != null && blockRewardAmount.compareTo(Wei.ZERO) > 0) {
                        events.add(new BlockReward(minerAddress, rewardPoolAddress, blockRewardAmount));
                }

                // 2. Fees collected event (tx fees paid by users)
                if (feesAmount != null && feesAmount.compareTo(Wei.ZERO) > 0) {
                        events.add(new FeesCollected(minerAddress, feesAmount));
                }

                // 3. Token supply updated events
                if (tokenDiffs != null) {
                        tokenDiffs.forEach((tokenAddress, diff) -> {
                                TokenState oldState = diff.getOldValue();
                                TokenState newState = diff.getNewValue();
                                if (oldState.getTotalSupply().compareTo(newState.getTotalSupply()) != 0) {
                                        Wei newTotalSupply = newState.getTotalSupply();
                                        events.add(new TokenSupplyUpdated(tokenAddress, newTotalSupply));
                                }
                        });
                }

                // 4. BIP state events
                if (bipDiffs != null && !bipDiffs.isEmpty()) {
                        bipDiffs.forEach((bipHash, diff) -> {
                                BipState oldState = diff.getOldValue();
                                BipState newState = diff.getNewValue();

                                // Detect if this is a new BIP creation:
                                // A fresh/new BIP has updatedAtBlockHeight < 0 in oldState
                                boolean isNewBip = !oldState.exists();

                                if (isNewBip) {
                                        events.add(new BipStateCreated(
                                                        bipHash,
                                                        newState.getStatus(),
                                                        newState.isActionExecuted(),
                                                        newState.getApprovers(),
                                                        newState.getDisapprovers(),
                                                        newState.getUpdatedByTxHash(),
                                                        newState.getUpdatedAtBlockHeight(),
                                                        newState.getUpdatedAtTimestamp()));
                                } else {
                                        // BIP state was updated - emit event on every update
                                        events.add(new BipStateUpdated(
                                                        bipHash,
                                                        newState.getStatus(),
                                                        newState.isActionExecuted(),
                                                        newState.getApprovers(),
                                                        newState.getDisapprovers(),
                                                        newState.getUpdatedByTxHash(),
                                                        newState.getUpdatedAtBlockHeight(),
                                                        newState.getUpdatedAtTimestamp()));
                                }

                                // Extract BIP action execution event if action was just executed in this block
                                if (!oldState.isActionExecuted() && newState.isActionExecuted()) {
                                        TxPayload payload = newState.getMetadata().getTxPayload();
                                        BlockEvent event = createBipExecutionEvent(bipHash, payload,
                                                        newState.getMetadata().getTxVersion(),
                                                        newState.getMetadata().getDerivedTokenAddress(),
                                                        actualBurnAmounts);
                                        if (event != null) {
                                                events.add(event);
                                        }
                                }
                        });
                }

                return events;
        }

        private BlockEvent createBipExecutionEvent(Hash bipHash, TxPayload payload, TxVersion txVersion,
                        Address derivedTokenAddress, Map<Hash, Wei> actualBurnAmounts) {
                if (payload == null) {
                        return null;
                }

                return switch (payload) {
                        case TxBipTokenCreatePayload p -> new TokenCreated(
                                        bipHash,
                                        derivedTokenAddress,
                                        txVersion,
                                        p);

                        case TxBipTokenUpdatePayload p -> new TokenUpdated(
                                        bipHash,
                                        txVersion,
                                        p);

                        case TxBipTokenMintPayload p -> new TokenMinted(
                                        bipHash,
                                        txVersion,
                                        p);

                        case TxBipTokenBurnPayload p -> {
                                Wei actualBurned = actualBurnAmounts != null ? actualBurnAmounts.get(bipHash) : null;
                                yield new TokenBurned(
                                                bipHash,
                                                txVersion,
                                                p,
                                                actualBurned != null ? actualBurned : Wei.ZERO);
                        }

                        case TxBipAuthorityAddPayload p -> new AuthorityAdded(
                                        bipHash,
                                        txVersion,
                                        p);

                        case TxBipAuthorityRemovePayload p -> new AuthorityRemoved(
                                        bipHash,
                                        txVersion,
                                        p);

                        case TxBipNetworkParamsSetPayload p -> new NetworkParamsChanged(
                                        bipHash,
                                        txVersion,
                                        p);

                        case TxBipAddressAliasAddPayload p -> new AddressAliasAdded(
                                        bipHash,
                                        txVersion,
                                        p);

                        case TxBipAddressAliasRemovePayload p -> new AddressAliasRemoved(
                                        bipHash,
                                        txVersion,
                                        p);

                        default -> null;
                };
        }
}
