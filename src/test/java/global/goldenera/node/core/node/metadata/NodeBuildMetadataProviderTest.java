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
package global.goldenera.node.core.node.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class NodeBuildMetadataProviderTest {

	@Test
	void springSelectsTheEnvironmentConstructor() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.register(NodeBuildMetadataProvider.class);
			context.refresh();

			assertThat(context.getBean(NodeBuildMetadataProvider.class).metadata()).isNotNull();
		}
	}

	@Test
	void developmentMetadataMayExplicitlyReportUnknown() {
		NodeBuildMetadataProvider provider = new NodeBuildMetadataProvider(new Properties(), false);

		assertThat(provider.metadata().applicationVersion()).isEqualTo(NodeBuildMetadataProvider.UNKNOWN);
		assertThat(provider.metadata().gitCommit()).isEqualTo(NodeBuildMetadataProvider.UNKNOWN);
		assertThat(provider.metadata().cryptoJSha256()).isEqualTo(NodeBuildMetadataProvider.UNKNOWN);
		assertThat(provider.metadata().randomXSourceCommit()).isEqualTo(NodeBuildMetadataProvider.UNKNOWN);
	}

	@Test
	void releaseMetadataFailsClosedWhenAnyRequiredValueIsUnknown() {
		Properties properties = completeProperties();
		properties.setProperty("git.commit", "UNKNOWN");

		assertThatThrownBy(() -> new NodeBuildMetadataProvider(properties, true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("git.commit");
	}

	@Test
	void releaseMetadataAcceptsCompleteSuppliedValues() {
		NodeBuildMetadata metadata = new NodeBuildMetadataProvider(completeProperties(), true).metadata();

		assertThat(metadata.applicationVersion()).isEqualTo("1.2.3");
		assertThat(metadata.gitCommit()).isEqualTo("a".repeat(40));
		assertThat(metadata.cryptoJVersion()).isEqualTo("4.5.6");
		assertThat(metadata.cryptoJSha256()).isEqualTo("b".repeat(64));
		assertThat(metadata.randomXSourceCommit()).isEqualTo("c".repeat(40));
	}

	@Test
	void activeReleaseProfileFailsClosedAgainstDevelopmentArtifactMetadata() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("release");

		assertThatThrownBy(() -> new NodeBuildMetadataProvider(environment))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Release build metadata");
	}

	@Test
	void releaseRejectsChecksumThatIsNotExactLowercaseSha256() {
		Properties properties = completeProperties();
		properties.setProperty("cryptoj.sha256", "B".repeat(64));

		assertThatThrownBy(() -> new NodeBuildMetadataProvider(properties, true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("exact lowercase SHA-256");
	}

	@Test
	void releaseRejectsRandomXSourceCommitThatIsNotExactLowercaseCommit() {
		Properties properties = completeProperties();
		properties.setProperty("randomx.source.commit", "C".repeat(40));

		assertThatThrownBy(() -> new NodeBuildMetadataProvider(properties, true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("RandomX source commit");
	}

	@Test
	void filteredReleaseArtifactFlagForcesValidationWithoutARuntimeProfile() {
		Properties properties = completeProperties();
		properties.setProperty("release.metadata.required", "true");
		properties.setProperty("git.commit", "UNKNOWN");

		assertThatThrownBy(() -> new NodeBuildMetadataProvider(properties, new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("git.commit");
	}

	private Properties completeProperties() {
		Properties properties = new Properties();
		properties.setProperty("app.version", "1.2.3");
		properties.setProperty("git.commit", "a".repeat(40));
		properties.setProperty("cryptoj.version", "4.5.6");
		properties.setProperty("cryptoj.sha256", "b".repeat(64));
		properties.setProperty("randomx.source.commit", "c".repeat(40));
		return properties;
	}
}
