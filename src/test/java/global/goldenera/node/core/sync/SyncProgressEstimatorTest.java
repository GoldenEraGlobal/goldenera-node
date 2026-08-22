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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import global.goldenera.node.core.sync.SyncProgressEstimator.Estimate;
import global.goldenera.node.core.sync.SyncProgressEstimator.EtaStatus;

class SyncProgressEstimatorTest {

	@Test
	void firstEndToEndSampleProducesRealRateAndEta() {
		SyncProgressEstimator estimator = new SyncProgressEstimator();

		Estimate estimate = estimator.recordCycle(true, 1_000, TimeUnit.MILLISECONDS.toNanos(12_576),
				2_000, 716_769).orElseThrow();

		assertThat(estimate.effectiveBlocksPerSecond()).isCloseTo(79.5165d, within(0.0001d));
		assertThat(estimate.remainingBlocks()).isEqualTo(714_769);
		assertThat(estimate.estimatedRemainingSeconds()).isCloseTo(8_988.93d, within(0.01d));
		assertThat(SyncProgressEstimator.formatEta(estimate)).isEqualTo("2h 30m");
	}

	@Test
	void ewmaDampensRandomXValidationOutlier() {
		SyncProgressEstimator estimator = new SyncProgressEstimator();

		Estimate baseline = estimator.recordCycle(true, 1_000, TimeUnit.SECONDS.toNanos(10),
				1_000, 10_000).orElseThrow();
		Estimate outlier = estimator.recordCycle(true, 1_000, TimeUnit.SECONDS.toNanos(100),
				2_000, 10_000).orElseThrow();
		Estimate recovered = estimator.recordCycle(true, 1_000, TimeUnit.SECONDS.toNanos(10),
				3_000, 10_000).orElseThrow();

		assertThat(baseline.effectiveBlocksPerSecond()).isEqualTo(100.0d);
		assertThat(outlier.effectiveBlocksPerSecond()).isEqualTo(82.0d);
		assertThat(recovered.effectiveBlocksPerSecond()).isCloseTo(85.6d, within(0.0001d));
	}

	@Test
	void unknownReorgAndCompletionDoNotInventForwardRate() {
		SyncProgressEstimator estimator = new SyncProgressEstimator();

		Estimate unknown = estimator.recordCycle(true, 0, TimeUnit.SECONDS.toNanos(1),
				-1, 10_000).orElseThrow();
		Estimate shorterHigherWorkReorg = estimator.recordCycle(true, 0, TimeUnit.SECONDS.toNanos(5),
				900, 1_000).orElseThrow();
		Estimate complete = estimator.recordCycle(true, 0, TimeUnit.SECONDS.toNanos(1),
				1_001, 1_000).orElseThrow();

		assertThat(unknown.etaStatus()).isEqualTo(EtaStatus.UNKNOWN);
		assertThat(shorterHigherWorkReorg.etaStatus()).isEqualTo(EtaStatus.UNKNOWN);
		assertThat(shorterHigherWorkReorg.remainingBlocks()).isEqualTo(100);
		assertThat(complete.etaStatus()).isEqualTo(EtaStatus.COMPLETE);
		assertThat(SyncProgressEstimator.formatEta(complete)).isEqualTo("complete");
		assertThat(estimator.telemetry().effectiveBlocksPerSecond())
				.isEqualTo(SyncProgressEstimator.UNAVAILABLE_GAUGE_VALUE);
		assertThat(estimator.telemetry().estimatedRemainingSeconds()).isZero();
	}

	@Test
	void currentTargetDrivesRemainingEstimateWithoutResettingRate() {
		SyncProgressEstimator estimator = new SyncProgressEstimator();
		estimator.recordCycle(true, 1_000, TimeUnit.SECONDS.toNanos(10), 1_000, 3_000);

		Estimate changedTarget = estimator.recordCycle(true, 0, TimeUnit.SECONDS.toNanos(2),
				1_000, 5_000).orElseThrow();

		assertThat(changedTarget.remainingBlocks()).isEqualTo(4_000);
		assertThat(changedTarget.effectiveBlocksPerSecond()).isEqualTo(100.0d);
		assertThat(changedTarget.estimatedRemainingSeconds()).isEqualTo(40.0d);
	}

	@Test
	void failedCycleDoesNotUpdateRateOrEta() {
		SyncProgressEstimator estimator = new SyncProgressEstimator();
		Estimate successful = estimator.recordCycle(true, 1_000, TimeUnit.SECONDS.toNanos(10),
				1_000, 3_000).orElseThrow();

		Optional<Estimate> failed = estimator.recordCycle(false, 1_000, TimeUnit.SECONDS.toNanos(1),
				2_000, 9_000);

		assertThat(failed).isEmpty();
		assertThat(estimator.telemetry().effectiveBlocksPerSecond())
				.isEqualTo(successful.effectiveBlocksPerSecond());
		assertThat(estimator.telemetry().estimatedRemainingSeconds())
				.isEqualTo(successful.estimatedRemainingSeconds());
	}

	@Test
	void formatsMinutesHoursDaysAndBoundsInvalidDurations() {
		assertThat(SyncProgressEstimator.formatEtaSeconds(0)).isEqualTo("complete");
		assertThat(SyncProgressEstimator.formatEtaSeconds(20)).isEqualTo("<1m");
		assertThat(SyncProgressEstimator.formatEtaSeconds(61)).isEqualTo("2m");
		assertThat(SyncProgressEstimator.formatEtaSeconds(3_601)).isEqualTo("1h 1m");
		assertThat(SyncProgressEstimator.formatEtaSeconds(90_061)).isEqualTo("1d 1h 2m");
		assertThat(SyncProgressEstimator.formatEtaSeconds(Double.POSITIVE_INFINITY)).isEqualTo("unknown");
		assertThat(SyncProgressEstimator.formatEtaSeconds(SyncProgressEstimator.MAX_ETA_SECONDS + 1.0d))
				.isEqualTo(">36500d");
	}

	@Test
	void extremelyLongFiniteEstimateUsesBoundedUnavailableSentinel() {
		SyncProgressEstimator estimator = new SyncProgressEstimator();

		Estimate estimate = estimator.recordCycle(true, 1, Long.MAX_VALUE,
				0, Long.MAX_VALUE).orElseThrow();

		assertThat(estimate.etaStatus()).isEqualTo(EtaStatus.TOO_LONG);
		assertThat(SyncProgressEstimator.formatEta(estimate)).isEqualTo(">36500d");
		assertThat(estimator.telemetry().estimatedRemainingSeconds())
				.isEqualTo(SyncProgressEstimator.UNAVAILABLE_GAUGE_VALUE);
	}

	private static Offset<Double> within(double value) {
		return Offset.offset(value);
	}
}
