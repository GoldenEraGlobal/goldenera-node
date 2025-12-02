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
package global.goldenera.node.core.p2p.reputation;

import java.time.Instant;

public record PeerReputationRecord(int failureCount, long lastFailureEpochSecond, long lastSuccessEpochSecond) {

	private static final PeerReputationRecord INITIAL = new PeerReputationRecord(0, 0L, 0L);
	private static final long BAN_DURATION_SECONDS = 12 * 3600;

	public static PeerReputationRecord initial() {
		return INITIAL;
	}

	public boolean isBanned() {
		return isBanned(Instant.now());
	}

	public boolean isBanned(Instant now) {
		if (failureCount != Integer.MAX_VALUE) {
			return false;
		}
		long age = now.getEpochSecond() - lastFailureEpochSecond;
		return age < BAN_DURATION_SECONDS;
	}

	public PeerReputationRecord checkExpiration(Instant now) {
		if (failureCount == Integer.MAX_VALUE) {
			long age = now.getEpochSecond() - lastFailureEpochSecond;
			if (age >= BAN_DURATION_SECONDS) {
				return new PeerReputationRecord(0, lastFailureEpochSecond, lastSuccessEpochSecond);
			}
		}
		return this;
	}

	public PeerReputationRecord withFailure(Instant when) {
		if (isBanned(when)) {
			return this;
		}
		int newCount = failureCount == Integer.MAX_VALUE ? Integer.MAX_VALUE
				: Math.min(Integer.MAX_VALUE, failureCount + 1);
		long timestamp = when != null ? when.getEpochSecond() : Instant.now().getEpochSecond();
		return new PeerReputationRecord(newCount, timestamp, lastSuccessEpochSecond);
	}

	public PeerReputationRecord withSuccess(Instant when) {
		if (isBanned(when)) {
			return this;
		}
		int newCount = failureCount > 0 ? 0 : failureCount;
		long timestamp = when != null ? when.getEpochSecond() : Instant.now().getEpochSecond();
		return new PeerReputationRecord(newCount, lastFailureEpochSecond, timestamp);
	}

	public PeerReputationRecord banned(Instant when) {
		long timestamp = when != null ? when.getEpochSecond() : Instant.now().getEpochSecond();
		return new PeerReputationRecord(Integer.MAX_VALUE, timestamp, lastSuccessEpochSecond);
	}

	public int reliabilityScore() {
		if (isBanned()) {
			return Integer.MIN_VALUE;
		}
		int cappedFailures = Math.min(1000, failureCount);
		return 1000 - cappedFailures;
	}
}
