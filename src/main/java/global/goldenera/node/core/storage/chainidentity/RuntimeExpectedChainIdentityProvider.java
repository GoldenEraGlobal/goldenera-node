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

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import global.goldenera.node.NetworkSettings;
import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

/** Derives the expected identity only from trusted activation and local genesis inputs. */
public final class RuntimeExpectedChainIdentityProvider implements ExpectedChainIdentityProvider {

	private final SandboxRuntimeContext runtimeContext;
	private final NetworkSettingsProvider networkSettingsProvider;
	private final Function<NetworkSettings, String> developmentGenesisHash;
	private ChainIdentityExpectation cached;

	public RuntimeExpectedChainIdentityProvider(
			SandboxRuntimeContext runtimeContext,
			NetworkSettingsProvider networkSettingsProvider,
			GenesisCandidateFactory candidateFactory) {
		this(runtimeContext, networkSettingsProvider, () -> candidateFactory);
	}

	public RuntimeExpectedChainIdentityProvider(
			SandboxRuntimeContext runtimeContext,
			NetworkSettingsProvider networkSettingsProvider,
			Supplier<GenesisCandidateFactory> candidateFactory) {
		this(runtimeContext, networkSettingsProvider,
				settings -> candidateFactory.get().create(settings, 0L).block().getHash().toHexString());
	}

	public RuntimeExpectedChainIdentityProvider(
			SandboxRuntimeContext runtimeContext,
			NetworkSettingsProvider networkSettingsProvider,
			Function<NetworkSettings, String> developmentGenesisHash) {
		this.runtimeContext = runtimeContext;
		this.networkSettingsProvider = networkSettingsProvider;
		this.developmentGenesisHash = developmentGenesisHash;
	}

	@Override
	public synchronized ChainIdentityExpectation expectedIdentity() {
		if (cached == null) {
			cached = runtimeContext.isSandbox() ? sandboxExpectation() : productionOrDevelopmentExpectation();
		}
		return cached;
	}

	private ChainIdentityExpectation sandboxExpectation() {
		SandboxManifestContext context = runtimeContext.manifestContext().orElseThrow();
		return new ChainIdentityExpectation(
				new StoredChainIdentity(
						StoredChainIdentity.CURRENT_FORMAT_VERSION,
						runtimeContext.legacyWireNetwork().getCode(),
						context.manifest().chainId(),
						context.manifest().genesis().expectedGenesisHash(),
						context.fingerprint()),
				ChainIdentityExecutionScope.SANDBOX);
	}

	private ChainIdentityExpectation productionOrDevelopmentExpectation() {
		int carrier = runtimeContext.legacyWireNetwork().getCode();
		String chainId = runtimeContext.legacyWireNetwork().name().toLowerCase(Locale.ROOT);
		if (!"dev".equals(networkSettingsProvider.currentProfile())) {
			String genesisHash = KnownProductionChainIdentityRegistry.expectedGenesisHash(carrier, chainId)
					.orElseThrow(() -> new ChainStorageGuardException(
							"No compile-time production identity exists for " + chainId));
			return new ChainIdentityExpectation(
					new StoredChainIdentity(StoredChainIdentity.CURRENT_FORMAT_VERSION,
							carrier, chainId, genesisHash, null),
					ChainIdentityExecutionScope.KNOWN_PRODUCTION);
		}

		NetworkSettings settings = networkSettingsProvider.currentSettings();
		return new ChainIdentityExpectation(
				new StoredChainIdentity(StoredChainIdentity.CURRENT_FORMAT_VERSION,
						carrier, "development-" + chainId,
						developmentGenesisHash.apply(settings), null),
				ChainIdentityExecutionScope.DEVELOPMENT);
	}
}
