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

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Types of "invisible" state change events that occur in a block.
 * These events represent state changes that are NOT directly visible as
 * transactions,
 * but are triggered by BIP execution, block rewards, or consensus rules.
 * 
 * <p>
 * Wallet/Explorer use case: When querying a block, these events tell you
 * about balance changes, governance actions, and token operations that happened
 * without an explicit transaction from the affected address.
 * </p>
 */
@Getter
@AllArgsConstructor
public enum BlockEventType {

    // === BLOCK LEVEL EVENTS ===

    /**
     * Miner received block reward from the reward pool.
     * Affects: miner address (receives), reward pool (sends)
     */
    BLOCK_REWARD(0),

    /**
     * Miner collected transaction fees from this block.
     * Affects: miner address (receives from tx senders)
     */
    FEES_COLLECTED(1),

    // === TOKEN EVENTS (BIP execution results) ===

    /**
     * New token was created via approved BIP.
     * Affects: token address (created)
     */
    TOKEN_CREATED(10),

    /**
     * Token metadata was updated via approved BIP.
     * Affects: token address
     */
    TOKEN_UPDATED(11),

    /**
     * Tokens were minted to an address via approved BIP.
     * KEY EVENT FOR WALLETS: recipient receives tokens without sending a
     * transaction!
     * Affects: recipient address (receives tokens)
     */
    TOKEN_MINTED(12),

    /**
     * Tokens were burned via approved BIP.
     * Affects: owner address (loses tokens)
     */
    TOKEN_BURNED(13),

    /**
     * Token total supply was updated.
     * This captures supply changes from: minting, burning, block rewards, fee
     * inflation.
     * Applies to any token including native token.
     */
    TOKEN_SUPPLY_UPDATED(14),

    // === GOVERNANCE EVENTS (BIP execution results) ===

    /**
     * New authority was added to the governance system via approved BIP.
     * Affects: authority address
     */
    AUTHORITY_ADDED(20),

    /**
     * Authority was removed from the governance system via approved BIP.
     * Affects: authority address
     */
    AUTHORITY_REMOVED(21),

    /**
     * Network parameters were changed via approved BIP.
     * Global event affecting all network participants.
     */
    NETWORK_PARAMS_CHANGED(22),

    // === ADDRESS ALIAS EVENTS (BIP execution results) ===

    /**
     * Human-readable alias was assigned to an address via approved BIP.
     * Affects: aliased address
     */
    ADDRESS_ALIAS_ADDED(30),

    /**
     * Alias was removed from an address via approved BIP.
     * Affects: previously aliased address
     */
    ADDRESS_ALIAS_REMOVED(31),

    /**
     * BIP state was changed via vote.
     * Affects: bip status
     */
    BIP_STATE_CHANGE(40);

    private final int code;

    public static BlockEventType fromCode(int code) {
        for (BlockEventType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown BlockEventType code: " + code);
    }
}
