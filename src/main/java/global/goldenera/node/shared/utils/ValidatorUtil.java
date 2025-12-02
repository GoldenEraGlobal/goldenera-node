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

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
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

        public static boolean isSafe(String urlStr) {
            try {
                URL url = new URL(urlStr);
                return isSafe(url.getHost());
            } catch (Exception e) {
                return false;
            }
        }
    }

    public static class IpAddress {
        public static boolean isSafe(String str) {
            try {
                InetAddress address = InetAddress.getByName(str);
                if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    return false;
                }
                if (address.getHostAddress().equals("169.254.169.254")) {
                    return false;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
