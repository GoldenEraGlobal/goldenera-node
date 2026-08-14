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

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.blockchain.genesis.SandboxGenesisPlanFactory;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.sandbox.genesis.SandboxNetworkSettingsAdapter;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;

/** Fail-closed chain identity lifecycle wiring. */
@Configuration(proxyBeanMethods = false)
public class ChainIdentityBootstrapConfiguration {

	@Bean
	static CoreChainIdentityOrdering coreChainIdentityOrdering() {
		return new CoreChainIdentityOrdering();
	}

	@Bean
	SandboxNetworkSettingsAdapter sandboxNetworkSettingsAdapter() {
		return new SandboxNetworkSettingsAdapter();
	}

	@Bean
	SandboxGenesisPlanFactory sandboxGenesisPlanFactory(
			GenesisCandidateFactory candidateFactory,
			SandboxNetworkSettingsAdapter settingsAdapter) {
		return new SandboxGenesisPlanFactory(candidateFactory, settingsAdapter);
	}

	@Bean
	ExpectedChainIdentityProvider expectedChainIdentityProvider(
			SandboxRuntimeContext runtimeContext,
			NetworkSettingsProvider networkSettingsProvider,
			DevelopmentGenesisIdentityCalculator developmentGenesisIdentityCalculator) {
		return new RuntimeExpectedChainIdentityProvider(
				runtimeContext, networkSettingsProvider, developmentGenesisIdentityCalculator::calculate);
	}

	@Bean
	DevelopmentGenesisIdentityCalculator developmentGenesisIdentityCalculator() {
		return new DevelopmentGenesisIdentityCalculator();
	}

	@Bean
	RocksChainStoragePreflightProbe rocksChainStoragePreflightProbe(
			BlockchainDbProperties properties) {
		return new RocksChainStoragePreflightProbe(Path.of(properties.getPath()));
	}

	@Bean
	ChainIdentityPreflight chainIdentityPreflight(
			RocksChainStoragePreflightProbe rocksProbe) {
		return new ChainIdentityPreflight(rocksProbe,
				new KnownProductionLegacyStorageVerifier());
	}

	@Bean
	ChainStorageGuard chainStorageGuard(RocksChainIdentityStore rocksStore) {
		return new ChainStorageGuard(rocksStore);
	}

	@Bean
	AuthoritativeChainIdentityProvider authoritativeChainIdentityProvider(
			RocksChainIdentityStore rocksStore,
			ExpectedChainIdentityProvider expectedIdentityProvider) {
		return new AuthoritativeChainIdentityProvider(rocksStore, expectedIdentityProvider);
	}

	@Bean(name = ChainIdentityGenesisVerifier.BEAN_NAME)
	ChainIdentityGenesisVerifier chainIdentityGenesisVerifier(
			SandboxRuntimeContext runtimeContext,
			NetworkSettingsProvider networkSettingsProvider,
			ExpectedChainIdentityProvider expectedIdentityProvider,
			SandboxGenesisPlanFactory sandboxPlanFactory,
			GenesisCandidateFactory candidateFactory) {
		return new ChainIdentityGenesisVerifier(
				runtimeContext,
				networkSettingsProvider,
				expectedIdentityProvider,
				sandboxPlanFactory,
				candidateFactory);
	}

	@Bean
	ChainIdentityBootstrapCoordinator chainIdentityBootstrapCoordinator(
			ExpectedChainIdentityProvider expectedIdentityProvider,
			ChainIdentityPreflight preflight,
			ObjectProvider<ChainStorageGuard> storageGuard) {
		return new ChainIdentityBootstrapCoordinator(
				expectedIdentityProvider, preflight, storageGuard::getObject);
	}

	@Bean(name = ChainIdentityPathPreflight.BEAN_NAME)
	ChainIdentityPathPreflight chainIdentityPathPreflight(
			ChainIdentityBootstrapCoordinator coordinator) {
		return new ChainIdentityPathPreflight(coordinator);
	}

	@Bean(name = ChainIdentityBindingInitializer.BEAN_NAME)
	ChainIdentityBindingInitializer chainIdentityBindingInitializer(
			ChainIdentityBootstrapCoordinator coordinator,
			ChainIdentityGenesisVerifier genesisVerifier) {
		return new ChainIdentityBindingInitializer(coordinator, genesisVerifier);
	}
}
