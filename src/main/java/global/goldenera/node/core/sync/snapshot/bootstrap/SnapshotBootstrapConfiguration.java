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
package global.goldenera.node.core.sync.snapshot.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityPathPreflight;
import global.goldenera.node.core.storage.chainidentity.ExpectedChainIdentityProvider;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotVerifier;
import global.goldenera.node.core.sync.snapshot.operator.LiveHeadCloneExportCapability;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;

@Configuration(proxyBeanMethods = false)
public class SnapshotBootstrapConfiguration {

	@Bean
	@Lazy
	CheckpointSnapshotVerifier checkpointSnapshotVerifier(
			CheckpointRegistry checkpointRegistry,
			ExpectedChainIdentityProvider expectedChainIdentityProvider,
			NetworkSettingsProvider networkSettingsProvider,
			ObjectProvider<LiveHeadCloneExportCapability> capabilityProvider,
			SandboxRuntimeContext runtimeContext) {
		return new CheckpointSnapshotVerifier(
				checkpointRegistry,
				expectedChainIdentityProvider.expectedIdentity().identity(),
				networkSettingsProvider.currentSettings().randomXEpochLength(),
				LiveHeadCloneExportCapability.select(capabilityProvider, runtimeContext));
	}

	@Bean
	@Lazy
	CoreSnapshotArchiveVerifier coreSnapshotArchiveVerifier(CheckpointSnapshotVerifier stateVerifier) {
		return new CoreSnapshotArchiveVerifier(stateVerifier);
	}

	@Bean
	CoreSnapshotArchiveImporter coreSnapshotArchiveImporter(
			BlockchainDbProperties databaseProperties,
			ObjectMapper objectMapper) {
		return new CoreSnapshotDiskArchiveImporter(databaseProperties, objectMapper);
	}

	@Bean
	CoreSnapshotFilesystemActivation coreSnapshotFilesystemActivation(
			BlockchainDbProperties databaseProperties) throws IOException {
		Path target = Path.of(databaseProperties.getPath()).toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null) {
			throw new IllegalArgumentException("Blockchain database path must have a parent");
		}
		Files.createDirectories(parent);
		return new CoreSnapshotFilesystemActivation(target);
	}

	@Bean
	CoreSnapshotCanonicalActivator coreSnapshotCanonicalActivator(
			CoreSnapshotFilesystemActivation filesystemActivation) {
		return new CoreSnapshotFilesystemCanonicalActivator(filesystemActivation);
	}

	@Bean(name = CoreSnapshotPreOpenInitializer.BEAN_NAME)
	CoreSnapshotPreOpenInitializer coreSnapshotPreOpenInitializer(
			CoreSnapshotBootstrapCoordinator coordinator,
			ChainIdentityPathPreflight chainIdentityPathPreflight) {
		return new CoreSnapshotPreOpenInitializer(coordinator, chainIdentityPathPreflight);
	}
}
