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
package global.goldenera.node.core.blockchain.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import global.goldenera.randomx.RandomXCache;
import global.goldenera.randomx.RandomXDataset;
import global.goldenera.randomx.RandomXFlag;
import global.goldenera.randomx.RandomXUtils;
import global.goldenera.randomx.RandomXVM;

/** Opt-in native vectors kept out of the ordinary unit-test resource budget. */
@Tag("resource-heavy")
class RandomXNativeParityTest {

	private static final byte[] KEY = "test key 000".getBytes(StandardCharsets.UTF_8);
	private static final byte[] INPUT = "This is a test".getBytes(StandardCharsets.UTF_8);
	private static final String EXPECTED =
			"639183aae1bf4c9a35884cb46b09cad9175f04efd7684e7262a0ac1c2f0b4e3f";

	@Test
	@EnabledIfSystemProperty(named = "goldenera.randomx.native-tests", matches = "true",
			disabledReason = "Enable with -Dgoldenera.randomx.native-tests=true on a supported native runner")
	void lightVmMatchesFixedRandomXVector() {
		Set<RandomXFlag> lightFlags = lightFlags();
		try (RandomXCache cache = new RandomXCache(lightFlags)) {
			cache.init(KEY);
			try (RandomXVM vm = new RandomXVM(lightFlags, cache, null)) {
				assertThat(HexFormat.of().formatHex(vm.calculateHash(INPUT))).isEqualTo(EXPECTED);
			}
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "goldenera.randomx.full-parity-tests", matches = "true",
			disabledReason = "Requires a release-capable runner with enough memory for the full RandomX dataset")
	void lightAndFullVmsProduceTheSameCanonicalHash() {
		Set<RandomXFlag> lightFlags = lightFlags();
		EnumSet<RandomXFlag> fullFlags = EnumSet.copyOf(lightFlags);
		fullFlags.add(RandomXFlag.FULL_MEM);

		try (RandomXCache lightCache = new RandomXCache(lightFlags);
				RandomXCache fullCache = new RandomXCache(fullFlags);
				RandomXDataset dataset = new RandomXDataset(fullFlags)) {
			lightCache.init(KEY);
			fullCache.init(KEY);
			dataset.init(fullCache);
			try (RandomXVM lightVm = new RandomXVM(lightFlags, lightCache, null);
					RandomXVM fullVm = new RandomXVM(fullFlags, fullCache, dataset)) {
				byte[] lightHash = lightVm.calculateHash(INPUT);
				byte[] fullHash = fullVm.calculateHash(INPUT);
				assertThat(fullHash).isEqualTo(lightHash);
				assertThat(HexFormat.of().formatHex(lightHash)).isEqualTo(EXPECTED);
			}
		}
	}

	private Set<RandomXFlag> lightFlags() {
		EnumSet<RandomXFlag> flags = EnumSet.copyOf(RandomXUtils.getRecommendedFlags());
		flags.remove(RandomXFlag.FULL_MEM);
		flags.remove(RandomXFlag.LARGE_PAGES);
		if (flags.isEmpty()) {
			flags.add(RandomXFlag.DEFAULT);
		}
		return flags;
	}
}
