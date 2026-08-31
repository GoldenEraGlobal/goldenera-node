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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.MiningProperties;
import global.goldenera.node.core.properties.RandomXVerificationProperties;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.PowAlgorithm;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.MeterRegistry;

/** Selects exactly one fail-closed proof-of-work implementation at startup. */
@Configuration(proxyBeanMethods = false)
public class ProofOfWorkConfiguration {

	@Bean
	@Lazy
	RandomXManager randomXManager(MiningProperties miningProperties, ChainQuery chainQuery,
			SandboxRuntimeContext runtimeContext,
			RandomXVerificationProperties verificationProperties,
			GeneralProperties generalProperties,
			MeterRegistry meterRegistry) {
		RandomXManager manager = new RandomXManager(miningProperties, chainQuery, runtimeContext);
		manager.configureSyncVerificationAcceleration(
				verificationProperties, generalProperties.isExplorerEnable(), meterRegistry);
		return manager;
	}

	@Bean
	ProofOfWorkProvider proofOfWorkProvider(
			SandboxRuntimeContext runtimeContext,
			ObjectProvider<RandomXManager> randomXManagerProvider,
			RandomXVerificationProperties verificationProperties,
			MeterRegistry meterRegistry) {
		if (!runtimeContext.isSandbox()) {
			if (runtimeContext.manifestContext().isPresent()) {
				throw new IllegalStateException("Production proof-of-work cannot use a sandbox manifest");
			}
			return new RandomXProofOfWorkProvider(
					randomXManagerProvider.getObject(), verificationProperties, meterRegistry);
		}

		SandboxManifestContext manifestContext = runtimeContext.manifestContext()
				.orElseThrow(() -> new IllegalStateException("Sandbox proof-of-work requires a manifest"));
		PowAlgorithm algorithm = manifestContext.manifest().pow().algorithm();
		return switch (algorithm) {
			case RANDOMX -> new RandomXProofOfWorkProvider(
					randomXManagerProvider.getObject(), verificationProperties, meterRegistry);
			case DETERMINISTIC_SHA256_V1 -> DeterministicSha256ProofOfWorkProvider.from(manifestContext);
		};
	}
}
