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

import java.util.Optional;
import java.util.Set;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestException;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.storage.chainidentity.KnownProductionChainIdentityRegistry;

/** Applies the fail-closed activation matrix before the Spring context starts. */
public final class SandboxActivationGuard {

	static final String SANDBOX_PROFILE = "sandbox";
	static final String MANIFEST_OPTION = "gesandboxmanifestpath";
	static final String CONTROL_API_ENABLED_OPTION = "gesandboxcontrolapienabled";
	static final String CONTROL_API_TOKEN_FILE_OPTION = "gesandboxcontrolapitokenfile";

	private static final Set<String> ALLOWED_SANDBOX_OPTIONS = Set.of(
			MANIFEST_OPTION,
			CONTROL_API_ENABLED_OPTION,
			CONTROL_API_TOKEN_FILE_OPTION);

	private final SandboxManifestLoader manifestLoader;

	public SandboxActivationGuard(SandboxManifestLoader manifestLoader) {
		this.manifestLoader = manifestLoader;
	}

	public SandboxRuntimeContext activate(SandboxActivationRequest request) {
		boolean sandboxProfile = request.activeProfiles().contains(SANDBOX_PROFILE);
		boolean manifestConfigured = request.manifestPath() != null;

		if (request.executionDomain() == ExecutionDomain.PRODUCTION) {
			if (sandboxProfile) {
				throw rejected("The sandbox Spring profile cannot run in the PRODUCTION execution domain");
			}
			if (manifestConfigured || !request.configuredSandboxOptions().isEmpty()) {
				throw rejected("Sandbox options and manifest overrides are forbidden in production modes");
			}
			return new SandboxRuntimeContext(ExecutionDomain.PRODUCTION, request.legacyWireNetwork(),
					Optional.empty());
		}

		if (!sandboxProfile || request.activeProfiles().size() != 1) {
			throw rejected("SANDBOX execution requires sandbox as its only active Spring profile");
		}
		if (request.legacyWireNetwork() != Network.TESTNET) {
			throw rejected("SANDBOX execution requires TESTNET as its legacy wire carrier");
		}
		if (!request.directoryDisabled()) {
			throw rejected("SANDBOX execution requires production directory communication to be disabled");
		}
		if (request.stressTestEnabled()) {
			throw rejected("The legacy stress-test API must be disabled in SANDBOX execution");
		}
		if (!ALLOWED_SANDBOX_OPTIONS.containsAll(request.configuredSandboxOptions())) {
			throw rejected("Unknown sandbox-only option configured: " + request.configuredSandboxOptions());
		}
		if (!manifestConfigured) {
			throw rejected("SANDBOX execution requires an absolute mounted manifest path");
		}

		SandboxManifestContext manifestContext;
		try {
			manifestContext = manifestLoader.load(request.manifestPath());
		} catch (SandboxManifestException e) {
			throw new SandboxActivationException("SANDBOX manifest validation failed: " + e.getMessage(), e);
		}
		if (!"TESTNET".equals(manifestContext.manifest().legacyCarrier().network())
				|| manifestContext.manifest().legacyCarrier().code() != Network.TESTNET.getCode()) {
			throw rejected("SANDBOX manifest legacy carrier must be TESTNET/" + Network.TESTNET.getCode());
		}
		if (KnownProductionChainIdentityRegistry.containsGenesisHash(
				manifestContext.manifest().genesis().expectedGenesisHash())) {
			throw rejected("SANDBOX manifest cannot claim a known production genesis hash");
		}

		return new SandboxRuntimeContext(ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(manifestContext));
	}

	private SandboxActivationException rejected(String message) {
		return new SandboxActivationException("Sandbox activation rejected: " + message);
	}
}
