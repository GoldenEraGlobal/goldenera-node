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
package global.goldenera.node.core.sync.snapshot.publication;

import java.time.Duration;

import global.goldenera.cryptoj.common.state.NetworkParamsState;

/** Deterministic minimum 24-hour canonical safety lag. */
public final class SnapshotSafetyLagCalculator {

	public static final Duration MINIMUM_SAFETY_LAG = Duration.ofHours(24);

	public long lagBlocks(NetworkParamsState params, long configuredMinimumBlocks) {
		if (params == null) {
			throw new IllegalArgumentException("Canonical network parameters are required");
		}
		return lagBlocks(params.getTargetMiningTimeMs(), configuredMinimumBlocks);
	}

	public long lagBlocks(long targetBlockIntervalMillis, long configuredMinimumBlocks) {
		if (targetBlockIntervalMillis <= 0 || configuredMinimumBlocks < 0) {
			throw new IllegalArgumentException("Snapshot block interval/lag override is invalid");
		}
		long minimumMillis = MINIMUM_SAFETY_LAG.toMillis();
		long derived = minimumMillis / targetBlockIntervalMillis
				+ (minimumMillis % targetBlockIntervalMillis == 0 ? 0 : 1);
		if (derived <= 0) {
			throw new IllegalArgumentException("Snapshot safety lag calculation underflowed");
		}
		return Math.max(derived, configuredMinimumBlocks);
	}

	public long snapshotHeight(
			long canonicalHeadHeight,
			long targetBlockIntervalMillis,
			long configuredMinimumBlocks) {
		long lag = lagBlocks(targetBlockIntervalMillis, configuredMinimumBlocks);
		if (canonicalHeadHeight < lag) {
			throw new IllegalStateException("Canonical head is younger than the mandatory snapshot safety lag");
		}
		return Math.subtractExact(canonicalHeadHeight, lag);
	}
}
