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
package global.goldenera.node.core.storage.chainidentity;

import java.util.Objects;
import java.util.regex.Pattern;

public record ChainIdentityExpectation(StoredChainIdentity identity, ChainIdentityExecutionScope scope) {

	private static final Pattern SANDBOX_CHAIN_ID = Pattern.compile("sandbox-[0-9a-f]{32,64}");

	public ChainIdentityExpectation {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(scope, "scope");
		switch (scope) {
			case SANDBOX -> validateSandbox(identity);
			case KNOWN_PRODUCTION -> validateKnownProduction(identity);
			case DEVELOPMENT -> validateDevelopment(identity);
		}
	}

	public boolean sandbox() {
		return scope == ChainIdentityExecutionScope.SANDBOX;
	}

	public boolean knownProduction() {
		return scope == ChainIdentityExecutionScope.KNOWN_PRODUCTION;
	}

	private static void validateSandbox(StoredChainIdentity identity) {
		if (identity.carrierNetworkCode() != 1
				|| !SANDBOX_CHAIN_ID.matcher(identity.chainId()).matches()
				|| identity.manifestFingerprint() == null) {
			throw new IllegalArgumentException(
					"Sandbox identity requires TESTNET carrier, high-entropy chain ID and manifest fingerprint");
		}
		if (KnownProductionChainIdentityRegistry.containsGenesisHash(identity.genesisHash())) {
			throw new IllegalArgumentException("Sandbox identity cannot claim a known production genesis hash");
		}
	}

	private static void validateKnownProduction(StoredChainIdentity identity) {
		if (!KnownProductionChainIdentityRegistry.isKnownProductionIdentity(identity)) {
			throw new IllegalArgumentException("Known-production scope requires an exact compile-time registry identity");
		}
	}

	private static void validateDevelopment(StoredChainIdentity identity) {
		if (identity.manifestFingerprint() != null) {
			throw new IllegalArgumentException("Development identity cannot contain a sandbox fingerprint");
		}
	}
}
