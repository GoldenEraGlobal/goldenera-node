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
package global.goldenera.node.core.node.capabilities;

import java.util.TreeSet;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.blockchain.pow.DeterministicSha256ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.shared.properties.GeneralProperties;

@Component
public final class RuntimeNodeCapabilitiesProvider implements NodeCapabilitiesProvider {

	private static final String CONTROL_ENABLED = "ge.sandbox.control-api.enabled";
	public static final String MINING_ECONOMICS_V1 = "mining-economics-v1";

	private final SandboxRuntimeContext runtimeContext;
	private final AuthoritativeChainIdentityProvider identityProvider;
	private final ProofOfWorkProvider proofOfWorkProvider;
	private final MiningProperties miningProperties;
	private final GeneralProperties generalProperties;
	private final Environment environment;

	public RuntimeNodeCapabilitiesProvider(
			SandboxRuntimeContext runtimeContext,
			AuthoritativeChainIdentityProvider identityProvider,
			ProofOfWorkProvider proofOfWorkProvider,
			MiningProperties miningProperties,
			GeneralProperties generalProperties,
			Environment environment) {
		this.runtimeContext = runtimeContext;
		this.identityProvider = identityProvider;
		this.proofOfWorkProvider = proofOfWorkProvider;
		this.miningProperties = miningProperties;
		this.generalProperties = generalProperties;
		this.environment = environment;
	}

	@Override
	public NodeCapabilitiesSnapshot snapshot() {
		ProofOfWorkRuntimeMode proofOfWorkMode = proofOfWorkMode();
		TreeSet<String> capabilities = new TreeSet<>();
		capabilities.add("chain-identity-v1");
		capabilities.add("core-readiness-v1");
		capabilities.add(MINING_ECONOMICS_V1);
		capabilities.add(switch (proofOfWorkMode) {
			case RANDOMX_FULL -> "pow-randomx-full";
			case RANDOMX_LIGHT -> "pow-randomx-light";
			case DETERMINISTIC_SHA256_V1 -> "pow-deterministic-sha256-v1";
		});
		if (generalProperties.isExplorerEnable()) {
			capabilities.add("explorer-v1");
		}
		runtimeContext.manifestContext().ifPresent(context -> addSandboxCapabilities(
				capabilities, context.manifest()));

		return new NodeCapabilitiesSnapshot(
				1,
				runtimeContext.executionDomain(),
				identityProvider.identity(),
				proofOfWorkMode,
				capabilities.stream().toList());
	}

	private ProofOfWorkRuntimeMode proofOfWorkMode() {
		if (proofOfWorkProvider instanceof DeterministicSha256ProofOfWorkProvider) {
			return ProofOfWorkRuntimeMode.DETERMINISTIC_SHA256_V1;
		}
		if (!(proofOfWorkProvider instanceof RandomXProofOfWorkProvider)) {
			throw new IllegalStateException("Unsupported active proof-of-work provider: "
					+ proofOfWorkProvider.getClass().getSimpleName());
		}
		return miningProperties.getMemoryMode() == RandomXMiningMemoryMode.LIGHT
				? ProofOfWorkRuntimeMode.RANDOMX_LIGHT
				: ProofOfWorkRuntimeMode.RANDOMX_FULL;
	}

	private void addSandboxCapabilities(TreeSet<String> capabilities, SandboxManifest manifest) {
		capabilities.add("sandbox-manifest-v1");
		if (manifest.features().deterministicClock()) {
			capabilities.add("clock-deterministic-v1");
		}
		if (manifest.features().legacyPeerCompatibility()) {
			capabilities.add("legacy-peer-compatibility-v1");
		}
		if (manifest.features().controlApi()
				&& environment.getProperty(CONTROL_ENABLED, Boolean.class, false)) {
			capabilities.add("sandbox-control-v1");
		}
	}
}
