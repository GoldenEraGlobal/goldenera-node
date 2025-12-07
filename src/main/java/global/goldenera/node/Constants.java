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

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.utils.Amounts;
import global.goldenera.node.shared.utils.VersionUtil;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {

        // =============================================
        // GLOBAL CONSTANTS (same for all networks)
        // =============================================
        public static final String NODE_VERSION = VersionUtil.getApplicationVersion();
        public static final long P2P_PROTOCOL_VERSION = 1;

        // =============================================
        // FORK NAMES
        // =============================================
        public enum ForkName {
                GENESIS;
        }

        // =============================================
        // NETWORK CONFIGURATIONS
        // =============================================
        public static final Map<Network, NetworkSettings> NETWORK_SETTINGS = Map.of(
                        Network.MAINNET, NetworkSettings.builder()
                                        // Directory configuration
                                        .directoryHost("https://directory.goldenera.global")
                                        .directoryIdentityAddress(
                                                        Address.fromHexString(
                                                                        "0xecb9d8f3e8b3f6f961065f4d942df8a2bedec2f4"))
                                        // Size limits
                                        .maxHeaderSizeInBytes(500L)
                                        .maxTxSizeInBytes(100_000L)
                                        .maxBlockSizeInBytes(5_000_000L)
                                        .maxTxCountPerBlock(100_000L)
                                        // BIP configuration
                                        .bipExpirationPeriodMs(86400000L)
                                        .bipApprovalThresholdBps(5100L)
                                        // Genesis network parameters
                                        .genesisNetworkBlockReward(Amounts.tokens(1))
                                        .genesisNetworkBlockRewardPoolAddress(
                                                        Address.fromHexString(
                                                                        "0x4B5C9d7CcB07466d658c2a67740b2D3D80D3CA76"))
                                        .genesisNetworkTargetMiningTimeMs(15000L)
                                        .genesisNetworkAsertHalfLifeBlocks(15L)
                                        .genesisNetworkMinDifficulty(BigInteger.valueOf(1000))
                                        .genesisNetworkMinTxBaseFee(Amounts.tokensDecimal("0.0001"))
                                        .genesisNetworkMinTxByteFee(Amounts.tokensDecimal("0.000001"))
                                        // Genesis authorities
                                        .genesisAuthorityAddresses(List.of(
                                                        Address.fromHexString(
                                                                        "0x4B5C9d7CcB07466d658c2a67740b2D3D80D3CA76")))
                                        // Genesis block
                                        .genesisBlockTimestamp(1764806403777L)
                                        .genesisBlockDifficulty(BigInteger.valueOf(1000))
                                        // Native token
                                        .genesisNativeTokenName("GoldenEraCoin")
                                        .genesisNativeTokenTicker("GEC")
                                        .genesisNativeTokenDecimals(9)
                                        .genesisNativeTokenWebsite("https://goldenera.global")
                                        .genesisNativeTokenLogo("https://goldenera.global/TokenTicker/logo.png")
                                        .genesisNativeTokenUserBurnable(false)
                                        // Forks
                                        .forkActivationBlocks(Map.of(ForkName.GENESIS, 0L))
                                        // Checkpoints
                                        .blockCheckpoints(Map.of())
                                        // RandomX
                                        .randomXEpochLength(2048L)
                                        .randomXGenesisKey("GLDNR_RNDX_GENESIS_KEY")
                                        .randomXBatchSize(32768)
                                        .build(),

                        Network.TESTNET, NetworkSettings.builder()
                                        // Directory configuration
                                        .directoryHost("https://directory.goldenera.global")
                                        .directoryIdentityAddress(
                                                        Address.fromHexString(
                                                                        "0xecb9d8f3e8b3f6f961065f4d942df8a2bedec2f4"))
                                        // Size limits
                                        .maxHeaderSizeInBytes(500L)
                                        .maxTxSizeInBytes(100_000L)
                                        .maxBlockSizeInBytes(5_000_000L)
                                        .maxTxCountPerBlock(100_000L)
                                        // BIP configuration
                                        .bipExpirationPeriodMs(86400000L)
                                        .bipApprovalThresholdBps(5100L)
                                        // Genesis network parameters
                                        .genesisNetworkBlockReward(Amounts.tokens(5))
                                        .genesisNetworkBlockRewardPoolAddress(
                                                        Address.fromHexString(
                                                                        "0x4B5C9d7CcB07466d658c2a67740b2D3D80D3CA76"))
                                        .genesisNetworkTargetMiningTimeMs(15000L)
                                        .genesisNetworkAsertHalfLifeBlocks(25L)
                                        .genesisNetworkMinDifficulty(BigInteger.valueOf(500))
                                        .genesisNetworkMinTxBaseFee(Amounts.tokensDecimal("0.0001"))
                                        .genesisNetworkMinTxByteFee(Amounts.tokensDecimal("0.000001"))
                                        // Genesis authorities
                                        .genesisAuthorityAddresses(List.of(
                                                        Address.fromHexString(
                                                                        "0x4B5C9d7CcB07466d658c2a67740b2D3D80D3CA76")))
                                        // Genesis block
                                        .genesisBlockTimestamp(1765133107049L)
                                        .genesisBlockDifficulty(BigInteger.valueOf(500))
                                        // Native token
                                        .genesisNativeTokenName("TestEraCoin")
                                        .genesisNativeTokenTicker("tGEC")
                                        .genesisNativeTokenDecimals(9)
                                        .genesisNativeTokenWebsite("https://goldenera.global")
                                        .genesisNativeTokenLogo("https://goldenera.global/TokenTicker/logo.png")
                                        .genesisNativeTokenUserBurnable(false)
                                        // Forks
                                        .forkActivationBlocks(Map.of(ForkName.GENESIS, 0L))
                                        // Checkpoints
                                        .blockCheckpoints(Map.of())
                                        // RandomX
                                        .randomXEpochLength(10240L)
                                        .randomXGenesisKey("GLDNR_RNDX_GENESIS_KEY")
                                        .randomXBatchSize(32768)
                                        .build());

        // =============================================
        // CONVENIENCE METHODS
        // =============================================

        /**
         * Get the currently active network.
         * Reads from application.properties (ge.general.network) via Spring,
         * with fallback to NETWORK environment variable for early initialization.
         */
        public static Network getActiveNetwork() {
                return NetworkSettingsProvider.getActiveNetwork();
        }

        /**
         * Get settings for the currently active network.
         */
        public static NetworkSettings getSettings() {
                return NETWORK_SETTINGS.get(getActiveNetwork());
        }

        /**
         * Get settings for a specific network.
         */
        public static NetworkSettings getSettings(Network network) {
                return NETWORK_SETTINGS.get(network);
        }

        /**
         * Check if a fork is active at the given block height for the active network.
         */
        public static boolean isForkActive(ForkName fork, long blockHeight) {
                Long activationHeight = getSettings().forkActivationBlocks().get(fork);
                return activationHeight != null && blockHeight >= activationHeight;
        }

        /**
         * Check if a fork is active at the given block height for a specific network.
         */
        public static boolean isForkActive(Network network, ForkName fork, long blockHeight) {
                Long activationHeight = getSettings(network).forkActivationBlocks().get(fork);
                return activationHeight != null && blockHeight >= activationHeight;
        }

        public static ForkName getActiveForkName(Network network, long blockHeight) {
                return getSettings(network).forkActivationBlocks().entrySet().stream()
                                .filter(entry -> entry.getValue() <= blockHeight)
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey)
                                .orElse(ForkName.GENESIS);
        }
}