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
package global.goldenera.node.core.blockchain.pow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Deterministic;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Pow;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.PowAlgorithm;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

class ProofOfWorkConfigurationTest {

	private final ProofOfWorkConfiguration configuration = new ProofOfWorkConfiguration();

	@Test
	void productionAlwaysSelectsRandomX() {
		RandomXManager manager = mock(RandomXManager.class);
		ObjectProvider<RandomXManager> provider = provider(manager);
		SandboxRuntimeContext production = new SandboxRuntimeContext(
				ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty());

		ProofOfWorkProvider selected = configuration.proofOfWorkProvider(production, provider);

		assertThat(selected).isInstanceOf(RandomXProofOfWorkProvider.class);
		verify(provider).getObject();
	}

	@Test
	void sandboxRandomXSelectsRandomX() {
		RandomXManager manager = mock(RandomXManager.class);
		ObjectProvider<RandomXManager> provider = provider(manager);

		ProofOfWorkProvider selected = configuration.proofOfWorkProvider(
				sandbox(PowAlgorithm.RANDOMX, DeterministicSha256ProofOfWorkProvider.DOMAIN_V1), provider);

		assertThat(selected).isInstanceOf(RandomXProofOfWorkProvider.class);
		verify(provider).getObject();
	}

	@Test
	void deterministicSandboxSelectsSha256WithoutCreatingRandomXManager() {
		ObjectProvider<RandomXManager> provider = provider(mock(RandomXManager.class));

		ProofOfWorkProvider selected = configuration.proofOfWorkProvider(
				sandbox(PowAlgorithm.DETERMINISTIC_SHA256_V1,
						DeterministicSha256ProofOfWorkProvider.DOMAIN_V1), provider);

		assertThat(selected).isInstanceOf(DeterministicSha256ProofOfWorkProvider.class);
		verify(provider, never()).getObject();
	}

	@Test
	void deterministicSpringContextHasOneProviderAndDoesNotInstantiateLazyRandomXBean() {
		SandboxRuntimeContext runtimeContext = sandbox(
				PowAlgorithm.DETERMINISTIC_SHA256_V1,
				DeterministicSha256ProofOfWorkProvider.DOMAIN_V1);

		new ApplicationContextRunner()
				.withBean(SandboxRuntimeContext.class, () -> runtimeContext)
				.withBean(MiningProperties.class, () -> mock(MiningProperties.class))
				.withBean(ChainQuery.class, () -> mock(ChainQuery.class))
				.withUserConfiguration(ProofOfWorkConfiguration.class)
				.run(context -> {
					assertThat(context).hasSingleBean(ProofOfWorkProvider.class);
					assertThat(context.getBean(ProofOfWorkProvider.class))
							.isInstanceOf(DeterministicSha256ProofOfWorkProvider.class);
					assertThat(context.getBeanFactory().containsSingleton("randomXManager")).isFalse();
				});
	}

	@Test
	void invalidActivationCombinationsFailClosed() {
		ObjectProvider<RandomXManager> provider = provider(mock(RandomXManager.class));
		SandboxRuntimeContext productionWithManifest = mock(SandboxRuntimeContext.class);
		SandboxManifestContext deterministicManifest = manifest(
				PowAlgorithm.DETERMINISTIC_SHA256_V1,
				DeterministicSha256ProofOfWorkProvider.DOMAIN_V1);
		when(productionWithManifest.isSandbox()).thenReturn(false);
		when(productionWithManifest.manifestContext()).thenReturn(Optional.of(deterministicManifest));
		SandboxRuntimeContext sandboxWithoutManifest = mock(SandboxRuntimeContext.class);
		when(sandboxWithoutManifest.isSandbox()).thenReturn(true);
		when(sandboxWithoutManifest.manifestContext()).thenReturn(Optional.empty());

		assertThatThrownBy(() -> configuration.proofOfWorkProvider(productionWithManifest, provider))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Production proof-of-work");
		assertThatThrownBy(() -> configuration.proofOfWorkProvider(sandboxWithoutManifest, provider))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("requires a manifest");
		assertThatThrownBy(() -> configuration.proofOfWorkProvider(
				sandbox(PowAlgorithm.DETERMINISTIC_SHA256_V1, "goldenera-sandbox-pow-v2"), provider))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported deterministic PoW domain");
	}

	@SuppressWarnings("unchecked")
	private ObjectProvider<RandomXManager> provider(RandomXManager manager) {
		ObjectProvider<RandomXManager> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(manager);
		return provider;
	}

	private SandboxRuntimeContext sandbox(PowAlgorithm algorithm, String domain) {
		SandboxRuntimeContext runtimeContext = mock(SandboxRuntimeContext.class);
		SandboxManifestContext manifestContext = manifest(algorithm, domain);
		when(runtimeContext.isSandbox()).thenReturn(true);
		when(runtimeContext.manifestContext()).thenReturn(Optional.of(manifestContext));
		return runtimeContext;
	}

	private SandboxManifestContext manifest(PowAlgorithm algorithm, String domain) {
		SandboxManifestContext manifestContext = mock(SandboxManifestContext.class);
		SandboxManifest manifest = mock(SandboxManifest.class);
		Pow pow = mock(Pow.class);
		Deterministic deterministic = mock(Deterministic.class);
		when(manifestContext.manifest()).thenReturn(manifest);
		when(manifestContext.fingerprint()).thenReturn(
				"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
		when(manifest.pow()).thenReturn(pow);
		when(pow.algorithm()).thenReturn(algorithm);
		when(pow.deterministic()).thenReturn(deterministic);
		when(deterministic.domain()).thenReturn(domain);
		return manifestContext;
	}
}
