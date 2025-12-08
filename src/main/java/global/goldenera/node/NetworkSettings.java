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
package global.goldenera.node;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.Constants.ForkName;

/**
 * Immutable configuration settings for a specific network (MAINNET, TESTNET,
 * etc.).
 * 
 * This record combines:
 * - Genesis settings (loaded from JSON, customizable for dev profile)
 * - Consensus-critical settings (hardcoded in Constants, same for all nodes)
 * 
 * Use the height-aware getter methods (e.g., getMaxBlockSizeInBytes(height))
 * for consensus-critical parameters that may change via forks.
 */
public record NetworkSettings(
        // =============================================
        // GENESIS SETTINGS (loaded from JSON)
        // =============================================

        // Size limits (base values, use getters with height for fork-aware values)
        long maxHeaderSizeInBytes,
        long maxTxSizeInBytes,
        long maxBlockSizeInBytes,
        long maxTxCountPerBlock,

        // BIP (Blockchain Improvement Proposal) configuration
        long bipExpirationPeriodMs,
        long bipApprovalThresholdBps,

        // Genesis network parameters
        Wei genesisNetworkBlockReward,
        Address genesisNetworkBlockRewardPoolAddress,
        long genesisNetworkTargetMiningTimeMs,
        long genesisNetworkAsertHalfLifeBlocks,
        BigInteger genesisNetworkMinDifficulty,
        Wei genesisNetworkMinTxBaseFee,
        Wei genesisNetworkMinTxByteFee,

        // Genesis authorities
        List<Address> genesisAuthorityAddresses,

        // Genesis block configuration
        long genesisBlockTimestamp,
        BigInteger genesisBlockDifficulty,

        // Native token configuration
        String genesisNativeTokenName,
        String genesisNativeTokenTicker,
        int genesisNativeTokenDecimals,
        String genesisNativeTokenWebsite,
        String genesisNativeTokenLogo,
        boolean genesisNativeTokenUserBurnable,

        // RandomX configuration
        long randomXEpochLength,
        String randomXGenesisKey,
        int randomXBatchSize,

        // =============================================
        // CONSENSUS-CRITICAL SETTINGS (from Constants)
        // =============================================

        // Fork activation blocks
        Map<ForkName, Long> forkActivationBlocks,

        // Block checkpoints for validation
        Map<Long, Hash> blockCheckpoints,

        // Fork-based parameter overrides (height -> new value)
        Map<Long, Long> maxBlockSizeOverrides,
        Map<Long, Long> maxTxSizeOverrides,
        Map<Long, Long> maxTxCountOverrides,
        Map<Long, Long> maxHeaderSizeOverrides) {

    // =============================================
    // HEIGHT-AWARE GETTERS (for fork-based changes)
    // =============================================

    /**
     * Get max block size for a specific block height.
     * Returns the most recent override that is active at this height,
     * or the base value if no override is active.
     */
    public long getMaxBlockSizeInBytes(long blockHeight) {
        return getValueAtHeight(maxBlockSizeInBytes, maxBlockSizeOverrides, blockHeight);
    }

    /**
     * Get max transaction size for a specific block height.
     */
    public long getMaxTxSizeInBytes(long blockHeight) {
        return getValueAtHeight(maxTxSizeInBytes, maxTxSizeOverrides, blockHeight);
    }

    /**
     * Get max transaction count per block for a specific block height.
     */
    public long getMaxTxCountPerBlock(long blockHeight) {
        return getValueAtHeight(maxTxCountPerBlock, maxTxCountOverrides, blockHeight);
    }

    /**
     * Get max header size for a specific block height.
     */
    public long getMaxHeaderSizeInBytes(long blockHeight) {
        return getValueAtHeight(maxHeaderSizeInBytes, maxHeaderSizeOverrides, blockHeight);
    }

    /**
     * Helper method to get the effective value at a given height.
     */
    private long getValueAtHeight(long baseValue, Map<Long, Long> overrides, long blockHeight) {
        if (overrides == null || overrides.isEmpty()) {
            return baseValue;
        }

        Long effectiveValue = null;
        long highestActivationHeight = -1;

        for (Map.Entry<Long, Long> entry : overrides.entrySet()) {
            long activationHeight = entry.getKey();
            if (activationHeight <= blockHeight && activationHeight > highestActivationHeight) {
                highestActivationHeight = activationHeight;
                effectiveValue = entry.getValue();
            }
        }

        return effectiveValue != null ? effectiveValue : baseValue;
    }

    // =============================================
    // FACTORY METHOD
    // =============================================

    /**
     * Create NetworkSettings by combining genesis settings from JSON
     * with consensus-critical settings from Constants.
     *
     * @param genesis
     *            Genesis settings loaded from JSON
     * @param network
     *            Network to get consensus settings for
     * @return Complete NetworkSettings
     */
    public static NetworkSettings fromGenesisSettings(GenesisSettings genesis, Network network) {
        ConsensusSettings consensus = Constants.getConsensusSettings(network);

        return new NetworkSettings(
                genesis.maxHeaderSizeInBytes(),
                genesis.maxTxSizeInBytes(),
                genesis.maxBlockSizeInBytes(),
                genesis.maxTxCountPerBlock(),
                genesis.bipExpirationPeriodMs(),
                genesis.bipApprovalThresholdBps(),
                genesis.genesisNetworkBlockReward(),
                genesis.genesisNetworkBlockRewardPoolAddress(),
                genesis.genesisNetworkTargetMiningTimeMs(),
                genesis.genesisNetworkAsertHalfLifeBlocks(),
                genesis.genesisNetworkMinDifficulty(),
                genesis.genesisNetworkMinTxBaseFee(),
                genesis.genesisNetworkMinTxByteFee(),
                genesis.genesisAuthorityAddresses(),
                genesis.genesisBlockTimestamp(),
                genesis.genesisBlockDifficulty(),
                genesis.genesisNativeTokenName(),
                genesis.genesisNativeTokenTicker(),
                genesis.genesisNativeTokenDecimals(),
                genesis.genesisNativeTokenWebsite(),
                genesis.genesisNativeTokenLogo(),
                genesis.genesisNativeTokenUserBurnable(),
                genesis.randomXEpochLength(),
                genesis.randomXGenesisKey(),
                genesis.randomXBatchSize(),
                consensus.forkActivationBlocks(),
                consensus.blockCheckpoints(),
                consensus.maxBlockSizeOverrides(),
                consensus.maxTxSizeOverrides(),
                consensus.maxTxCountOverrides(),
                consensus.maxHeaderSizeOverrides());
    }
}
