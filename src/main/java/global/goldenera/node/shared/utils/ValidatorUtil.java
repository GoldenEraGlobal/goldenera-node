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
package global.goldenera.node.shared.utils;

import java.net.URI;
import java.util.Base64;

import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ValidatorUtil {

    public static String isNullOrEmpty(String value) {
        if (value == null || value.isEmpty() || value.trim().isEmpty()) {
            throw new GEValidationException("Value cannot be null or empty.");
        }
        return value;
    }

    public static void validateSecuritySecret(String secret, String property) {
        if (secret == null || secret.isBlank()) {
            throw new GEValidationException("Property '" + property + "' cannot be blank!");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new GEValidationException(
                    "Secret property '" + property + "' is not in valid Base64 format.", e);
        }

        if (keyBytes.length != 32) {
            throw new GEValidationException(
                    "Decoded secret property '" + property + "' must be exactly 32 bytes (256 bits)! "
                            +
                            "Check if you generated it using 'openssl rand -base64 32'.");
        }
    }

    @UtilityClass
    public static class Url {
        public static String url(String url) throws GEValidationException {
            url = isNullOrEmpty(url);

            try {
                URI uri = new URI(url);
                String scheme = uri.getScheme();
                String host = uri.getHost();

                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    throw new GEValidationException("URL must start with 'http://' or 'https://'.");
                }

                if (host == null || host.isBlank()) {
                    throw new GEValidationException(
                            "URL '" + url + "' does not contain a valid host.");
                }
                return url;
            } catch (java.net.URISyntaxException e) {
                throw new GEValidationException("URL '" + url + "' is not valid.", e);
            } catch (Exception e) {
                throw new GEValidationException("Validation of URL '" + url + "' failed.", e);
            }
        }

        /**
         * Checks if a URL is safe to use (not pointing to localhost or private
         * networks).
         * This is SSRF (Server-Side Request Forgery) protection.
         * Does NOT use DNS resolution - works reliably in Docker.
         */
        public static boolean isSafe(String urlStr) {
            try {
                URI uri = new URI(urlStr);
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    return false;
                }
                return HostValidator.isSafe(host);
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * Validates hostnames and IP addresses without DNS resolution.
     * Blocks localhost, private IPs, and internal Docker hostnames.
     */
    @UtilityClass
    public static class HostValidator {
        // Blocked hostname patterns (case-insensitive)
        private static final String[] BLOCKED_HOSTNAMES = {
                "localhost",
                "host.docker.internal",
                "gateway.docker.internal",
                "kubernetes.default",
                "metadata.google.internal"
        };

        /**
         * Check if a host (hostname or IP) is safe for external requests.
         * Returns true if the host appears to be a public/external address.
         */
        public static boolean isSafe(String host) {
            if (host == null || host.isBlank()) {
                return false;
            }
            host = host.toLowerCase().trim();

            // Block known internal hostnames
            for (String blocked : BLOCKED_HOSTNAMES) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) {
                    return false;
                }
            }

            // Check if it looks like an IP address
            if (isIpAddress(host)) {
                return isPublicIp(host);
            }

            // For regular hostnames, allow them (we can't resolve DNS reliably in Docker)
            // The main concern is blocking localhost and private IPs
            return true;
        }

        private static boolean isIpAddress(String host) {
            // Simple check: contains only digits and dots (IPv4) or contains colon (IPv6)
            return host.matches("^[0-9.]+$") || host.contains(":");
        }

        private static boolean isPublicIp(String ip) {
            // Handle IPv6 loopback
            if (ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
                return false;
            }

            // Parse IPv4
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                // Not a valid IPv4. For IPv6, allow by default (could be enhanced)
                return !ip.startsWith("fe80:") && !ip.startsWith("fc") && !ip.startsWith("fd");
            }

            try {
                int[] octets = new int[4];
                for (int i = 0; i < 4; i++) {
                    octets[i] = Integer.parseInt(parts[i]);
                    if (octets[i] < 0 || octets[i] > 255) {
                        return false;
                    }
                }

                // 127.0.0.0/8 - Loopback
                if (octets[0] == 127) {
                    return false;
                }

                // 0.0.0.0/8 - "This" network
                if (octets[0] == 0) {
                    return false;
                }

                // 10.0.0.0/8 - Private
                if (octets[0] == 10) {
                    return false;
                }

                // 172.16.0.0/12 - Private (172.16.0.0 - 172.31.255.255)
                if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
                    return false;
                }

                // 192.168.0.0/16 - Private
                if (octets[0] == 192 && octets[1] == 168) {
                    return false;
                }

                // 169.254.0.0/16 - Link-local (including AWS metadata 169.254.169.254)
                if (octets[0] == 169 && octets[1] == 254) {
                    return false;
                }

                // 100.64.0.0/10 - Carrier-grade NAT
                if (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127) {
                    return false;
                }

                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}
