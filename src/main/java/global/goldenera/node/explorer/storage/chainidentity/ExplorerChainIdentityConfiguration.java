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
package global.goldenera.node.explorer.storage.chainidentity;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;
import global.goldenera.node.explorer.snapshot.DelayedExplorerSnapshotPublicationGenerator;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveRebuildService;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveReplayEngine;
import global.goldenera.node.explorer.snapshot.ExplorerCheckpointSnapshotImporter;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotBootstrapService;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotRemoteSource;
import global.goldenera.node.explorer.snapshot.IsolatedExplorerArchiveReplayEngine;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerQueueService;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerService;
import global.goldenera.node.explorer.services.indexer.business.ExplorerIndexingExecutionGate;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.shared.properties.GeneralProperties;
import liquibase.integration.spring.SpringLiquibase;

/** Explorer-owned PostgreSQL identity mirror wiring. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
		prefix = "ge.general",
		name = "explorer-enable",
		havingValue = "true",
		matchIfMissing = true)
public class ExplorerChainIdentityConfiguration {

	@Bean
	static ExplorerChainIdentityOrdering explorerChainIdentityOrdering() {
		return new ExplorerChainIdentityOrdering();
	}

	@Bean
	ExplorerRuntimeReadiness explorerRuntimeReadiness() {
		return new ExplorerRuntimeReadiness();
	}

	@Bean
	ExplorerReadinessFilter explorerReadinessFilter(ExplorerRuntimeReadiness readiness) {
		return new ExplorerReadinessFilter(readiness);
	}

	@Bean
	ExplorerSchemaMigrator explorerSchemaMigrator(ObjectProvider<SpringLiquibase> liquibaseProvider) {
		return new ExplorerSchemaMigrator(liquibaseProvider);
	}

	@Bean
	PostgresChainStoragePreflightProbe postgresChainStoragePreflightProbe(DataSource dataSource) {
		return new PostgresChainStoragePreflightProbe(dataSource);
	}

	@Bean
	ExplorerChainIdentityGuard explorerChainIdentityGuard(
			PostgresChainStoragePreflightProbe probe,
			PostgresChainIdentityRepository repository) {
		return new ExplorerChainIdentityGuard(probe, repository);
	}

	@Bean
	ExplorerCheckpointSnapshotImporter explorerCheckpointSnapshotImporter(
			DataSource dataSource,
			ObjectMapper objectMapper) {
		return new ExplorerCheckpointSnapshotImporter(dataSource, objectMapper);
	}

	@Bean
	ExplorerSnapshotBootstrapService explorerSnapshotBootstrapService(
			SnapshotDistributionProperties properties,
			CoreSnapshotCheckpointFloorPolicy floorPolicy,
			ChainQuery chainQuery,
			DataSource dataSource,
			ExplorerSnapshotRemoteSource remoteSource,
			ExplorerCheckpointSnapshotImporter importer) {
		return new ExplorerSnapshotBootstrapService(
				properties, floorPolicy, chainQuery, dataSource, remoteSource, importer);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "ge.core.sync.snapshot",
			name = "publish-enabled",
			havingValue = "true")
	DelayedExplorerSnapshotPublicationGenerator delayedExplorerSnapshotPublicationGenerator(
			DataSource dataSource,
			ChainQuery chainQuery,
			AuthoritativeChainIdentityProvider identityProvider,
			SnapshotDistributionProperties properties,
			ObjectMapper objectMapper) {
		return new DelayedExplorerSnapshotPublicationGenerator(
				true, dataSource, chainQuery, identityProvider, properties, objectMapper, Clock.systemUTC());
	}

	@Bean
	ExplorerArchiveReplayEngine explorerArchiveReplayEngine(
			ChainQuery chainQuery,
			StateProcessor stateProcessor,
			BlockEventExtractor blockEventExtractor,
			ExIndexerService indexerService,
			ExIndexerStatusCoreService statusService) {
		return new IsolatedExplorerArchiveReplayEngine(
				chainQuery, stateProcessor, blockEventExtractor, indexerService, statusService);
	}

	@Bean
	ExplorerArchiveRebuildService explorerArchiveRebuildService(
			GeneralProperties generalProperties,
			ExplorerRuntimeReadiness readiness,
			ExplorerArchiveReplayEngine replayEngine,
			ExplorerIndexingExecutionGate executionGate,
			ExIndexerQueueService queueService) {
		return new ExplorerArchiveRebuildService(
				generalProperties, readiness, replayEngine, executionGate, queueService);
	}

	@Bean(name = ExplorerChainIdentityInitializer.BEAN_NAME)
	ExplorerChainIdentityInitializer explorerChainIdentityInitializer(
			AuthoritativeChainIdentityProvider authoritativeIdentityProvider,
			ExplorerSchemaMigrator schemaMigrator,
			ExplorerChainIdentityGuard guard,
			ExplorerRuntimeReadiness readiness,
			GeneralProperties generalProperties,
			ExplorerSnapshotBootstrapService snapshotBootstrap,
			ExplorerArchiveRebuildService archiveRebuild) {
		return new ExplorerChainIdentityInitializer(
				authoritativeIdentityProvider, schemaMigrator, guard, readiness,
				generalProperties, snapshotBootstrap, archiveRebuild);
	}
}
