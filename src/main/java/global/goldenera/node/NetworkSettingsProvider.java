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

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.shared.properties.GeneralProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides access to network-specific settings.
 * Bridges Spring-managed properties with static Constants access.
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

    private static Network activeNetwork;
    private static boolean initialized = false;

    @PostConstruct
    public void init() {
        activeNetwork = generalProperties.getNetwork();
        initialized = true;
        log.info("NetworkSettingsProvider initialized for network: {}", activeNetwork);
    }

    /**
     * Returns the active network configured in application.properties.
     * Must be called after Spring context initialization.
     */
    public static Network getActiveNetwork() {
        if (!initialized) {
            // Fallback for very early initialization (before Spring context loads)
            // This can happen during static initializers or tests
            String networkEnv = System.getenv().getOrDefault("NETWORK", "MAINNET");
            log.warn("NetworkSettingsProvider not yet initialized by Spring, using env fallback: {}", networkEnv);
            return Network.valueOf(networkEnv);
        }
        return activeNetwork;
    }

    /**
     * Returns the settings for the active network.
     */
    public static NetworkSettings getSettings() {
        return Constants.NETWORK_SETTINGS.get(getActiveNetwork());
    }

    /**
     * Returns the settings for a specific network.
     */
    public static NetworkSettings getSettings(Network network) {
        return Constants.NETWORK_SETTINGS.get(network);
    }

    /**
     * Check if the provider has been initialized by Spring.
     */
    public static boolean isInitialized() {
        return initialized;
    }
}
