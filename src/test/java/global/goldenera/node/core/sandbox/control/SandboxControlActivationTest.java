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
package global.goldenera.node.core.sandbox.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

class SandboxControlActivationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void acceptsOnlyExplicitEnabledDisposableManifestCapabilityConjunction() throws Exception {
		SandboxManifestContext enabledManifest = manifest(true);
		SandboxRuntimeContext sandbox = new SandboxRuntimeContext(
				ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(enabledManifest));
		SandboxControlProperties properties = properties(true);

		SandboxControlActivation activation = new SandboxControlActivationValidator().validate(sandbox, properties);

		assertThat(activation.manifestContext()).isSameAs(enabledManifest);
	}

	@Test
	void rejectsEveryIncompleteActivationConjunction() throws Exception {
		SandboxRuntimeContext production = new SandboxRuntimeContext(
				ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty());
		SandboxRuntimeContext featureDisabled = new SandboxRuntimeContext(
				ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(manifest(false)));

		assertThatThrownBy(() -> new SandboxControlActivationValidator().validate(production, properties(true)))
				.isInstanceOf(SandboxControlActivationException.class)
				.hasMessageContaining("SANDBOX execution domain");
		assertThatThrownBy(() -> new SandboxControlActivationValidator().validate(featureDisabled, properties(true)))
				.isInstanceOf(SandboxControlActivationException.class)
				.hasMessageContaining("disabled by the sandbox manifest");
		assertThatThrownBy(() -> new SandboxControlActivationValidator().validate(featureDisabled, properties(false)))
				.isInstanceOf(SandboxControlActivationException.class)
				.hasMessageContaining("explicit enable switch");
	}

	private SandboxControlProperties properties(boolean enabled) {
		SandboxControlProperties properties = new SandboxControlProperties();
		properties.setEnabled(enabled);
		properties.setTokenFile(temporaryDirectory.resolve("control.token"));
		return properties;
	}

	private SandboxManifestContext manifest(boolean controlApi) throws IOException {
		String json;
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(stream).isNotNull();
			json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		json = json.replace("\"controlApi\": false", "\"controlApi\": " + controlApi);
		Path path = temporaryDirectory.resolve("manifest-" + controlApi + ".json");
		Files.writeString(path, json, StandardCharsets.UTF_8);
		return new SandboxManifestLoader().load(path);
	}
}
