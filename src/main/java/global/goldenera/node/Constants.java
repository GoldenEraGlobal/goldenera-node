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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.shared.utils.VersionUtil;
import lombok.experimental.UtilityClass;

/**
 * Global constants and network configuration access.
 * 
 * Network-specific settings are composed of:
 * - Genesis settings: loaded from JSON files (genesis-{network}-{profile}.json)
 * - Consensus settings: hardcoded here (MUST be identical on all nodes)
 * 
 * For development, copy the .example template and customize genesis settings.
 * Dev genesis files are git-ignored for local customization.
 */
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
                // Add future forks here, e.g.:
                // UPGRADE_1,
                // UPGRADE_2;
        }

        // =============================================
        // DIRECTORY CONFIGURATION (can change, split by network)
        // =============================================

        /**
         * Directory service configuration.
         * These settings can change over time (unlike consensus settings).
         */
        public record DirectoryConfig(
                        String host,
                        Address identityAddress) {
        }

        /**
         * Directory configuration for MAINNET.
         */
        private static final DirectoryConfig MAINNET_DIRECTORY = new DirectoryConfig(
                        "https://directory.goldenera.global",
                        Address.fromHexString("0xecb9d8f3e8b3f6f961065f4d942df8a2bedec2f4"));

        /**
         * Directory configuration for TESTNET.
         */
        private static final DirectoryConfig TESTNET_DIRECTORY = new DirectoryConfig(
                        "https://directory.goldenera.global",
                        Address.fromHexString("0xecb9d8f3e8b3f6f961065f4d942df8a2bedec2f4"));

        /**
         * Get directory configuration for a specific network.
         */
        public static DirectoryConfig getDirectoryConfig(Network network) {
                return switch (network) {
                        case MAINNET -> MAINNET_DIRECTORY;
                        case TESTNET -> TESTNET_DIRECTORY;
                };
        }

        /**
         * Get directory configuration for the active network.
         */
        public static DirectoryConfig getDirectoryConfig() {
                return getDirectoryConfig(getActiveNetwork());
        }

        // =============================================
        // CONSENSUS SETTINGS (hardcoded, same for all nodes)
        // =============================================

        /**
         * Consensus-critical settings for MAINNET.
         * These MUST be identical on all nodes.
         */
        private static final ConsensusSettings MAINNET_CONSENSUS = new ConsensusSettings(
                        // Fork activation blocks
                        Map.of(
                                        ForkName.GENESIS, 0L
                        // Add future forks here, e.g.:
                        // ForkName.UPGRADE_1, 100000L
                        ),
                        // Block checkpoints (height -> hash)
                        Map.of(
                        // Add verified block hashes here, e.g.:
                        // 0L, Hash.fromHexString("0x..."),
                        // 10000L, Hash.fromHexString("0x...")
                        ),
                        // Max block size overrides (height -> new value)
                        Map.of(),
                        // Max tx size overrides
                        Map.of(),
                        // Max tx count overrides
                        Map.of(),
                        // Max header size overrides
                        Map.of());

        /**
         * Consensus-critical settings for TESTNET.
         * These MUST be identical on all nodes.
         */
        private static final ConsensusSettings TESTNET_CONSENSUS = new ConsensusSettings(
                        // Fork activation blocks
                        Map.of(
                                        ForkName.GENESIS, 0L),
                        // Block checkpoints
                        Map.of(),
                        // Max block size overrides
                        Map.of(),
                        // Max tx size overrides
                        Map.of(),
                        // Max tx count overrides
                        Map.of(),
                        // Max header size overrides
                        Map.of());

        /**
         * Generates DEV consensus settings with ALL forks activated at block 0.
         * Used for local development to test all features immediately.
         * Similar to Ethereum's --dev mode.
         */
        private static ConsensusSettings createDevConsensus() {
                // Activate ALL forks at block 0
                Map<ForkName, Long> allForksAtZero = new HashMap<>();
                for (ForkName fork : ForkName.values()) {
                        allForksAtZero.put(fork, 0L);
                }
                return new ConsensusSettings(
                                Collections.unmodifiableMap(allForksAtZero),
                                Map.of(), // No checkpoints for dev
                                Map.of(), // No overrides
                                Map.of(),
                                Map.of(),
                                Map.of());
        }

        /**
         * Get consensus settings for a specific network.
         * For dev profile, returns settings with all forks activated at block 0.
         */
        public static ConsensusSettings getConsensusSettings(Network network) {
                // For dev profile, activate all forks at block 0
                if ("dev".equals(getActiveProfile())) {
                        return createDevConsensus();
                }

                return switch (network) {
                        case MAINNET -> MAINNET_CONSENSUS;
                        case TESTNET -> TESTNET_CONSENSUS;
                };
        }

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
         * Get the currently active profile (prod or dev).
         */
        public static String getActiveProfile() {
                return NetworkSettingsProvider.getActiveProfile();
        }

        /**
         * Get settings for the currently active network.
         * Settings combine genesis settings from JSON with consensus settings.
         */
        public static NetworkSettings getSettings() {
                return NetworkSettingsProvider.getSettings();
        }

        /**
         * Get settings for a specific network.
         * Uses the current active profile for loading genesis settings.
         */
        public static NetworkSettings getSettings(Network network) {
                return NetworkSettingsProvider.getSettings(network);
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