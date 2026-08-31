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
package global.goldenera.node.core.sync;

import java.util.Locale;
import java.util.Optional;

/**
 * Thread-safe end-to-end sync rate and ETA estimator.
 *
 * <p>The effective rate uses an EWMA with alpha {@value #EWMA_ALPHA}. A low
 * alpha deliberately dampens occasional RandomX initialization and validation
 * outliers while still adapting as block density changes.</p>
 */
final class SyncProgressEstimator {

	static final double EWMA_ALPHA = 0.20d;
	static final double UNAVAILABLE_GAUGE_VALUE = -1.0d;
	static final long MAX_ETA_SECONDS = 100L * 365 * 24 * 60 * 60;

	private double effectiveBlocksPerSecond = Double.NaN;
	private double estimatedRemainingSeconds = Double.NaN;

	synchronized Optional<Estimate> recordCycle(boolean successful, long blocksAdvanced,
			long elapsedNanos, long localHeight, long targetHeight) {
		if (!successful) {
			return Optional.empty();
		}

		if (blocksAdvanced > 0 && elapsedNanos > 0) {
			double sampleRate = blocksAdvanced * 1_000_000_000.0d / elapsedNanos;
			if (Double.isFinite(sampleRate) && sampleRate > 0.0d) {
				effectiveBlocksPerSecond = Double.isFinite(effectiveBlocksPerSecond)
						? EWMA_ALPHA * sampleRate + (1.0d - EWMA_ALPHA) * effectiveBlocksPerSecond
						: sampleRate;
			}
		}

		long remainingBlocks = remainingBlocks(localHeight, targetHeight);
		EtaStatus status;
		if (remainingBlocks < 0) {
			status = EtaStatus.UNKNOWN;
			estimatedRemainingSeconds = Double.NaN;
		} else if (remainingBlocks == 0) {
			status = EtaStatus.COMPLETE;
			estimatedRemainingSeconds = 0.0d;
		} else if (!Double.isFinite(effectiveBlocksPerSecond) || effectiveBlocksPerSecond <= 0.0d) {
			status = EtaStatus.UNKNOWN;
			estimatedRemainingSeconds = Double.NaN;
		} else {
			double etaSeconds = remainingBlocks / effectiveBlocksPerSecond;
			if (!Double.isFinite(etaSeconds) || etaSeconds > MAX_ETA_SECONDS) {
				status = EtaStatus.TOO_LONG;
				estimatedRemainingSeconds = Double.NaN;
			} else {
				status = EtaStatus.AVAILABLE;
				estimatedRemainingSeconds = etaSeconds;
			}
		}

		return Optional.of(new Estimate(localHeight, targetHeight, remainingBlocks,
				effectiveBlocksPerSecond, estimatedRemainingSeconds, status));
	}

	synchronized Telemetry telemetry() {
		return new Telemetry(gaugeValue(effectiveBlocksPerSecond), gaugeValue(estimatedRemainingSeconds));
	}

	static String formatRate(double blocksPerSecond) {
		if (!Double.isFinite(blocksPerSecond) || blocksPerSecond <= 0.0d) {
			return "unknown";
		}
		return String.format(Locale.ROOT, "%.1f", blocksPerSecond);
	}

	static String formatEta(Estimate estimate) {
		return switch (estimate.etaStatus()) {
		case COMPLETE -> "complete";
		case UNKNOWN -> "unknown";
		case TOO_LONG -> ">36500d";
		case AVAILABLE -> formatEtaSeconds(estimate.estimatedRemainingSeconds());
		};
	}

	static String formatEtaSeconds(double seconds) {
		if (!Double.isFinite(seconds) || seconds < 0.0d) {
			return "unknown";
		}
		if (seconds == 0.0d) {
			return "complete";
		}
		if (seconds > MAX_ETA_SECONDS) {
			return ">36500d";
		}
		if (seconds < 60.0d) {
			return "<1m";
		}

		long totalMinutes = (long) Math.ceil(seconds / 60.0d);
		long days = totalMinutes / (24 * 60);
		long hours = totalMinutes % (24 * 60) / 60;
		long minutes = totalMinutes % 60;
		if (days > 0) {
			return String.format(Locale.ROOT, "%dd %dh %dm", days, hours, minutes);
		}
		if (hours > 0) {
			return String.format(Locale.ROOT, "%dh %dm", hours, minutes);
		}
		return String.format(Locale.ROOT, "%dm", minutes);
	}

	private static long remainingBlocks(long localHeight, long targetHeight) {
		if (localHeight < 0 || targetHeight < 0) {
			return -1;
		}
		return targetHeight <= localHeight ? 0 : targetHeight - localHeight;
	}

	private static double gaugeValue(double value) {
		return Double.isFinite(value) && value >= 0.0d ? value : UNAVAILABLE_GAUGE_VALUE;
	}

	enum EtaStatus {
		AVAILABLE,
		COMPLETE,
		UNKNOWN,
		TOO_LONG
	}

	record Estimate(
			long localHeight,
			long targetHeight,
			long remainingBlocks,
			double effectiveBlocksPerSecond,
			double estimatedRemainingSeconds,
			EtaStatus etaStatus) {
	}

	record Telemetry(double effectiveBlocksPerSecond, double estimatedRemainingSeconds) {
	}
}
