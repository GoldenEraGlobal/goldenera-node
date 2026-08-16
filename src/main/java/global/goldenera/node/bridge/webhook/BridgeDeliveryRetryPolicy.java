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
package global.goldenera.node.bridge.webhook;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.springframework.stereotype.Component;

@Component
public class BridgeDeliveryRetryPolicy {

	static final int MAX_ATTEMPTS = 12;
	static final Duration BASE_DELAY = Duration.ofSeconds(5);
	static final Duration MAX_DELAY = Duration.ofHours(1);
	static final Duration MAX_RETRY_AFTER = Duration.ofDays(1);

	public Outcome classify(Integer statusCode, boolean networkFailure, int attempt) {
		if (!networkFailure && statusCode != null && statusCode >= 200 && statusCode < 300) {
			return Outcome.DELIVERED;
		}
		boolean retryable = networkFailure || statusCode == null || statusCode == 408 || statusCode == 429
				|| statusCode >= 500;
		return retryable && attempt < MAX_ATTEMPTS ? Outcome.RETRY : Outcome.DEAD;
	}

	public Instant nextAttemptAt(int attempt, Integer statusCode, String retryAfter, Instant now) {
		return nextAttemptAt(attempt, statusCode, retryAfter, now, ThreadLocalRandom.current()::nextDouble);
	}

	Instant nextAttemptAt(int attempt, Integer statusCode, String retryAfter, Instant now, DoubleSupplier random) {
		boolean retryAfterApplicable = statusCode != null
				&& (statusCode == 408 || statusCode == 429 || statusCode >= 500);
		Duration serverDelay = retryAfterApplicable ? parseRetryAfter(retryAfter, now) : null;
		if (serverDelay != null) {
			return now.plus(serverDelay.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : serverDelay);
		}
		long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
		long delayMillis = Math.min(MAX_DELAY.toMillis(), BASE_DELAY.toMillis() * multiplier);
		double jitterMultiplier = 0.8d + random.getAsDouble() * 0.4d;
		return now.plusMillis(Math.max(1L, Math.round(delayMillis * jitterMultiplier)));
	}

	private Duration parseRetryAfter(String retryAfter, Instant now) {
		if (retryAfter == null || retryAfter.isBlank()) {
			return null;
		}
		try {
			long seconds = Long.parseLong(retryAfter.trim());
			return seconds < 0 ? null : Duration.ofSeconds(seconds);
		} catch (NumberFormatException ignored) {
			try {
				Duration delay = Duration.between(now,
						ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
				return delay.isNegative() ? Duration.ZERO : delay;
			} catch (DateTimeParseException invalidDate) {
				return null;
			}
		}
	}

	public enum Outcome {
		DELIVERED,
		RETRY,
		DEAD
	}
}
