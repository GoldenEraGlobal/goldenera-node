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
package global.goldenera.node.core.blockchain.utils;

import java.math.BigInteger;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DifficultyUtil {

	private static final BigInteger MAX_TARGET = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

	// Fixed Point Precision (2^16 = 65536) for ASERT cubic approximation
	private static final int FIXED_POINT_SHIFT = 16;
	private static final long FIXED_POINT_ONE = 1L << FIXED_POINT_SHIFT;

	/**
	 * Calculates the new difficulty using Absolute ASERT.
	 */
	public static BigInteger calculateAbsoluteAsertDifficulty(
			@NonNull BigInteger anchorDifficulty,
			long timeDeltaMs,
			long heightDelta,
			long targetBlockTimeMs,
			long tauMs,
			BigInteger minDifficulty) {
		if (tauMs <= 0)
			tauMs = 1;
		if (heightDelta < 0) {
			heightDelta = 0;
		}
		// 1. Convert Anchor Difficulty to Target (Work)
		BigInteger anchorTarget = calculateTargetFromDifficulty(anchorDifficulty);

		// 2. Calculate Drift (Difference between Actual Time and Expected Time)
		long idealTimeMs = heightDelta * targetBlockTimeMs;
		long driftMs = timeDeltaMs - idealTimeMs;

		// 3. Calculate the scaling factor: 2^(drift / tau)
		BigInteger scalingFactorFixedPoint = calculateAsertMultiplier(driftMs, tauMs);

		// 4. Apply factor to Anchor Target
		BigInteger newTarget = anchorTarget.multiply(scalingFactorFixedPoint)
				.shiftRight(FIXED_POINT_SHIFT);

		// 5. Clamp Target Limits (Prevent overflow or zero)
		if (newTarget.compareTo(BigInteger.ZERO) <= 0) {
			newTarget = BigInteger.ONE;
		}
		if (newTarget.compareTo(MAX_TARGET) > 0) {
			newTarget = MAX_TARGET;
		}

		// 6. Convert back to Difficulty
		BigInteger newDifficulty = MAX_TARGET.divide(newTarget);

		// 7. Apply Protocol Floor (Min Difficulty)
		if (newDifficulty.compareTo(minDifficulty) < 0) {
			return minDifficulty;
		}

		return newDifficulty;
	}

	/**
	 * Dynamically calculates the maximum allowed "Time in Future" for a block
	 * timestamp.
	 */
	public static long calculateDynamicMaxFutureTime(long targetMiningTimeMs) {
		// Minimum safety margin (e.g. for NTP clock skews) - 15 seconds
		long minSafetyMarginMs = 15_000;

		// Multiplier logic - allow up to 5 blocks worth of future time
		long dynamicMargin = targetMiningTimeMs * 5;

		return Math.max(minSafetyMarginMs, dynamicMargin);
	}

	/**
	 * Calculates 2^(drift / tau) in fixed point representation.
	 * Uses cubic approximation for the fractional part.
	 */
	private static BigInteger calculateAsertMultiplier(long drift, long tau) {
		long q = drift / tau;
		long r = drift % tau;

		// Handle negative remainder for correct floor division
		if (r < 0) {
			q -= 1;
			r += tau;
		}

		// Calculate fractional part x = r / tau
		// We calculate (r << 16) / tau to get fixed point result
		long fractionalX = (r << FIXED_POINT_SHIFT) / tau;

		// Cubic approximation of 2^x for 0 <= x < 1
		// 2^x ~= 1 + 0.695x + 0.226x^2 + 0.078x^3
		long x = fractionalX;
		long x2 = (x * x) >> FIXED_POINT_SHIFT;
		long x3 = (x2 * x) >> FIXED_POINT_SHIFT;

		// Coefficients scaled by 65536
		long term1 = (45573 * x) >> FIXED_POINT_SHIFT; // ~0.695 * x
		long term2 = (14830 * x2) >> FIXED_POINT_SHIFT; // ~0.226 * x^2
		long term3 = (5127 * x3) >> FIXED_POINT_SHIFT; // ~0.078 * x^3

		long fractionalMultiplier = FIXED_POINT_ONE + term1 + term2 + term3;

		BigInteger result = BigInteger.valueOf(fractionalMultiplier);

		// Apply integer part of the exponent using bit shifting
		// 2^(q + x) = 2^q * 2^x
		if (q > 0) {
			// If q is huge, clamp it to avoid hanging
			int shift = (int) Math.min(q, 256);
			result = result.shiftLeft(shift);
		} else if (q < 0) {
			int shift = (int) Math.min(-q, 256);
			result = result.shiftRight(shift);
		}

		if (result.equals(BigInteger.ZERO)) {
			return BigInteger.ONE;
		}

		return result;
	}

	public static BigInteger calculateTargetFromDifficulty(@NonNull BigInteger difficulty) {
		if (difficulty.signum() <= 0) {
			return MAX_TARGET;
		}
		return MAX_TARGET.divide(difficulty);
	}
}