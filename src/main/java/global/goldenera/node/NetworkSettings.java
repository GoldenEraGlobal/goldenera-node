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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.Constants.ForkName;

/**
 * Immutable configuration settings for a specific network (MAINNET, TESTNET,
 * etc.).
 * All network-specific parameters are defined here.
 * 
 * Supports fork-based parameter overrides - parameters can change at specific
 * block heights.
 * Use the height-aware getter methods (e.g., getMaxBlockSizeInBytes(height))
 * for consensus-critical
 * parameters that may change via forks.
 */
public record NetworkSettings(
        // Directory configuration
        String directoryHost,
        Address directoryIdentityAddress,

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

        // Fork activation blocks
        Map<ForkName, Long> forkActivationBlocks,

        // Block checkpoints for validation
        Map<Long, Hash> blockCheckpoints,

        // RandomX configuration
        long randomXEpochLength,
        String randomXGenesisKey,
        int randomXBatchSize,

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
     * 
     * For genesis (height 0), returns the base value unless there's an override at
     * height 0.
     * For other heights, finds the most recent override that activates at or before
     * the given height.
     * 
     * @param baseValue
     *            The default value (used for genesis and before any overrides)
     * @param overrides
     *            Map of activation_height -> new_value
     * @param blockHeight
     *            The block height to get the value for
     * @return The effective value at the given height
     */
    private long getValueAtHeight(long baseValue, Map<Long, Long> overrides, long blockHeight) {
        if (overrides == null || overrides.isEmpty()) {
            return baseValue;
        }

        // Find the highest activation height that is <= blockHeight
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
    // BUILDER
    // =============================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String directoryHost;
        private Address directoryIdentityAddress;
        private long maxHeaderSizeInBytes;
        private long maxTxSizeInBytes;
        private long maxBlockSizeInBytes;
        private long maxTxCountPerBlock;
        private long bipExpirationPeriodMs;
        private long bipApprovalThresholdBps;
        private Wei genesisNetworkBlockReward;
        private Address genesisNetworkBlockRewardPoolAddress;
        private long genesisNetworkTargetMiningTimeMs;
        private long genesisNetworkAsertHalfLifeBlocks;
        private BigInteger genesisNetworkMinDifficulty;
        private Wei genesisNetworkMinTxBaseFee;
        private Wei genesisNetworkMinTxByteFee;
        private List<Address> genesisAuthorityAddresses;
        private long genesisBlockTimestamp;
        private BigInteger genesisBlockDifficulty;
        private String genesisNativeTokenName;
        private String genesisNativeTokenTicker;
        private int genesisNativeTokenDecimals;
        private String genesisNativeTokenWebsite;
        private String genesisNativeTokenLogo;
        private boolean genesisNativeTokenUserBurnable;
        private Map<ForkName, Long> forkActivationBlocks;
        private Map<Long, Hash> blockCheckpoints;
        private long randomXEpochLength;
        private String randomXGenesisKey;
        private int randomXBatchSize;

        // Override maps
        private Map<Long, Long> maxBlockSizeOverrides = new TreeMap<>();
        private Map<Long, Long> maxTxSizeOverrides = new TreeMap<>();
        private Map<Long, Long> maxTxCountOverrides = new TreeMap<>();
        private Map<Long, Long> maxHeaderSizeOverrides = new TreeMap<>();

        public Builder directoryHost(String directoryHost) {
            this.directoryHost = directoryHost;
            return this;
        }

        public Builder directoryIdentityAddress(Address directoryIdentityAddress) {
            this.directoryIdentityAddress = directoryIdentityAddress;
            return this;
        }

        public Builder maxHeaderSizeInBytes(long maxHeaderSizeInBytes) {
            this.maxHeaderSizeInBytes = maxHeaderSizeInBytes;
            return this;
        }

        public Builder maxTxSizeInBytes(long maxTxSizeInBytes) {
            this.maxTxSizeInBytes = maxTxSizeInBytes;
            return this;
        }

        public Builder maxBlockSizeInBytes(long maxBlockSizeInBytes) {
            this.maxBlockSizeInBytes = maxBlockSizeInBytes;
            return this;
        }

        public Builder maxTxCountPerBlock(long maxTxCountPerBlock) {
            this.maxTxCountPerBlock = maxTxCountPerBlock;
            return this;
        }

        public Builder bipExpirationPeriodMs(long bipExpirationPeriodMs) {
            this.bipExpirationPeriodMs = bipExpirationPeriodMs;
            return this;
        }

        public Builder bipApprovalThresholdBps(long bipApprovalThresholdBps) {
            this.bipApprovalThresholdBps = bipApprovalThresholdBps;
            return this;
        }

        public Builder genesisNetworkBlockReward(Wei genesisNetworkBlockReward) {
            this.genesisNetworkBlockReward = genesisNetworkBlockReward;
            return this;
        }

        public Builder genesisNetworkBlockRewardPoolAddress(Address genesisNetworkBlockRewardPoolAddress) {
            this.genesisNetworkBlockRewardPoolAddress = genesisNetworkBlockRewardPoolAddress;
            return this;
        }

        public Builder genesisNetworkTargetMiningTimeMs(long genesisNetworkTargetMiningTimeMs) {
            this.genesisNetworkTargetMiningTimeMs = genesisNetworkTargetMiningTimeMs;
            return this;
        }

        public Builder genesisNetworkAsertHalfLifeBlocks(long genesisNetworkAsertHalfLifeBlocks) {
            this.genesisNetworkAsertHalfLifeBlocks = genesisNetworkAsertHalfLifeBlocks;
            return this;
        }

        public Builder genesisNetworkMinDifficulty(BigInteger genesisNetworkMinDifficulty) {
            this.genesisNetworkMinDifficulty = genesisNetworkMinDifficulty;
            return this;
        }

        public Builder genesisNetworkMinTxBaseFee(Wei genesisNetworkMinTxBaseFee) {
            this.genesisNetworkMinTxBaseFee = genesisNetworkMinTxBaseFee;
            return this;
        }

        public Builder genesisNetworkMinTxByteFee(Wei genesisNetworkMinTxByteFee) {
            this.genesisNetworkMinTxByteFee = genesisNetworkMinTxByteFee;
            return this;
        }

        public Builder genesisAuthorityAddresses(List<Address> genesisAuthorityAddresses) {
            this.genesisAuthorityAddresses = genesisAuthorityAddresses;
            return this;
        }

        public Builder genesisBlockTimestamp(long genesisBlockTimestamp) {
            this.genesisBlockTimestamp = genesisBlockTimestamp;
            return this;
        }

        public Builder genesisBlockDifficulty(BigInteger genesisBlockDifficulty) {
            this.genesisBlockDifficulty = genesisBlockDifficulty;
            return this;
        }

        public Builder genesisNativeTokenName(String genesisNativeTokenName) {
            this.genesisNativeTokenName = genesisNativeTokenName;
            return this;
        }

        public Builder genesisNativeTokenTicker(String genesisNativeTokenTicker) {
            this.genesisNativeTokenTicker = genesisNativeTokenTicker;
            return this;
        }

        public Builder genesisNativeTokenDecimals(int genesisNativeTokenDecimals) {
            this.genesisNativeTokenDecimals = genesisNativeTokenDecimals;
            return this;
        }

        public Builder genesisNativeTokenWebsite(String genesisNativeTokenWebsite) {
            this.genesisNativeTokenWebsite = genesisNativeTokenWebsite;
            return this;
        }

        public Builder genesisNativeTokenLogo(String genesisNativeTokenLogo) {
            this.genesisNativeTokenLogo = genesisNativeTokenLogo;
            return this;
        }

        public Builder genesisNativeTokenUserBurnable(boolean genesisNativeTokenUserBurnable) {
            this.genesisNativeTokenUserBurnable = genesisNativeTokenUserBurnable;
            return this;
        }

        public Builder forkActivationBlocks(Map<ForkName, Long> forkActivationBlocks) {
            this.forkActivationBlocks = forkActivationBlocks;
            return this;
        }

        public Builder blockCheckpoints(Map<Long, Hash> blockCheckpoints) {
            this.blockCheckpoints = blockCheckpoints;
            return this;
        }

        public Builder randomXEpochLength(long randomXEpochLength) {
            this.randomXEpochLength = randomXEpochLength;
            return this;
        }

        public Builder randomXGenesisKey(String randomXGenesisKey) {
            this.randomXGenesisKey = randomXGenesisKey;
            return this;
        }

        public Builder randomXBatchSize(int randomXBatchSize) {
            this.randomXBatchSize = randomXBatchSize;
            return this;
        }

        // =============================================
        // OVERRIDE BUILDERS
        // =============================================

        /**
         * Add a max block size override at a specific block height.
         * 
         * @param activationHeight
         *            Block height when this override activates
         * @param newMaxBlockSize
         *            New max block size in bytes
         */
        public Builder maxBlockSizeOverride(long activationHeight, long newMaxBlockSize) {
            this.maxBlockSizeOverrides.put(activationHeight, newMaxBlockSize);
            return this;
        }

        /**
         * Add a max transaction size override at a specific block height.
         */
        public Builder maxTxSizeOverride(long activationHeight, long newMaxTxSize) {
            this.maxTxSizeOverrides.put(activationHeight, newMaxTxSize);
            return this;
        }

        /**
         * Add a max transaction count override at a specific block height.
         */
        public Builder maxTxCountOverride(long activationHeight, long newMaxTxCount) {
            this.maxTxCountOverrides.put(activationHeight, newMaxTxCount);
            return this;
        }

        /**
         * Add a max header size override at a specific block height.
         */
        public Builder maxHeaderSizeOverride(long activationHeight, long newMaxHeaderSize) {
            this.maxHeaderSizeOverrides.put(activationHeight, newMaxHeaderSize);
            return this;
        }

        public NetworkSettings build() {
            return new NetworkSettings(
                    directoryHost,
                    directoryIdentityAddress,
                    maxHeaderSizeInBytes,
                    maxTxSizeInBytes,
                    maxBlockSizeInBytes,
                    maxTxCountPerBlock,
                    bipExpirationPeriodMs,
                    bipApprovalThresholdBps,
                    genesisNetworkBlockReward,
                    genesisNetworkBlockRewardPoolAddress,
                    genesisNetworkTargetMiningTimeMs,
                    genesisNetworkAsertHalfLifeBlocks,
                    genesisNetworkMinDifficulty,
                    genesisNetworkMinTxBaseFee,
                    genesisNetworkMinTxByteFee,
                    genesisAuthorityAddresses,
                    genesisBlockTimestamp,
                    genesisBlockDifficulty,
                    genesisNativeTokenName,
                    genesisNativeTokenTicker,
                    genesisNativeTokenDecimals,
                    genesisNativeTokenWebsite,
                    genesisNativeTokenLogo,
                    genesisNativeTokenUserBurnable,
                    forkActivationBlocks,
                    blockCheckpoints,
                    randomXEpochLength,
                    randomXGenesisKey,
                    randomXBatchSize,
                    Collections.unmodifiableMap(new HashMap<>(maxBlockSizeOverrides)),
                    Collections.unmodifiableMap(new HashMap<>(maxTxSizeOverrides)),
                    Collections.unmodifiableMap(new HashMap<>(maxTxCountOverrides)),
                    Collections.unmodifiableMap(new HashMap<>(maxHeaderSizeOverrides)));
        }
    }
}
