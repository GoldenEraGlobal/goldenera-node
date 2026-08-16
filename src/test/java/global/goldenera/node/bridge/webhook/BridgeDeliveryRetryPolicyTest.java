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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import global.goldenera.node.bridge.webhook.BridgeDeliveryRetryPolicy.Outcome;

class BridgeDeliveryRetryPolicyTest {

	private final BridgeDeliveryRetryPolicy policy = new BridgeDeliveryRetryPolicy();

	@Test
	void classifiesHttpAndNetworkOutcomes() {
		assertThat(policy.classify(204, false, 1)).isEqualTo(Outcome.DELIVERED);
		assertThat(policy.classify(null, true, 1)).isEqualTo(Outcome.RETRY);
		assertThat(policy.classify(408, false, 1)).isEqualTo(Outcome.RETRY);
		assertThat(policy.classify(429, false, 1)).isEqualTo(Outcome.RETRY);
		assertThat(policy.classify(503, false, 1)).isEqualTo(Outcome.RETRY);
		assertThat(policy.classify(400, false, 1)).isEqualTo(Outcome.DEAD);
		assertThat(policy.classify(503, false, BridgeDeliveryRetryPolicy.MAX_ATTEMPTS)).isEqualTo(Outcome.DEAD);
	}

	@Test
	void exponentialDelayIncludesDeterministicJitter() {
		Instant now = Instant.parse("2026-08-16T12:00:00Z");

		Instant next = policy.nextAttemptAt(3, 503, null, now, () -> 0.5d);

		assertThat(next).isEqualTo(now.plusSeconds(20));
	}

	@Test
	void retryAfterOverridesBackoffAndIsCapped() {
		Instant now = Instant.parse("2026-08-16T12:00:00Z");

		assertThat(policy.nextAttemptAt(1, 429, "120", now, () -> 0.5d)).isEqualTo(now.plusSeconds(120));
		assertThat(policy.nextAttemptAt(1, 429, "999999", now, () -> 0.5d)).isEqualTo(now.plusSeconds(86_400));
	}
}
