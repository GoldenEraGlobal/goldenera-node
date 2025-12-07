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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.shared.properties.GeneralProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides access to network-specific settings.
 * 
 * Settings are composed of:
 * - GenesisSettings: loaded from JSON files (genesis-{network}-{profile}.json)
 * - ConsensusSettings: hardcoded in Constants (forks, checkpoints, overrides)
 * 
 * Genesis files selection based on:
 * - Network: ge.general.network property (MAINNET, TESTNET)
 * - Profile: Active Spring profile (prod, dev)
 * 
 * For dev profile, genesis files are git-ignored and each developer creates
 * their own
 * by copying the .example template files.
 * 
 * Initialized with HIGHEST_PRECEDENCE to ensure it's available
 * as early as possible in the Spring lifecycle.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class NetworkSettingsProvider {

    private final GeneralProperties generalProperties;
    private final Environment environment;

    private static Network activeNetwork;
    private static String activeProfile;
    private static NetworkSettings activeSettings;
    private static final Map<String, NetworkSettings> settingsCache = new ConcurrentHashMap<>();
    private static boolean initialized = false;

    @PostConstruct
    public void init() {
        activeNetwork = generalProperties.getNetwork();
        activeProfile = determineActiveProfile();

        log.info("Initializing NetworkSettingsProvider for network: {}, profile: {}", activeNetwork, activeProfile);

        // Load and combine settings for active network
        activeSettings = loadAndCacheSettings(activeNetwork, activeProfile);

        initialized = true;
        log.info("NetworkSettingsProvider initialized successfully");
    }

    /**
     * Determine the active profile (prod or dev).
     * Defaults to "prod" if no specific profile is set.
     */
    private String determineActiveProfile() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }

        // Check for dev profile
        if (Arrays.asList(profiles).contains("dev")) {
            return "dev";
        }

        // Default to prod
        return "prod";
    }

    /**
     * Load genesis settings from JSON and combine with consensus settings from
     * Constants.
     */
    private NetworkSettings loadAndCacheSettings(Network network, String profile) {
        String cacheKey = network.name() + "-" + profile;
        return settingsCache.computeIfAbsent(cacheKey, key -> {
            GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(network, profile);
            return NetworkSettings.fromGenesisSettings(genesis, network);
        });
    }

    /**
     * Returns the active network configured in application.properties.
     * Must be called after Spring context initialization.
     */
    public static Network getActiveNetwork() {
        if (!initialized) {
            // Fallback for very early initialization (before Spring context loads)
            String networkEnv = System.getenv().getOrDefault("NETWORK", "MAINNET");
            log.warn("NetworkSettingsProvider not yet initialized by Spring, using env fallback: {}", networkEnv);
            return Network.valueOf(networkEnv);
        }
        return activeNetwork;
    }

    /**
     * Returns the active Spring profile (prod or dev).
     */
    public static String getActiveProfile() {
        if (!initialized) {
            String profileEnv = System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "prod");
            log.warn("NetworkSettingsProvider not yet initialized by Spring, using env fallback profile: {}",
                    profileEnv);
            return profileEnv.contains("dev") ? "dev" : "prod";
        }
        return activeProfile;
    }

    /**
     * Returns the settings for the active network.
     * Settings combine genesis settings from JSON with consensus settings from
     * Constants.
     */
    public static NetworkSettings getSettings() {
        if (!initialized) {
            // Fallback for very early initialization
            Network network = getActiveNetwork();
            String profile = getActiveProfile();
            log.warn("NetworkSettingsProvider not yet initialized, loading settings directly for {}-{}",
                    network, profile);
            GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(network, profile);
            return NetworkSettings.fromGenesisSettings(genesis, network);
        }
        return activeSettings;
    }

    /**
     * Returns the settings for a specific network.
     * Uses the current profile for loading genesis settings.
     */
    public static NetworkSettings getSettings(Network network) {
        String profile = getActiveProfile();
        String cacheKey = network.name() + "-" + profile;

        NetworkSettings cached = settingsCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Load if not cached (shouldn't happen often after init)
        GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(network, profile);
        NetworkSettings settings = NetworkSettings.fromGenesisSettings(genesis, network);
        settingsCache.put(cacheKey, settings);
        return settings;
    }

    /**
     * Check if the provider has been initialized by Spring.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Clear the settings cache.
     * Useful for testing or hot-reloading configurations.
     */
    public static void clearCache() {
        settingsCache.clear();
        log.info("NetworkSettings cache cleared");
    }
}
