/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.enums.Network;

class SnapshotDistributionPropertiesTest {

	@Test
	void unboundPropertiesFailClosedAndUseOnlyFrozenHttpsSources() {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();

		properties.validate();
		assertThat(properties.isBootstrapEnabled()).isFalse();
		assertThat(properties.isPublishEnabled()).isFalse();
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
	void rejectsConfiguredLimitsAboveHardSafetyCaps() {
		SnapshotDistributionProperties properties = new SnapshotDistributionProperties();
		properties.setParallelism(SnapshotDistributionProperties.HARD_MAX_PARALLELISM + 1);

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("hard safety limits");
	}
}
