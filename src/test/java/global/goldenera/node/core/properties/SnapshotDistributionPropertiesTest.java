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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.enums.Network;

class SnapshotDistributionPropertiesTest {

	@Test
	void unboundPropertiesFailClosedAndUseOnlyFrozenHttpsSources() {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();

		properties.validate();
		assertThat(properties.isBootstrapEnabled()).isFalse();
		assertThat(properties.isPublishEnabled()).isFalse();
		assertThat(properties.getMaxConcurrentStreams()).isEqualTo(8);
		assertThat(properties.getResumeCacheMaxEntries()).isEqualTo(8);
		assertThat(properties.getPublishCycle()).isEqualTo(Duration.ofHours(24));
		assertThat(properties.getPublishMinimumLagBlocks()).isZero();
		assertThat(properties.trustedSources(Network.TESTNET))
				.containsExactly(URI.create("https://node-eu1.geram1.com/"));
		assertThat(properties.trustedSources(Network.MAINNET))
				.containsExactly(
						URI.create("https://node-eu2.goldenera.global/"),
						URI.create("https://node-eu1.goldenera.global/"),
						URI.create("https://node-us1.goldenera.global/"),
						URI.create("https://node-me1.goldenera.global/"),
						URI.create("https://node-asia1.goldenera.global/"));
	}

	@Test
	void automaticPublicationCannotReduceDailyCycleOrSafetyLagOverride() {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishCycle(Duration.ofHours(23));

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cadence/backoff");

		properties.setPublishCycle(Duration.ofHours(24));
		properties.setPublishMinimumLagBlocks(-1);
		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cadence/backoff");
	}

	@Test
	void automaticPublishingRequiresAnExplicitHttpsOrigin() {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setPublishEnabled(true);
		properties.setPublishDirectory(Path.of("/tmp/snapshots"));

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("public origin");

		properties.setPublishPublicOrigin(URI.create("https://snapshots.example.test/"));
		properties.validate();
	}

	@Test
	void rejectsConfiguredLimitsAboveHardSafetyCaps() {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setParallelism(SnapshotDistributionProperties.HARD_MAX_PARALLELISM + 1);

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("hard safety limits");
	}

	@Test
	void rejectsStreamLimitBelowBootstrapParallelism() {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setParallelism(3);
		properties.setMaxConcurrentStreams(2);

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least the bootstrap download parallelism");
	}
}
