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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;

class SandboxActivationGuardTest {

	private static final boolean[] FLAGS = {false, true};

	private final SandboxActivationGuard guard = new SandboxActivationGuard(new SandboxManifestLoader());

	@TempDir
	Path temporaryDirectory;

	@Test
	void enforcesCompleteActivationTruthTable() throws Exception {
		Path validManifest = copyValidManifest("valid.json");
		int accepted = 0;
		int rejected = 0;

		for (ExecutionDomain domain : ExecutionDomain.values()) {
			for (boolean sandboxProfile : FLAGS) {
				for (Network network : Network.values()) {
					for (boolean directoryDisabled : FLAGS) {
						for (boolean manifestConfigured : FLAGS) {
							for (boolean stressTestEnabled : FLAGS) {
								SandboxActivationRequest request = request(domain, sandboxProfile, network,
										directoryDisabled, manifestConfigured, stressTestEnabled, validManifest);
								boolean expected = expectedToActivate(domain, sandboxProfile, network,
										directoryDisabled, manifestConfigured, stressTestEnabled);
								Throwable failure = catchThrowable(() -> guard.activate(request));

								if (expected) {
									assertThat(failure).as(description(request)).isNull();
									accepted++;
								} else {
									assertThat(failure).as(description(request))
											.isInstanceOf(SandboxActivationException.class);
									rejected++;
								}
							}
						}
					}
				}
			}
		}

		assertThat(accepted).isEqualTo(9);
		assertThat(rejected).isEqualTo(55);
	}

	@Test
	void loadsManifestWithoutRequiringControlFeature() throws Exception {
		Path manifest = copyValidManifest("no-control.json");
		SandboxRuntimeContext context = guard.activate(request(ExecutionDomain.SANDBOX, true, Network.TESTNET,
				true, true, false, manifest));

		assertThat(context.isSandbox()).isTrue();
		assertThat(context.manifestContext()).isPresent();
		assertThat(context.manifestContext().orElseThrow().manifest().features().controlApi()).isFalse();
	}

	@Test
	void admitsOnlyDeclaredControlOptionsInSandboxAndRejectsThemInProduction() throws Exception {
		Path manifest = copyValidManifest("control-options.json");
		Set<String> options = Set.of(
				SandboxActivationGuard.MANIFEST_OPTION,
				SandboxActivationGuard.CONTROL_API_ENABLED_OPTION,
				SandboxActivationGuard.CONTROL_API_TOKEN_FILE_OPTION);
		SandboxActivationRequest sandbox = new SandboxActivationRequest(
				ExecutionDomain.SANDBOX, Set.of("sandbox"), Network.TESTNET,
				true, false, manifest, options);
		SandboxActivationRequest production = new SandboxActivationRequest(
				ExecutionDomain.PRODUCTION, Set.of(), Network.MAINNET,
				true, false, null, Set.of(SandboxActivationGuard.CONTROL_API_ENABLED_OPTION));

		assertThat(guard.activate(sandbox).isSandbox()).isTrue();
		assertThatThrownBy(() -> guard.activate(production))
				.isInstanceOf(SandboxActivationException.class)
				.hasMessageContaining("forbidden in production");
	}

	@Test
	void rejectsUnknownSandboxOptionsInEveryExecutionDomain() throws Exception {
		Path manifest = copyValidManifest("valid.json");
		for (ExecutionDomain domain : ExecutionDomain.values()) {
			SandboxActivationRequest request = new SandboxActivationRequest(domain,
					domain == ExecutionDomain.SANDBOX ? Set.of("sandbox") : Set.of(),
					domain == ExecutionDomain.SANDBOX ? Network.TESTNET : Network.MAINNET,
					true, false, domain == ExecutionDomain.SANDBOX ? manifest : null,
					Set.of("gesandboxfutureunsafeoption"));

			assertThatThrownBy(() -> guard.activate(request))
					.as("domain=%s", domain)
					.isInstanceOf(SandboxActivationException.class);
		}
	}

