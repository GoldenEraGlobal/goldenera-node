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
package global.goldenera.node.core.storage.blockchain.domain;

import java.time.Instant;
import java.util.LinkedHashSet;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.node.core.enums.BlockEventType;

/**
 * Represents an "invisible" state change event that occurred in a block.
 * These events capture state changes that are NOT explicit transactions,
 * such as BIP execution effects, block rewards, and governance actions.
 * 
 * <p>
 * Use cases for wallets/explorers:
 * <ul>
 * <li>TOKEN_MINTED: Wallet can show "Received 1000 USDT via BIP approval"</li>
 * <li>BLOCK_REWARD: Explorer can show miner reward without explicit tx</li>
 * <li>AUTHORITY_ADDED: Governance dashboard can track authority changes</li>
 * </ul>
 * </p>
 */
public sealed interface BlockEvent {

    /**
     * @return The type of this event
     */
    BlockEventType type();

    /**
     * @return The BIP hash that caused this event, or null for non-BIP events (e.g.
     *         block reward)
     */
    Hash bipHash();

    // ============================
    // BLOCK LEVEL EVENTS
    // ============================

    /**
     * Miner received block reward from reward pool.
     * 
     * @param minerAddress
     *            The miner who received the reward
     * @param rewardPoolAddress
     *            The reward pool address
     * @param amount
     *            The reward amount
     */
    record BlockReward(
            Address minerAddress,
            Address rewardPoolAddress,
            Wei amount) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.BLOCK_REWARD;
        }

        @Override
        public Hash bipHash() {
            return null;
        }

    }

    /**
     * Miner collected transaction fees from this block.
     * 
     * @param minerAddress
     *            The miner who collected the fees
     * @param amount
     *            The total fees collected
     */
    record FeesCollected(
            Address minerAddress,
            Wei amount) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.FEES_COLLECTED;
        }

        @Override
        public Hash bipHash() {
            return null;
        }
    }

    // ============================
    // TOKEN EVENTS
    // ============================

    /**
     * New token was created via approved BIP.
     */
    record TokenCreated(
            Hash bipHash,
            Address derivedTokenAddress,
            TxVersion txVersion,
            TxBipTokenCreatePayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.TOKEN_CREATED;
        }
    }

    /**
     * Token metadata was updated via approved BIP.
     */
    record TokenUpdated(
            Hash bipHash,
            TxVersion txVersion,
            TxBipTokenUpdatePayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.TOKEN_UPDATED;
        }
    }

    /**
     * Tokens were minted to an address via approved BIP.
     * KEY EVENT: recipient receives tokens without sending a transaction!
     */
    record TokenMinted(
            Hash bipHash,
            TxVersion txVersion,
            TxBipTokenMintPayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.TOKEN_MINTED;
        }
    }

    /**
     * Tokens were burned via approved BIP.
     */
    record TokenBurned(
            Hash bipHash,
            TxVersion txVersion,
            TxBipTokenBurnPayload payload,
            Wei actualBurnedAmount) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.TOKEN_BURNED;
        }
    }

    /**
     * Token total supply was updated in this block.
     * Captures supply changes from: minting, burning, block rewards, fee inflation.
     * Applies to any token including native token.
     * 
     * @param tokenAddress
     *            The token whose supply changed
     * @param newTotalSupply
     *            The new total supply after this block
     */
    record TokenSupplyUpdated(
            Address tokenAddress,
            Wei newTotalSupply) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.TOKEN_SUPPLY_UPDATED;
        }

        @Override
        public Hash bipHash() {
            return null;
        }
    }

    // ============================
    // GOVERNANCE EVENTS
    // ============================

    /**
     * New authority was added to the governance system via approved BIP.
     */
    record AuthorityAdded(
            Hash bipHash,
            TxVersion txVersion,
            TxBipAuthorityAddPayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.AUTHORITY_ADDED;
        }
    }

    /**
     * Authority was removed from governance system via approved BIP.
     */
    record AuthorityRemoved(
            Hash bipHash,
            TxVersion txVersion,
            TxBipAuthorityRemovePayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.AUTHORITY_REMOVED;
        }
    }

    /**
     * Network parameters were changed via approved BIP.
     * Only non-null fields were changed.
     */
    record NetworkParamsChanged(
            Hash bipHash,
            TxVersion txVersion,
            TxBipNetworkParamsSetPayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.NETWORK_PARAMS_CHANGED;
        }
    }

    // ============================
    // ADDRESS ALIAS EVENTS
    // ============================

    /**
     * Human-readable alias was assigned to an address via approved BIP.
     */
    record AddressAliasAdded(
            Hash bipHash,
            TxVersion txVersion,
            TxBipAddressAliasAddPayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.ADDRESS_ALIAS_ADDED;
        }
    }

    /**
     * Alias was removed from an address via approved BIP.
     */
    record AddressAliasRemoved(
            Hash bipHash,
            TxVersion txVersion,
            TxBipAddressAliasRemovePayload payload) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.ADDRESS_ALIAS_REMOVED;
        }
    }

    /**
     * New BIP was created/submitted.
     */
    record BipStateCreated(
            Hash bipHash,
            BipStatus status,
            boolean isActionExecuted,
            LinkedHashSet<Address> approvers,
            LinkedHashSet<Address> disapprovers,
            Hash updatedByTxHash,
            long updatedAtBlockHeight,
            Instant updatedAtTimestamp) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.BIP_STATE_CREATED;
        }
    }

    /**
     * BIP state was updated via vote or time-based status change.
     */
    record BipStateUpdated(
            Hash bipHash,
            BipStatus status,
            boolean isActionExecuted,
            LinkedHashSet<Address> approvers,
            LinkedHashSet<Address> disapprovers,
            Hash updatedByTxHash,
            long updatedAtBlockHeight,
            Instant updatedAtTimestamp) implements BlockEvent {

        @Override
        public BlockEventType type() {
            return BlockEventType.BIP_STATE_UPDATED;
        }
    }
}
