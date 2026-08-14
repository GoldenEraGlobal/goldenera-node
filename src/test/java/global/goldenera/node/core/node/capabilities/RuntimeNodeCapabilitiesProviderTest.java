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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.blockchain.pow.DeterministicSha256ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.shared.properties.GeneralProperties;

class RuntimeNodeCapabilitiesProviderTest {

	@Test
	void reportsActualRandomXFullRuntimeAndNoSandboxCapabilitiesInProduction() {
		NodeCapabilitiesSnapshot snapshot = provider(
				new SandboxRuntimeContext(ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty()),
				mock(RandomXProofOfWorkProvider.class),
				RandomXMiningMemoryMode.FULL)
				.snapshot();

		assertThat(snapshot.proofOfWorkMode()).isEqualTo(ProofOfWorkRuntimeMode.RANDOMX_FULL);
		assertThat(snapshot.capabilityIds()).contains("pow-randomx-full");
		assertThat(snapshot.capabilityIds()).noneMatch(id -> id.startsWith("sandbox-")
				|| id.startsWith("clock-") || id.startsWith("legacy-peer-"));
	}

	@Test
	void reportsActualRandomXLightRuntime() {
		NodeCapabilitiesSnapshot snapshot = provider(
				sandboxRuntime(),
				mock(RandomXProofOfWorkProvider.class),
				RandomXMiningMemoryMode.LIGHT)
				.snapshot();

		assertThat(snapshot.proofOfWorkMode()).isEqualTo(ProofOfWorkRuntimeMode.RANDOMX_LIGHT);
		assertThat(snapshot.capabilityIds()).contains("pow-randomx-light");
	}

	@Test
	void reportsActualDeterministicRuntimeAndStableSortedCapabilities() {
		NodeCapabilitiesSnapshot snapshot = provider(
				sandboxRuntime(),
				mock(DeterministicSha256ProofOfWorkProvider.class),
				RandomXMiningMemoryMode.FULL)
				.snapshot();

		assertThat(snapshot.proofOfWorkMode()).isEqualTo(ProofOfWorkRuntimeMode.DETERMINISTIC_SHA256_V1);
		assertThat(snapshot.capabilityIds()).contains("pow-deterministic-sha256-v1");
		assertThat(snapshot.capabilityIds()).isSorted().doesNotHaveDuplicates();
	}

	private RuntimeNodeCapabilitiesProvider provider(SandboxRuntimeContext runtimeContext,
			ProofOfWorkProvider proofOfWorkProvider, RandomXMiningMemoryMode memoryMode) {
		AuthoritativeChainIdentityProvider identityProvider = mock(AuthoritativeChainIdentityProvider.class);
		when(identityProvider.identity()).thenReturn(new StoredChainIdentity(
				1, 0, "mainnet", "0x" + "01".repeat(32), null));
		MiningProperties miningProperties = new MiningProperties();
		miningProperties.setMemoryMode(memoryMode);
		GeneralProperties generalProperties = new GeneralProperties();
		generalProperties.setExplorerEnable(false);
		return new RuntimeNodeCapabilitiesProvider(
				runtimeContext,
				identityProvider,
				proofOfWorkProvider,
				miningProperties,
				generalProperties,
				new MockEnvironment());
	}

	private SandboxRuntimeContext sandboxRuntime() {
		SandboxRuntimeContext runtimeContext = mock(SandboxRuntimeContext.class);
		when(runtimeContext.executionDomain()).thenReturn(ExecutionDomain.SANDBOX);
		when(runtimeContext.manifestContext()).thenReturn(Optional.empty());
		return runtimeContext;
	}
}
