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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RandomXVerificationPropertiesTest {

	@Test
	void zeroConfigurationScalesToCpuWithinTheHardBound() {
		RandomXVerificationProperties properties = new RandomXVerificationProperties();

		assertThat(properties.getVerificationMode()).isEqualTo(RandomXSyncVerificationMode.AUTO);
		assertThat(properties.getBulkEnterGap()).isEqualTo(4096);
		assertThat(properties.getTailExitGap()).isEqualTo(1000);
		assertThat(properties.getMinimumExpectedHashes()).isEqualTo(6144);
		assertThat(properties.resolveParallelism(4)).isEqualTo(4);
		assertThat(properties.resolveParallelism(8)).isEqualTo(8);
		assertThat(properties.resolveParallelism(16)).isEqualTo(16);
		assertThat(properties.resolveParallelism(32)).isEqualTo(16);
		assertThat(properties.resolveFullDatasetParallelism(4)).isEqualTo(3);
		assertThat(properties.resolveFullDatasetParallelism(8)).isEqualTo(6);
		assertThat(properties.resolveFullDatasetParallelism(16)).isEqualTo(8);
	}

	@Test
	void explicitParallelismRemainsCpuAndHardCapBounded() {
		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		properties.setParallelism(6);
		properties.setMaxParallelism(8);

		assertThat(properties.resolveParallelism(4)).isEqualTo(4);
		assertThat(properties.resolveParallelism(16)).isEqualTo(6);
	}

	@Test
	void invalidBoundsFailClosed() {
		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		properties.setParallelism(9);
		properties.setMaxParallelism(8);

		assertThatThrownBy(() -> properties.resolveParallelism(16))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("hard maximum");
	}

	@Test
	void invalidGapAndMemoryPoliciesFailClosed() {
		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		properties.setTailExitGap(4096);

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("hysteresis");

		properties.setTailExitGap(1000);
		properties.setAutoPreferredMemoryMib(1024);
		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("memory thresholds");

		properties.setAutoPreferredMemoryMib(16 * 1024);
		properties.setMinimumExpectedHashes(6143);
		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("amortization");
	}
}
