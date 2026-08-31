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
package global.goldenera.node.core.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/** Independent bounds for CPU-scaled RandomX sync verification. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ge.core.sync.randomx", ignoreUnknownFields = false)
public class RandomXVerificationProperties {

	public static final int MAX_HARD_PARALLELISM = 64;
	public static final long HARD_MAX_MEMORY_MIB = 1024L * 1024;
	public static final long HARD_MAX_GAP = 100_000_000L;
	/** Conservative margin above the measured reusable-VM 4-vCPU FULL-dataset break-even. */
	public static final long MINIMUM_SAFE_EXPECTED_HASHES = 6144;

	RandomXSyncVerificationMode verificationMode = RandomXSyncVerificationMode.AUTO;

	/** Zero selects the JVM-visible processor count. */
	int parallelism;

	/** Hard safety ceiling for automatic and explicit verification concurrency. */
	int maxParallelism = 16;

	long bulkEnterGap = 4096;
	long tailExitGap = 1000;
	Duration rebuildCooldown = Duration.ofMinutes(10);
	long minimumExpectedHashes = MINIMUM_SAFE_EXPECTED_HASHES;
	long autoMinimumMemoryMib = 12 * 1024;
	long autoPreferredMemoryMib = 16 * 1024;
	long minimumNativeHeadroomMib = 6 * 1024;
	long minimumAvailableMemoryMib = 3 * 1024;

	public int resolveParallelism(int availableProcessors) {
		validate();
		if (availableProcessors < 1) {
			throw new IllegalArgumentException("availableProcessors must be positive");
		}
		int requested = parallelism == 0 ? availableProcessors : parallelism;
		return Math.max(1, Math.min(requested, Math.min(availableProcessors, maxParallelism)));
	}

	public int resolveFullDatasetParallelism(int availableProcessors) {
		int bounded = resolveParallelism(availableProcessors);
		int balanced = availableProcessors <= 4
				? Math.max(1, availableProcessors - 1)
				: availableProcessors <= 8 ? availableProcessors - 2 : Math.min(8, availableProcessors);
		return Math.max(1, Math.min(bounded, balanced));
	}

	public void validate() {
		if (verificationMode == null) {
			throw new IllegalStateException("RandomX sync verification mode is required");
		}
		if (parallelism < 0) {
			throw new IllegalStateException("RandomX verification parallelism must be zero or positive");
		}
		if (maxParallelism < 1 || maxParallelism > MAX_HARD_PARALLELISM) {
			throw new IllegalStateException("RandomX verification max parallelism must be in range 1.."
					+ MAX_HARD_PARALLELISM);
		}
		if (parallelism > maxParallelism) {
			throw new IllegalStateException("RandomX verification parallelism exceeds its hard maximum");
		}
		if (tailExitGap < 0 || bulkEnterGap <= tailExitGap || bulkEnterGap > HARD_MAX_GAP) {
			throw new IllegalStateException("RandomX sync verification gap hysteresis is invalid");
		}
		if (rebuildCooldown == null || rebuildCooldown.isNegative()
				|| rebuildCooldown.compareTo(Duration.ofDays(1)) > 0) {
			throw new IllegalStateException("RandomX sync verification cooldown is invalid");
		}
		if (minimumExpectedHashes < MINIMUM_SAFE_EXPECTED_HASHES || minimumExpectedHashes > HARD_MAX_GAP) {
			throw new IllegalStateException("RandomX sync verification amortization threshold is invalid");
		}
		if (!validMemory(autoMinimumMemoryMib) || !validMemory(autoPreferredMemoryMib)
				|| !validMemory(minimumNativeHeadroomMib) || !validMemory(minimumAvailableMemoryMib)
				|| autoPreferredMemoryMib < autoMinimumMemoryMib) {
			throw new IllegalStateException("RandomX sync verification memory thresholds are invalid");
		}
	}

	private boolean validMemory(long memoryMib) {
		return memoryMib > 0 && memoryMib <= HARD_MAX_MEMORY_MIB;
	}
}
