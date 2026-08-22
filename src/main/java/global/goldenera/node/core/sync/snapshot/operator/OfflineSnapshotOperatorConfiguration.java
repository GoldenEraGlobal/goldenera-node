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
package global.goldenera.node.core.sync.snapshot.operator;

import javax.sql.DataSource;

import java.util.concurrent.locks.ReentrantLock;

import org.rocksdb.RocksDB;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotPublicationService;
import global.goldenera.node.core.sync.snapshot.CheckpointStateSnapshotExporter;
import global.goldenera.node.core.sync.snapshot.SnapshotAnchorPolicy;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveExporter;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.RepositoryCoreSnapshotEntityIndexSource;
import global.goldenera.node.explorer.snapshot.ExplorerCheckpointSnapshotExporter;
import global.goldenera.node.shared.properties.GeneralProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OfflineSnapshotOperatorProperties.class)
@ConditionalOnProperty(prefix = "ge.snapshot.operator", name = "enabled", havingValue = "true")
public class OfflineSnapshotOperatorConfiguration {

	@Bean
	SnapshotAnchorPolicy offlineSnapshotAnchorPolicy(
			ObjectProvider<LiveHeadCloneExportCapability> capabilityProvider,
			SandboxRuntimeContext runtimeContext) {
		return LiveHeadCloneExportCapability.select(capabilityProvider, runtimeContext);
	}

	@Bean
	CheckpointStateSnapshotExporter offlineCheckpointStateSnapshotExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			WorldStateFactory worldStateFactory,
			AuthoritativeChainIdentityProvider identityProvider,
			NetworkSettingsProvider settingsProvider,
			OfflineSnapshotOperatorProperties properties,
			ObjectMapper objectMapper,
			SnapshotAnchorPolicy offlineSnapshotAnchorPolicy) {
		return new CheckpointStateSnapshotExporter(
				checkpointRegistry, chainQuery, worldStateFactory, identityProvider.identity(),
				settingsProvider.currentSettings().randomXEpochLength(), properties.getPublicOrigin(), objectMapper,
				offlineSnapshotAnchorPolicy);
	}

	@Bean
	CoreSnapshotArchiveExporter offlineCoreSnapshotArchiveExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			AuthoritativeChainIdentityProvider identityProvider,
			EntityIndexRepository entityIndexRepository,
			ObjectMapper objectMapper,
			SnapshotAnchorPolicy offlineSnapshotAnchorPolicy) {
		StoredChainIdentity identity = identityProvider.identity();
		return new CoreSnapshotArchiveExporter(
				checkpointRegistry, chainQuery, identity, objectMapper,
				new RepositoryCoreSnapshotEntityIndexSource(entityIndexRepository, chainQuery),
				offlineSnapshotAnchorPolicy);
	}

	@Bean
	@ConditionalOnProperty(prefix = "ge.snapshot.operator", name = "include-explorer", havingValue = "true")
	ExplorerCheckpointSnapshotExporter offlineExplorerCheckpointSnapshotExporter(
			DataSource dataSource, ObjectMapper objectMapper) {
		return new ExplorerCheckpointSnapshotExporter(dataSource, objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	LiveHeadCoreSnapshotCloneService liveHeadCoreSnapshotCloneService(
			RocksDB blockchainDB,
			RocksDbColumnFamilies families,
			BlockchainDbProperties databaseProperties,
			ObjectMapper objectMapper,
			@Qualifier("masterChainLock") ReentrantLock masterChainLock) {
		return new LiveHeadCoreSnapshotCloneService(
				blockchainDB, families, databaseProperties,
				new BlockchainRocksDbFactory(databaseProperties), masterChainLock, objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	IsolatedLiveHeadSnapshotPublisher isolatedLiveHeadSnapshotPublisher() {
		return new IsolatedLiveHeadSnapshotPublisher();
	}

	@Bean
	CheckpointSnapshotPublicationService offlineCheckpointSnapshotPublicationService(
			CheckpointStateSnapshotExporter stateExporter,
			CoreSnapshotArchiveExporter archiveExporter,
			CoreSnapshotArchiveVerifier verifier) {
		return new CheckpointSnapshotPublicationService(stateExporter, archiveExporter, verifier);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "ge.snapshot.operator", name = "clone-export-context", havingValue = "false", matchIfMissing = true)
	OfflineSnapshotOperatorService offlineSnapshotOperatorService(
			OfflineSnapshotOperatorProperties properties,
			SnapshotDistributionProperties distributionProperties,
			GeneralProperties generalProperties,
			NetworkSettingsProvider networkSettingsProvider,
			AuthoritativeChainIdentityProvider identityProvider,
			LiveHeadCoreSnapshotCloneService cloneService,
			IsolatedLiveHeadSnapshotPublisher isolatedPublisher) {
		return new OfflineSnapshotOperatorService(
				properties, distributionProperties, generalProperties,
				networkSettingsProvider, identityProvider, cloneService, isolatedPublisher);
	}
}
