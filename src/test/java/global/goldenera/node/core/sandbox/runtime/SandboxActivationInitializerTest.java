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
package global.goldenera.node.core.sandbox.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;

class SandboxActivationInitializerTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void registersValidatedSandboxRuntimeAndManifestContexts() throws Exception {
		Path manifest = copyValidManifest();
		MockEnvironment environment = new MockEnvironment()
				.withProperty(SandboxActivationInitializer.EXECUTION_DOMAIN_PROPERTY, "SANDBOX")
				.withProperty("ge.general.network", "TESTNET")
				.withProperty("ge.core.directory.disable", "true")
				.withProperty("ge.stress-test.enabled", "false")
				.withProperty(SandboxActivationInitializer.MANIFEST_PATH_PROPERTY, manifest.toString());
		environment.setActiveProfiles("sandbox");
		GenericApplicationContext applicationContext = context(environment);

		new SandboxActivationInitializer().initialize(applicationContext);

		SandboxRuntimeContext runtime = applicationContext.getBeanFactory().getBean(SandboxRuntimeContext.class);
		SandboxManifestContext manifestContext = applicationContext.getBeanFactory()
				.getBean(SandboxManifestContext.class);
		assertThat(runtime.isSandbox()).isTrue();
		assertThat(runtime.manifestContext()).containsSame(manifestContext);
	}

	@Test
	void productionDefaultDoesNotCreateManifestContext() {
		MockEnvironment environment = new MockEnvironment().withProperty("ge.general.network", "MAINNET");
		GenericApplicationContext applicationContext = context(environment);

		new SandboxActivationInitializer().initialize(applicationContext);

		SandboxRuntimeContext runtime = applicationContext.getBeanFactory().getBean(SandboxRuntimeContext.class);
		assertThat(runtime.executionDomain()).isEqualTo(ExecutionDomain.PRODUCTION);
		assertThat(applicationContext.getBeanFactory().getBeanNamesForType(SandboxManifestContext.class)).isEmpty();
	}

	@Test
	void rejectsStaleTokenFileWhenControlApiIsDisabledAndAllControlOptionsInProduction() throws Exception {
		Path manifest = copyValidManifest();
		MockEnvironment sandboxEnvironment = new MockEnvironment()
				.withProperty(SandboxActivationInitializer.EXECUTION_DOMAIN_PROPERTY, "SANDBOX")
				.withProperty("ge.general.network", "TESTNET")
				.withProperty("ge.core.directory.disable", "true")
				.withProperty(SandboxActivationInitializer.MANIFEST_PATH_PROPERTY, manifest.toString())
				.withProperty("ge.sandbox.control-api.enabled", "false")
				.withProperty("ge.sandbox.control-api.token-file", "/run/secrets/control-token");
		sandboxEnvironment.setActiveProfiles("sandbox");

		assertThatThrownBy(() -> new SandboxActivationInitializer().initialize(context(sandboxEnvironment)))
				.isInstanceOf(SandboxActivationException.class)
				.hasMessageContaining("requires ge.sandbox.control-api.enabled=true");

		MockEnvironment productionEnvironment = new MockEnvironment()
				.withProperty("ge.general.network", "MAINNET")
				.withProperty("ge.sandbox.control-api.enabled", "true");
		assertThatThrownBy(() -> new SandboxActivationInitializer().initialize(context(productionEnvironment)))
				.isInstanceOf(SandboxActivationException.class)
				.hasMessageContaining("forbidden in production");
	}

	@Test
	void sandboxProfileAloneCannotActivateSandbox() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("ge.general.network", "TESTNET")
				.withProperty("ge.core.directory.disable", "true");
		environment.setActiveProfiles("sandbox");
		GenericApplicationContext applicationContext = context(environment);

		assertThatThrownBy(() -> new SandboxActivationInitializer().initialize(applicationContext))
				.isInstanceOf(SandboxActivationException.class)
				.hasMessageContaining("PRODUCTION execution domain");
	}

	@Test
	void springBootDiscoversInitializerOutsideApplicationMain() {
		List<ApplicationContextInitializer> initializers = SpringFactoriesLoader.loadFactories(
				ApplicationContextInitializer.class, getClass().getClassLoader());

		assertThat(initializers).anyMatch(SandboxActivationInitializer.class::isInstance);
	}

	private GenericApplicationContext context(MockEnvironment environment) {
		GenericApplicationContext applicationContext = new GenericApplicationContext();
		applicationContext.setEnvironment(environment);
		return applicationContext;
	}

	private Path copyValidManifest() throws IOException {
		try (InputStream input = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(input).isNotNull();
			Path target = temporaryDirectory.resolve("manifest.json");
			Files.copy(input, target);
			return target;
		}
	}
}
