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

import java.util.Objects;
import java.util.Optional;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.storage.chainidentity.KnownProductionChainIdentityRegistry;

public record SandboxRuntimeContext(
		ExecutionDomain executionDomain,
		Network legacyWireNetwork,
		Optional<SandboxManifestContext> manifestContext) {

	public SandboxRuntimeContext {
		Objects.requireNonNull(executionDomain, "executionDomain");
		Objects.requireNonNull(legacyWireNetwork, "legacyWireNetwork");
		manifestContext = Objects.requireNonNull(manifestContext, "manifestContext");
		if (executionDomain == ExecutionDomain.PRODUCTION && manifestContext.isPresent()) {
			throw new IllegalArgumentException("Production runtime cannot contain a sandbox manifest context");
		}
		if (executionDomain == ExecutionDomain.SANDBOX) {
			if (legacyWireNetwork != Network.TESTNET) {
				throw new IllegalArgumentException("Sandbox runtime requires TESTNET as its legacy wire carrier");
			}
			SandboxManifestContext sandboxManifest = manifestContext.orElseThrow(() ->
					new IllegalArgumentException("Sandbox runtime requires a manifest context"));
			if (!"TESTNET".equals(sandboxManifest.manifest().legacyCarrier().network())
					|| sandboxManifest.manifest().legacyCarrier().code() != Network.TESTNET.getCode()) {
				throw new IllegalArgumentException("Sandbox manifest legacy carrier must match TESTNET");
			}
			if (KnownProductionChainIdentityRegistry.containsGenesisHash(
					sandboxManifest.manifest().genesis().expectedGenesisHash())) {
				throw new IllegalArgumentException("Sandbox runtime cannot claim a known production genesis hash");
			}
		}
	}

	public boolean isSandbox() {
		return executionDomain == ExecutionDomain.SANDBOX;
	}
}
