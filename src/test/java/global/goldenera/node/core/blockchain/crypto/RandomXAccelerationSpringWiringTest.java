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
package global.goldenera.node.core.blockchain.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationSession;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXMiningMemoryMode;
import global.goldenera.node.core.properties.RandomXSyncVerificationMode;
import global.goldenera.node.core.properties.RandomXVerificationProperties;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.sync.SyncVerificationAccelerationConfiguration;
import global.goldenera.node.core.sync.SyncVerificationAccelerationPolicy;
import global.goldenera.randomx.RandomXCache;
import global.goldenera.randomx.RandomXDataset;
import global.goldenera.randomx.RandomXFlag;
import global.goldenera.randomx.RandomXVM;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RandomXAccelerationSpringWiringTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(TestConfiguration.class, SyncVerificationAccelerationConfiguration.class);

	@Test
	void defaultAutoBindingDelegatesLifecycleAndBuildsFullSessionForAnAdmittedProductionGap() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(RandomXVerificationProperties.class);
			assertThat(context.getBean(RandomXVerificationProperties.class).getVerificationMode())
					.isEqualTo(RandomXSyncVerificationMode.AUTO);
			assertThat(context).hasSingleBean(ProofOfWorkProvider.class);
			assertThat(context).hasSingleBean(SyncVerificationAccelerationPolicy.class);

			SyncVerificationAccelerationPolicy lifecycle =
					context.getBean(SyncVerificationAccelerationPolicy.class);
			ProofOfWorkProvider provider = context.getBean(ProofOfWorkProvider.class);
			lifecycle.bulkCatchUpStarted(0, 10_000);
			var verificationContext = provider.verificationContext(1L, ignored -> Optional.empty());
			try (ProofOfWorkVerificationSession session = provider.openVerificationSession(verificationContext)) {
				assertThat(session.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_FULL);
				assertThat(session.hash(new byte[] { 1 })).hasSize(32);
			}
			lifecycle.progress(9_500, 10_000);
			try (ProofOfWorkVerificationSession session = provider.openVerificationSession(verificationContext)) {
				assertThat(session.mode()).isEqualTo(ProofOfWorkVerificationMode.RANDOMX_LIGHT);
			}
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RandomXVerificationProperties.class)
	static class TestConfiguration {

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean
		TestResourceFactory testResourceFactory() {
			return new TestResourceFactory();
		}

		@Bean
		RandomXManager randomXManager(
				RandomXVerificationProperties properties,
				MeterRegistry registry,
				TestResourceFactory resourceFactory) {
			MiningProperties mining = new MiningProperties();
			mining.setEnable(false);
			mining.setHashingThreads(-1);
			mining.setMemoryMode(RandomXMiningMemoryMode.FULL);
			RandomXManager manager = new RandomXManager(
					mining,
					mock(ChainQuery.class),
					new SandboxRuntimeContext(ExecutionDomain.PRODUCTION, Network.MAINNET, Optional.empty()),
					resourceFactory,
					height -> new byte[] { (byte) height, 42 });
			manager.configureSyncVerificationAcceleration(
					properties,
					false,
					registry,
					() -> new RandomXSyncMemoryPolicy.MemorySnapshot(32_768, 24_000, 4_096, true));
			return manager;
		}

		@Bean
		ProofOfWorkProvider proofOfWorkProvider(
				RandomXManager manager,
				RandomXVerificationProperties properties,
				MeterRegistry registry) {
			return new RandomXProofOfWorkProvider(manager, properties, registry);
		}
	}

	static final class TestResourceFactory implements RandomXResourceFactory {
		@Override
		public RandomXCache createCache(Set<RandomXFlag> flags) {
			return mock(RandomXCache.class);
		}

		@Override
		public RandomXDataset createDataset(Set<RandomXFlag> flags) {
			return mock(RandomXDataset.class);
		}

		@Override
		public RandomXVM createVM(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
			RandomXVM vm = mock(RandomXVM.class);
			when(vm.calculateHash(any())).thenReturn(new byte[32]);
			return vm;
		}
	}
}
