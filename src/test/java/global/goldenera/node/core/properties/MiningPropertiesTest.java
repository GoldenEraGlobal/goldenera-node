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

import org.junit.jupiter.api.Test;

class MiningPropertiesTest {

	@Test
	void fullModePreservesConfiguredAndAutomaticWorkerCounts() {
		MiningProperties properties = properties(RandomXMiningMemoryMode.FULL, 12);

		assertThat(properties.resolveHashingThreads(16)).isEqualTo(12);

		properties.setHashingThreads(-1);
		assertThat(properties.resolveHashingThreads(16)).isEqualTo(14);
	}

	@Test
	void lightModeCapsConfiguredAndAutomaticWorkerCounts() {
		MiningProperties properties = properties(RandomXMiningMemoryMode.LIGHT, 100);

		assertThat(properties.resolveHashingThreads(128))
				.isEqualTo(MiningProperties.MAX_LIGHT_HASHING_THREADS);

		properties.setHashingThreads(-1);
		assertThat(properties.resolveHashingThreads(128))
				.isEqualTo(MiningProperties.MAX_LIGHT_HASHING_THREADS);
		assertThat(properties.resolveHashingThreads(2)).isEqualTo(1);
	}

	private MiningProperties properties(RandomXMiningMemoryMode mode, int hashingThreads) {
		MiningProperties properties = new MiningProperties();
		properties.setEnable(true);
		properties.setMemoryMode(mode);
		properties.setHashingThreads(hashingThreads);
		return properties;
	}
}
