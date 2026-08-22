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

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
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
                GENESIS,
                MINING_ECONOMICS;
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
        // SNAPSHOT DISTRIBUTION (service configuration)
        // =============================================

        /** Trusted HTTPS origins allowed to distribute immutable snapshots. */
        public record SnapshotDistributionConfig(List<URI> trustedSources) {
                public SnapshotDistributionConfig {
                        trustedSources = List.copyOf(trustedSources);
                }
        }

        private static final SnapshotDistributionConfig MAINNET_SNAPSHOT_DISTRIBUTION =
                        new SnapshotDistributionConfig(List.of(
                                        URI.create("https://node-eu2.goldenera.global/"),
                                        URI.create("https://node-eu1.goldenera.global/"),
                                        URI.create("https://node-us1.goldenera.global/"),
                                        URI.create("https://node-me1.goldenera.global/"),
                                        URI.create("https://node-asia1.goldenera.global/")));

        private static final SnapshotDistributionConfig TESTNET_SNAPSHOT_DISTRIBUTION =
                        new SnapshotDistributionConfig(List.of(
                                        URI.create("https://node-eu1.geram1.com/")));

        public static SnapshotDistributionConfig getSnapshotDistributionConfig(Network network) {
                return switch (network) {
                        case MAINNET -> MAINNET_SNAPSHOT_DISTRIBUTION;
                        case TESTNET -> TESTNET_SNAPSHOT_DISTRIBUTION;
                };
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
                                        ForkName.GENESIS, 0L,
                                        // 14,400 blocks (~5 days) after block 717,103 at 30 seconds per block
                                        ForkName.MINING_ECONOMICS, 731_503L
                        // Add future forks here, e.g.:
                        // ForkName.UPGRADE_1, 100000L
                        ),
                        // Block checkpoints (height -> hash)
                        Map.of(
						0L, Hash.fromHexString("0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f"),
                                        100_000L, Hash.fromHexString("0x61c97a01fe7c09baf1d6bc4b7c994825e505bf52c6a282b8171e38c81681a4f2"),
                                        300_000L, Hash.fromHexString("0x1462f92f895ed3a939455f10954b3c4f9874abebde421579024b51dc27ddcea6"),
                                        500_000L, Hash.fromHexString("0xd399f6e1482df71b4a5e22d9ac02a01950d52d58abea53fb40d2babba7852e6c"),
                                        650_000L, Hash.fromHexString("0x21f8484a3bbbe789446554fc0a8a8381aa96a3215572aa44fa82e85067a33e0e"),
                                        700_000L, Hash.fromHexString("0xb40f6cf8be10d312abe76b2173f1e1c52634b0b4b0be4a0a93a5eb3ff4491dcc")
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
                                        ForkName.GENESIS, 0L,
                                        // 150 blocks (~1 hour 15 minutes) after block 716,674 at 30 seconds per block
                                        ForkName.MINING_ECONOMICS, 716_824L),
                        // Block checkpoints
                        Map.of(
						0L, Hash.fromHexString("0xf403f287a52b794eba7645d193c53c2dfa084a52db11ad94d70d0c79107c05cc"),
                                        100_000L, Hash.fromHexString("0x6531a7858c0fb1ad1e96c873fef8d3b37715f2296c514cd84215ce34e04d2f36"),
                                        300_000L, Hash.fromHexString("0x58c6458894f68eac3ea3902f5da9367e254fed46996140790871dc7cde5e38ad"),
                                        500_000L, Hash.fromHexString("0x1c11a5108c88519c6b6111d23de3e0d597b17f71b8ccd914ad3e28bfaedbc646"),
                                        650_000L, Hash.fromHexString("0x507d1433a743026caa86e64d711bdeea55392dc1e585aa4fa43e5399cf557859"),
                                        700_000L, Hash.fromHexString("0x12f484b7f5fb54df56108bb7fdad7f916df468d35c3ee11c3ca562c0a4a9915e")),
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
                return getConsensusSettings(network, getActiveProfile());
        }

        static ConsensusSettings getConsensusSettings(Network network, String profile) {
                // For dev profile, activate all forks at block 0
                if ("dev".equals(profile)) {
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
                                .max((left, right) -> {
                                        int heightComparison = Long.compare(left.getValue(), right.getValue());
                                        return heightComparison != 0
                                                        ? heightComparison
                                                        : Integer.compare(left.getKey().ordinal(),
                                                                        right.getKey().ordinal());
                                })
                                .map(Map.Entry::getKey)
                                .orElse(ForkName.GENESIS);
        }
}
