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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.node.core.properties.RandomXSyncVerificationMode;
import global.goldenera.node.core.properties.RandomXVerificationProperties;

class RandomXSyncMemoryPolicyTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void autoFailsClosedBelowSafeMemoryAndWhenHeapConsumesNativeHeadroom() {
		assertThat(decide(snapshot(8192, 6000, 1024), false, false, false, 1000).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
		assertThat(decide(snapshot(12288, 6000, 8192), false, false, false, 1000).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
	}

	@Test
	void autoUsesTwelveGibForCoreOnlyAndSixteenGibWithExplorerOrMining() {
		assertThat(decide(snapshot(12288, 6000, 4096), false, false, false, 7000).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.BUILD_DATASET);
		assertThat(decide(snapshot(12288, 6000, 4096), true, false, false, 7000).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
		assertThat(decide(snapshot(16384, 7000, 4096), true, true, false, 7000).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.BUILD_DATASET);
	}

	@Test
	void matchingMiningDatasetIsReusableWithoutAllocationButForcedLightWins() {
		assertThat(decide(snapshot(4096, 1000, 2048), true, true, true, 1).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.REUSE_EXISTING);

		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		properties.setVerificationMode(RandomXSyncVerificationMode.LIGHT);
		RandomXSyncMemoryPolicy policy = new RandomXSyncMemoryPolicy(properties,
				() -> snapshot(32768, 20000, 4096));
		assertThat(policy.decide(10_000, false, false, true).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
	}

	@Test
	void expectedHashThresholdAndUnknownMemoryPreventDatasetBuild() {
		assertThat(decide(snapshot(32768, 20000, 4096), false, false, false, 1024).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
		assertThat(decide(new RandomXSyncMemoryPolicy.MemorySnapshot(0, 0, 0, false),
				false, false, false, 7000).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
	}

	@Test
	void fourCpuEconomicsKeepTestnetAndLateMainnetEpochsLight() {
		RandomXSyncMemoryPolicy.MemorySnapshot memory = snapshot(32768, 20000, 4096);
		assertThat(decide(memory, false, false, false, 1024).outcome())
				.as("TESTNET cannot amortize a dataset inside one 1024-block seed epoch")
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
		assertThat(decide(memory, false, false, false, 6143).outcome())
				.as("late MAINNET epoch defers until the next complete seed epoch")
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
		assertThat(decide(memory, false, false, false, 6144).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.BUILD_DATASET);
	}

	@Test
	void explicitFullDatasetModeCannotBypassAmortizationFloor() {
		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		properties.setVerificationMode(RandomXSyncVerificationMode.FULL_DATASET);
		RandomXSyncMemoryPolicy policy = new RandomXSyncMemoryPolicy(properties,
				() -> snapshot(32768, 20000, 4096));

		assertThat(policy.decide(1024, false, false, false).outcome())
				.isEqualTo(RandomXSyncMemoryPolicy.Outcome.LIGHT);
	}

	@Test
	void cgroupV2ExhaustedMissingAndMalformedUsageNeverFallsBackToHostAvailability() throws Exception {
		ProbeFiles files = probeFiles();
		Files.writeString(files.v2Limit(), bytes(16 * 1024));

		Files.writeString(files.v2Current(), bytes(16 * 1024));
		assertUnknownAndUnavailable(files.probe().snapshot());

		Files.delete(files.v2Current());
		assertUnknownAndUnavailable(files.probe().snapshot());

		Files.writeString(files.v2Current(), "malformed");
		assertUnknownAndUnavailable(files.probe().snapshot());
	}

	@Test
	void cgroupV1ExhaustedMissingAndMalformedUsageNeverFallsBackToHostAvailability() throws Exception {
		ProbeFiles files = probeFiles();
		Files.writeString(files.v1Limit(), bytes(16 * 1024));

		Files.writeString(files.v1Current(), bytes(16 * 1024));
		assertUnknownAndUnavailable(files.probe().snapshot());

		Files.delete(files.v1Current());
		assertUnknownAndUnavailable(files.probe().snapshot());

		Files.writeString(files.v1Current(), "malformed");
		assertUnknownAndUnavailable(files.probe().snapshot());
	}

	private RandomXSyncMemoryPolicy.Decision decide(
			RandomXSyncMemoryPolicy.MemorySnapshot snapshot,
			boolean explorer,
			boolean mining,
			boolean matching,
			long hashes) {
		RandomXVerificationProperties properties = new RandomXVerificationProperties();
		return new RandomXSyncMemoryPolicy(properties, () -> snapshot)
				.decide(hashes, explorer, mining, matching);
	}

	private RandomXSyncMemoryPolicy.MemorySnapshot snapshot(long effective, long available, long heap) {
		return new RandomXSyncMemoryPolicy.MemorySnapshot(effective, available, heap, true);
	}

	private ProbeFiles probeFiles() throws Exception {
		Path meminfo = temporaryDirectory.resolve("meminfo");
		Path v2Limit = temporaryDirectory.resolve("memory.max");
		Path v2Current = temporaryDirectory.resolve("memory.current");
		Path v1Limit = temporaryDirectory.resolve("memory.limit_in_bytes");
		Path v1Current = temporaryDirectory.resolve("memory.usage_in_bytes");
		Files.writeString(meminfo, "MemTotal: 33554432 kB\nMemAvailable: 25165824 kB\n");
		return new ProbeFiles(
				new RandomXSyncMemoryPolicy.SystemMemoryProbe(
						meminfo, v2Limit, v2Current, v1Limit, v1Current),
				v2Limit, v2Current, v1Limit, v1Current);
	}

	private void assertUnknownAndUnavailable(RandomXSyncMemoryPolicy.MemorySnapshot snapshot) {
		assertThat(snapshot.known()).isFalse();
		assertThat(snapshot.availableMemoryMib()).isZero();
	}

	private String bytes(long mib) {
		return Long.toString(Math.multiplyExact(mib, 1024L * 1024));
	}

	private record ProbeFiles(
			RandomXSyncMemoryPolicy.SystemMemoryProbe probe,
			Path v2Limit,
			Path v2Current,
			Path v1Limit,
			Path v1Current) {
	}
}
