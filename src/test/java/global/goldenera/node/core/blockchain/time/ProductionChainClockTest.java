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
package global.goldenera.node.core.blockchain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;

class ProductionChainClockTest {

	private static final Instant NOW = Instant.parse("2026-08-14T10:15:30.789Z");

	@Test
	void productionTimestampPreservesSecondTruncationAndParentClamp() {
		ProductionChainClock clock = clock();

		assertThat(clock.nextBlockTimestamp(parent(10, NOW.minusSeconds(10))))
				.isEqualTo(Instant.parse("2026-08-14T10:15:30Z"));
		assertThat(clock.nextBlockTimestamp(parent(10, NOW.plusMillis(211))))
				.isEqualTo(NOW.plusMillis(212));
	}

	@Test
	void admissionTimestampPreservesExactWallClockAndParentClamp() {
		ProductionChainClock clock = clock();

		assertThat(clock.earliestNextBlockTimestamp(parent(10, NOW.minusSeconds(10))))
				.isEqualTo(NOW);
		assertThat(clock.earliestNextBlockTimestamp(parent(10, NOW.plusMillis(211))))
				.isEqualTo(NOW.plusMillis(212));
	}

	@Test
	void productionValidationPreservesWallClockFutureDriftRule() {
		ProductionChainClock clock = clock();
		BlockHeader parent = parent(10, NOW.minusSeconds(1));
		BlockHeader boundary = child(11, NOW.plusMillis(2_000));

		clock.validateBlockTimestamp(boundary, parent, 2_000);

		assertThatThrownBy(() -> clock.validateBlockTimestamp(child(11, NOW.plusMillis(2_001)), parent, 2_000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Timestamp too far in future");
		assertThatThrownBy(() -> clock.validateBlockTimestamp(child(11, parent.getTimestamp()), parent, 2_000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Timestamp invalid");
	}

	@Test
	void chainClockContractExposesNoGlobalSchedulingMutation() {
		assertThat(ChainClock.class.getDeclaredMethods())
				.extracting(Method::getName)
				.doesNotContain("scheduleNextBlockTimestamp");
	}

	@Test
	void productionReservationRejectsExplicitScheduling() {
		ProductionChainClock clock = clock();
		assertThatThrownBy(() -> clock.reserveNextBlockTimestamp(
				parent(10, NOW.minusSeconds(1)), Optional.of(NOW)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("deterministic sandbox");
	}

	private ProductionChainClock clock() {
		return new ProductionChainClock(Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private BlockHeader parent(long height, Instant timestamp) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getHeight()).thenReturn(height);
		when(header.getTimestamp()).thenReturn(timestamp);
		return header;
	}

	private BlockHeader child(long height, Instant timestamp) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getHeight()).thenReturn(height);
		when(header.getTimestamp()).thenReturn(timestamp);
		return header;
	}
}
