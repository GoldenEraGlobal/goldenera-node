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
package global.goldenera.node.shared.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ge.api-key-auth-cache", ignoreUnknownFields = false)
public class ApiKeyAuthenticationCacheProperties {

	private boolean enabled = true;
	private long maximumSize = 1_000;
	private Duration ttl = Duration.ofSeconds(5);
	private Duration negativeTtl = Duration.ofMillis(500);

	@PostConstruct
	public void validate() {
		if (maximumSize < 1) {
			throw new IllegalArgumentException("ge.api-key-auth-cache.maximum-size must be at least 1");
		}
		validateDuration(ttl, "ge.api-key-auth-cache.ttl");
		validateDuration(negativeTtl, "ge.api-key-auth-cache.negative-ttl");
	}

	private static void validateDuration(Duration duration, String property) {
		if (duration == null || duration.isNegative()) {
			throw new IllegalArgumentException(property + " must not be negative");
		}
		try {
			duration.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(property + " is too large", exception);
		}
	}
}