	@Test
	void rejectsSandboxCombinedWithAnotherProfile() throws Exception {
		Path manifest = copyValidManifest("valid.json");
		SandboxActivationRequest request = new SandboxActivationRequest(ExecutionDomain.SANDBOX,
				Set.of("sandbox", "dev"), Network.TESTNET, true, false, manifest,
				Set.of(SandboxActivationGuard.MANIFEST_OPTION));

		assertThatThrownBy(() -> guard.activate(request))
				.isInstanceOf(SandboxActivationException.class)
				.hasMessageContaining("only active Spring profile");
	}

	@Test
	void rejectsKnownProductionGenesisHash() throws Exception {
		Path manifest = copyValidManifest("production-genesis.json");
		String json = Files.readString(manifest).replace(
				"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f");
		Files.writeString(manifest, json);

		assertThatThrownBy(() -> guard.activate(request(ExecutionDomain.SANDBOX, true, Network.TESTNET,
				true, true, false, manifest)))
				.isInstanceOf(SandboxActivationException.class)
				.hasMessageContaining("known production genesis hash");
	}

	@Test
	void runtimeContextIndependentlyRejectsSandboxOnMainnetCarrier() throws Exception {
		SandboxRuntimeContext loaded = guard.activate(request(ExecutionDomain.SANDBOX, true, Network.TESTNET,
				true, true, false, copyValidManifest("valid.json")));

		assertThatThrownBy(() -> new SandboxRuntimeContext(ExecutionDomain.SANDBOX, Network.MAINNET,
				Optional.of(loaded.manifestContext().orElseThrow())))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("TESTNET");
	}

	@Test
	void runtimeContextIndependentlyRejectsKnownProductionGenesis() throws Exception {
		Path manifest = copyValidManifest("production-genesis-context.json");
		String json = Files.readString(manifest).replace(
				"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				"0xf403f287a52b794eba7645d193c53c2dfa084a52db11ad94d70d0c79107c05cc");
		Files.writeString(manifest, json);

		assertThatThrownBy(() -> new SandboxRuntimeContext(ExecutionDomain.SANDBOX, Network.TESTNET,
				Optional.of(new SandboxManifestLoader().load(manifest))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("known production genesis hash");
	}

	@Test
	void wrapsStrictManifestValidationFailureAsActivationFailure() throws Exception {
		Path invalidManifest = temporaryDirectory.resolve("invalid.json");
		Files.writeString(invalidManifest, "{}");

		assertThatThrownBy(() -> guard.activate(request(ExecutionDomain.SANDBOX, true, Network.TESTNET,
				true, true, false, invalidManifest)))
				.isInstanceOf(SandboxActivationException.class)
				.hasMessageContaining("manifest validation failed");
	}

	private SandboxActivationRequest request(ExecutionDomain domain, boolean sandboxProfile, Network network,
			boolean directoryDisabled, boolean manifestConfigured, boolean stressTestEnabled, Path manifest) {
		return new SandboxActivationRequest(domain, sandboxProfile ? Set.of("sandbox") : Set.of(), network,
				directoryDisabled, stressTestEnabled, manifestConfigured ? manifest : null,
				manifestConfigured ? Set.of(SandboxActivationGuard.MANIFEST_OPTION) : Set.of());
	}

	private boolean expectedToActivate(ExecutionDomain domain, boolean sandboxProfile, Network network,
			boolean directoryDisabled, boolean manifestConfigured, boolean stressTestEnabled) {
		if (domain == ExecutionDomain.PRODUCTION) {
			return !sandboxProfile && !manifestConfigured;
		}
		return sandboxProfile && network == Network.TESTNET && directoryDisabled && manifestConfigured
				&& !stressTestEnabled;
	}

	private String description(SandboxActivationRequest request) {
		return "domain=%s profiles=%s network=%s directoryDisabled=%s stress=%s manifest=%s".formatted(
				request.executionDomain(), request.activeProfiles(), request.legacyWireNetwork(),
				request.directoryDisabled(), request.stressTestEnabled(), request.manifestPath() != null);
	}

	private Path copyValidManifest(String fileName) throws IOException {
		try (InputStream input = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(input).isNotNull();
			Path target = temporaryDirectory.resolve(fileName);
			Files.copy(input, target);
			return target;
		}
	}
}
