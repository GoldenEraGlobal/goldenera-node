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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.state.NetworkParamsState;

class SnapshotSafetyLagCalculatorTest {

	private final SnapshotSafetyLagCalculator calculator = new SnapshotSafetyLagCalculator();

	@Test
	void derives2880BlocksAtThirtySeconds() {
		assertThat(calculator.lagBlocks(30_000, 0)).isEqualTo(2_880);
		assertThat(calculator.snapshotHeight(10_000, 30_000, 0)).isEqualTo(7_120);
	}

	@Test
	void roundsNonDivisibleIntervalsUpAndOverrideCanOnlyIncreaseLag() {
		assertThat(calculator.lagBlocks(35_000, 0)).isEqualTo(2_469);
		assertThat(calculator.lagBlocks(35_000, 3_000)).isEqualTo(3_000);
		assertThat(calculator.lagBlocks(35_000, 2_000)).isEqualTo(2_469);
	}

	@Test
	void followsCanonicalNetworkParameterChangesAndRejectsInvalidInputs() {
		NetworkParamsState thirtySeconds = mock(NetworkParamsState.class);
		NetworkParamsState sixtySeconds = mock(NetworkParamsState.class);
		when(thirtySeconds.getTargetMiningTimeMs()).thenReturn(30_000L);
		when(sixtySeconds.getTargetMiningTimeMs()).thenReturn(60_000L);
		assertThat(calculator.lagBlocks(thirtySeconds, 0)).isEqualTo(2_880);
		assertThat(calculator.lagBlocks(sixtySeconds, 0)).isEqualTo(1_440);
		assertThatThrownBy(() -> calculator.lagBlocks(0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.lagBlocks(30_000, -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.snapshotHeight(100, 30_000, 0))
				.isInstanceOf(IllegalStateException.class);
	}
}
