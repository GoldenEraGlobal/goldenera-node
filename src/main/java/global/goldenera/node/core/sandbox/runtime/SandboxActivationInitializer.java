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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;

/** Resolves and validates sandbox activation before any application beans run. */
public final class SandboxActivationInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	public static final String EXECUTION_DOMAIN_PROPERTY = "ge.runtime.execution-domain";
	public static final String MANIFEST_PATH_PROPERTY = "ge.sandbox.manifest-path";
	public static final String CONTROL_API_ENABLED_PROPERTY = "ge.sandbox.control-api.enabled";
	public static final String CONTROL_API_TOKEN_FILE_PROPERTY = "ge.sandbox.control-api.token-file";

	private static final String RUNTIME_CONTEXT_BEAN = "sandboxRuntimeContext";
	private static final String MANIFEST_CONTEXT_BEAN = "sandboxManifestContext";

	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		ConfigurableEnvironment environment = applicationContext.getEnvironment();
		SandboxRuntimeContext runtimeContext = new SandboxActivationGuard(new SandboxManifestLoader())
				.activate(request(environment));

		applicationContext.getBeanFactory().registerSingleton(RUNTIME_CONTEXT_BEAN, runtimeContext);
		runtimeContext.manifestContext().ifPresent(manifest ->
				applicationContext.getBeanFactory().registerSingleton(MANIFEST_CONTEXT_BEAN, manifest));
	}

	SandboxActivationRequest request(ConfigurableEnvironment environment) {
		Binder binder = Binder.get(environment);
		try {
			ExecutionDomain executionDomain = binder.bind(EXECUTION_DOMAIN_PROPERTY, ExecutionDomain.class)
					.orElse(ExecutionDomain.PRODUCTION);
			Network network = binder.bind("ge.general.network", Network.class)
					.orElseThrow(() -> new SandboxActivationException("ge.general.network must be configured"));
			boolean directoryDisabled = binder.bind("ge.core.directory.disable", Boolean.class).orElse(false);
			boolean stressTestEnabled = binder.bind("ge.stress-test.enabled", Boolean.class).orElse(false);
			Path manifestPath = manifestPath(binder.bind(MANIFEST_PATH_PROPERTY, String.class).orElse(null));
			boolean controlApiEnabled = binder.bind(CONTROL_API_ENABLED_PROPERTY, Boolean.class).orElse(false);
			String controlTokenFile = binder.bind(CONTROL_API_TOKEN_FILE_PROPERTY, String.class).orElse(null);
			if (controlTokenFile != null && !controlTokenFile.isBlank() && !controlApiEnabled) {
				throw new SandboxActivationException(
						"Sandbox control token file requires ge.sandbox.control-api.enabled=true");
			}
			Set<String> activeProfiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));

			return new SandboxActivationRequest(executionDomain, activeProfiles, network, directoryDisabled,
					stressTestEnabled, manifestPath, configuredSandboxOptions(environment));
		} catch (BindException e) {
			throw new SandboxActivationException("Invalid sandbox activation configuration: " + e.getMessage(), e);
		}
	}

	private Path manifestPath(String configuredPath) {
		if (configuredPath == null || configuredPath.isBlank()) {
			return null;
		}
		try {
			return Path.of(configuredPath);
		} catch (InvalidPathException e) {
			throw new SandboxActivationException("Invalid sandbox manifest path", e);
		}
	}

	private Set<String> configuredSandboxOptions(ConfigurableEnvironment environment) {
		Set<String> configured = new LinkedHashSet<>();
		for (PropertySource<?> propertySource : environment.getPropertySources()) {
			if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
				continue;
			}
			for (String propertyName : enumerable.getPropertyNames()) {
				String normalized = normalize(propertyName);
				if (normalized.startsWith("gesandbox") && propertySource.getProperty(propertyName) != null) {
					configured.add(normalized);
				}
			}
		}
		return Set.copyOf(configured);
	}

	private String normalize(String propertyName) {
		return propertyName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
