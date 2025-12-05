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
package global.goldenera.node.shared.services;

import static lombok.AccessLevel.PRIVATE;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.node.shared.properties.ThrottlingProperties;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ThrottlingService {

    ThrottlingProperties throttlingProperties;

    private static final Duration REFILL_DURATION = Duration.ofSeconds(1);

    Map<String, Bucket> globalIpCache = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(10_000)
            .<String, Bucket>build().asMap();

    Map<String, Bucket> specificLogicCache = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(50_000)
            .<String, Bucket>build().asMap();

    private static final Map<Pattern, Integer> ENDPOINT_COSTS = new LinkedHashMap<>();
    static {
        // ============= HEAVY IO / Range Scans (highest cost) =============
        // Block header range - full DB scan
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/block-header/by-range.*"), 20);
        // Block TXs - loads full block body + pagination
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/block/by-hash/.*/txs.*"), 15);
        // All tokens/authorities - full index scan
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/tokens.*"), 15);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/authorities.*"), 15);

        // ============= MODERATE IO (DB lookups) =============
        // Block header by hash/height - indexed lookup
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/block-header/by-hash/.*"), 5);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/block-header/by-height/.*"), 5);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/block-header/latest.*"), 3);
        // Block hash/height conversions
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/block-hash/by-height/.*"), 3);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/block-height/by-hash/.*"), 3);

        // ============= TRANSACTION ENDPOINTS =============
        // TX lookup by hash
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/tx/by-hash/.*/confirmations.*"), 5);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/tx/by-hash/.*/block-height.*"), 3);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/tx/by-hash/.*"), 3);

        // ============= WORLDSTATE ENDPOINTS (in-memory cache) =============
        // BIP state - might be complex
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/bip-state/.*"), 3);
        // Account balance/nonce - simple trie lookup
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/account/.*"), 2);
        // Token/Authority/Alias lookup
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/token/.*"), 2);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/authority/.*"), 2);
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/address-alias/.*"), 2);
        // Network params - cached
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/worldstate/network-params.*"), 1);
        // Latest height - very cheap
        ENDPOINT_COSTS.put(Pattern.compile(".*/blockchain/latest-height.*"), 1);

        // ============= MEMPOOL ENDPOINTS =============
        // Submit TX - crypto validation required
        ENDPOINT_COSTS.put(Pattern.compile(".*/mempool/submit.*"), 10);
        // Inventory check
        ENDPOINT_COSTS.put(Pattern.compile(".*/mempool/inventory.*"), 5);

        // Default cost is 1 (for any unmatched endpoints)
    }

    /**
     * Layer 1: Global IP Check.
     * Returns false if the IP is spamming the server entirely.
     */
    public boolean checkGlobalIpLimit(HttpServletRequest request) {
        String ip = getClientIp(request);
        Bucket bucket = globalIpCache.computeIfAbsent(ip, k -> createBucket(throttlingProperties.getGlobalCapacity(),
                throttlingProperties.getGlobalRefillTokens()));
        return bucket.tryConsume(1);
    }

    /**
     * Layer 2: Context Aware Check.
     * Handles Public Core vs API Key logic and calculating costs.
     */
    public boolean checkSpecificLimit(HttpServletRequest request, String keyIdentifier, boolean isApiKey) {
        String uri = request.getRequestURI();
        Bucket bucket = specificLogicCache.computeIfAbsent(keyIdentifier + ":" + getBucketType(uri, isApiKey),
                k -> createBucketForContext(uri, isApiKey));

        int cost = resolveCost(uri);
        return bucket.tryConsume(cost);
    }

    private Bucket createBucketForContext(String uri, boolean isApiKey) {
        if (isApiKey) {
            if (uri.startsWith("/api/explorer")) {
                // API Key accessing Explorer -> Reasonable Limit
                return createBucket(throttlingProperties.getApiKeyExplorerCapacity(),
                        throttlingProperties.getApiKeyExplorerRefillTokens());
            } else {
                // API Key accessing anything else (Core/Shared) -> Mega Loose
                return createBucket(throttlingProperties.getApiKeyDefaultCapacity(),
                        throttlingProperties.getApiKeyDefaultRefillTokens());
            }
        } else {
            // Public User (IP Based) accessing Core
            // Note: Explorer is blocked for public by SecurityConfig anyway, so we assume
            // Core here
            return createBucket(throttlingProperties.getPublicCoreCapacity(),
                    throttlingProperties.getPublicCoreRefillTokens());
        }
    }

    private Bucket createBucket(long capacity, long refillTokens) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity)
                        .refillGreedy(refillTokens, REFILL_DURATION))
                .build();
    }

    private int resolveCost(String uri) {
        for (Map.Entry<Pattern, Integer> entry : ENDPOINT_COSTS.entrySet()) {
            if (entry.getKey().matcher(uri).matches()) {
                return entry.getValue();
            }
        }
        return 1;
    }

    private String getBucketType(String uri, boolean isApiKey) {
        if (isApiKey) {
            return uri.startsWith("/api/explorer") ? "API_EXPLORER" : "API_DEFAULT";
        }
        return "PUBLIC_CORE";
    }

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}