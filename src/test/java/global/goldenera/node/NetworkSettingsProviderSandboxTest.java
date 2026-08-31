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
package global.goldenera.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.shared.properties.GeneralProperties;

class NetworkSettingsProviderSandboxTest {

	@TempDir
	Path temporaryDirectory;

	@BeforeEach
	void startWithoutProductionTestSettings() {
		NetworkSettingsProvider.resetForTesting();
	}

	@AfterEach
	void resetStaticProvider() {
		NetworkSettingsProvider.resetForTesting();
	}

	@Test
	void refusesClasspathFallbackBeforeSpringInitialization() {
		assertThatThrownBy(NetworkSettingsProvider::getActiveNetwork)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ge.general.network is unavailable");
		assertThatThrownBy(NetworkSettingsProvider::getActiveProfile)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Spring profiles are unavailable");
		assertThatThrownBy(NetworkSettingsProvider::getSettings)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("refusing an unverified classpath fallback");
	}

	@Test
	void sandboxSettingsComeOnlyFromValidatedManifest() throws Exception {
		SandboxManifestContext manifest = loadManifest();
		GeneralProperties general = mock(GeneralProperties.class);
		when(general.getNetwork()).thenReturn(Network.TESTNET);
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("sandbox");
		SandboxRuntimeContext runtime = new SandboxRuntimeContext(
				ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(manifest));
		NetworkSettingsProvider provider = new NetworkSettingsProvider(general, environment, runtime);

		provider.init();

		assertThat(provider.currentProfile()).isEqualTo("sandbox");
		assertThat(provider.currentSettings().genesisBlockTimestamp()).isEqualTo(1_800_000_000_000L);
		assertThat(NetworkSettingsProvider.getSettings(Network.TESTNET))
				.isSameAs(provider.currentSettings());
		assertThatThrownBy(() -> NetworkSettingsProvider.getSettings(Network.MAINNET))
				.hasMessageContaining("cannot load classpath settings");
	}

	private SandboxManifestContext loadManifest() throws IOException {
		Path manifest = temporaryDirectory.resolve("manifest.json");
		try (InputStream input = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(input).isNotNull();
			Files.copy(input, manifest);
		}
		return new SandboxManifestLoader().load(manifest);
	}
}
