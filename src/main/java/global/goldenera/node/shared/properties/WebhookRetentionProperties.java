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
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ge.webhook.retention", ignoreUnknownFields = false)
public class WebhookRetentionProperties {

	boolean enabled = true;
	Duration auditWindow = Duration.ofDays(90);
	Duration runInterval = Duration.ofHours(1);
	Duration initialDelay = Duration.ofMinutes(5);
	int batchSize = 1_000;
	int maxBatchesPerRun = 20;
	long journalSafetyEntries = 10_000L;
	long journalMaxRetainedEntries = 1_000_000L;

	@PostConstruct
	void validate() {
		if (auditWindow == null || auditWindow.isNegative() || auditWindow.isZero()) {
			throw new IllegalArgumentException("Webhook retention audit window must be positive");
		}
		if (runInterval == null || runInterval.isNegative() || runInterval.isZero()
				|| initialDelay == null || initialDelay.isNegative()) {
			throw new IllegalArgumentException("Webhook retention schedule is invalid");
		}
		if (batchSize < 1 || batchSize > 100_000 || maxBatchesPerRun < 1 || maxBatchesPerRun > 10_000) {
			throw new IllegalArgumentException("Webhook retention batch limits are invalid");
		}
		if (journalSafetyEntries < 0L || journalMaxRetainedEntries < 1L
				|| journalSafetyEntries >= journalMaxRetainedEntries) {
			throw new IllegalArgumentException("Lifecycle journal retention limits are invalid");
		}
	}
}
