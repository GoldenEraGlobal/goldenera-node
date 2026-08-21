/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotVerifier;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;

@Configuration(proxyBeanMethods = false)
public class SnapshotBootstrapConfiguration {

	@Bean
	@Lazy
	CheckpointSnapshotVerifier checkpointSnapshotVerifier(
			CheckpointRegistry checkpointRegistry,
			AuthoritativeChainIdentityProvider chainIdentityProvider,
			NetworkSettingsProvider networkSettingsProvider) {
		return new CheckpointSnapshotVerifier(
				checkpointRegistry,
				chainIdentityProvider.identity(),
				networkSettingsProvider.currentSettings().randomXEpochLength());
	}

	@Bean
	@Lazy
	CoreSnapshotArchiveVerifier coreSnapshotArchiveVerifier(CheckpointSnapshotVerifier stateVerifier) {
		return new CoreSnapshotArchiveVerifier(stateVerifier);
	}
}
