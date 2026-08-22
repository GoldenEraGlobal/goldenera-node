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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityBindingInitializer;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerQueueService;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerService;
import global.goldenera.node.explorer.services.indexer.business.ExplorerIndexingExecutionGate;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveRebuildLauncher;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveReplayEngine;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotRemoteSource;
import global.goldenera.node.shared.properties.GeneralProperties;
import liquibase.integration.spring.SpringLiquibase;

class ExplorerStorageLifecycleContextTest {

	@Test
	void unavailableExplorerPostgresDoesNotFailCoreContextOrRunGuardedWorker() {
		new ApplicationContextRunner()
				.withUserConfiguration(UnavailableExplorerConfiguration.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean("coreMarker", CoreMarker.class)).isNotNull();
					ExplorerRuntimeReadiness readiness = context.getBean(ExplorerRuntimeReadiness.class);
					assertThat(readiness.status().state())
							.isEqualTo(ExplorerReadinessState.DATABASE_UNAVAILABLE);
					assertThat(context.getBean(GuardedWorker.class).ran()).isFalse();
				});
	}

	@Test
	void productionExplorerLifecycleStartsWithCircularReferencesProhibited() {
		new ApplicationContextRunner()
				.withPropertyValues(
						"spring.main.allow-circular-references=false",
						"ge.general.explorer-enable=true",
						"ge.database.postgresql-enable=true")
				.withUserConfiguration(
						ExplorerChainIdentityConfiguration.class,
						EnabledExplorerDependencies.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					DefaultListableBeanFactory beanFactory =
							(DefaultListableBeanFactory) context.getBeanFactory();
					assertThat(beanFactory.isAllowCircularReferences()).isFalse();
					assertThat(beanFactory.getBeanDefinition("explorerArchiveRebuildLauncher").isLazyInit())
							.isFalse();
					assertThat(beanFactory.getBeanDefinition(ExplorerChainIdentityInitializer.BEAN_NAME)
							.isLazyInit()).isFalse();
					assertThat(context).hasSingleBean(ExplorerArchiveRebuildLauncher.class);
					assertThat(context.getBean(ExplorerRuntimeReadiness.class).status().state())
							.isEqualTo(ExplorerReadinessState.READY);
					assertThat(context.getBean(ExplorerArchiveRebuildLauncher.class))
							.extracting("rebuildService")
							.isNull();
				});
	}

	@Configuration(proxyBeanMethods = false)
	static class UnavailableExplorerConfiguration {

		@Bean
		static ExplorerChainIdentityOrdering explorerChainIdentityOrdering() {
			return new ExplorerChainIdentityOrdering();
		}

		@Bean(name = ChainIdentityBindingInitializer.BEAN_NAME)
		CoreMarker chainIdentityBindingInitializer() {
			return new CoreMarker();
		}

		@Bean
		CoreMarker coreMarker() {
			return new CoreMarker();
		}

		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource(
					"jdbc:postgresql://127.0.0.1:1/unavailable", "unavailable", "unavailable");
		}

		@Bean
		PostgresChainStoragePreflightProbe probe(DataSource dataSource) {
			return new PostgresChainStoragePreflightProbe(dataSource);
		}

		@Bean
		PostgresChainIdentityRepository repository(DataSource dataSource) {
			return new PostgresChainIdentityRepository(new JdbcTemplate(dataSource));
		}

		@Bean
		ExplorerChainIdentityGuard guard(
				PostgresChainStoragePreflightProbe probe,
				PostgresChainIdentityRepository repository) {
			return new ExplorerChainIdentityGuard(probe, repository);
		}

		@Bean
		ExplorerSchemaMigrator schemaMigrator() {
			return mock(ExplorerSchemaMigrator.class);
		}

		@Bean
		ExplorerRuntimeReadiness readiness() {
			return new ExplorerRuntimeReadiness();
		}

		@Bean
		AuthoritativeChainIdentityProvider identityProvider() {
			AuthoritativeChainIdentityProvider provider = mock(AuthoritativeChainIdentityProvider.class);
			when(provider.identity()).thenReturn(new StoredChainIdentity(
					1, 0, "mainnet", "0x" + "a".repeat(64), null));
			return provider;
		}

		@Bean(name = ExplorerChainIdentityInitializer.BEAN_NAME)
		ExplorerChainIdentityInitializer initializer(
				AuthoritativeChainIdentityProvider identityProvider,
				ExplorerSchemaMigrator schemaMigrator,
				ExplorerChainIdentityGuard guard,
				ExplorerRuntimeReadiness readiness) {
			return new ExplorerChainIdentityInitializer(identityProvider, schemaMigrator, guard, readiness);
		}

		@Bean(name = "exIndexerCoordinateService")
		GuardedWorker guardedWorker(ExplorerRuntimeReadiness readiness) {
			return new GuardedWorker(readiness);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class EnabledExplorerDependencies {

		private static final StoredChainIdentity IDENTITY = new StoredChainIdentity(
				1, 0, "mainnet", "0x" + "a".repeat(64), null);

		@Bean(name = ChainIdentityBindingInitializer.BEAN_NAME)
		CoreMarker chainIdentityBindingInitializer() {
			return new CoreMarker();
		}

		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource(
					"jdbc:h2:mem:explorer-cycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		}

		@Bean(name = "liquibase")
		SpringLiquibase liquibase() {
			return mock(SpringLiquibase.class);
		}

		@Bean
		PostgresChainIdentityRepository postgresChainIdentityRepository() {
			PostgresChainIdentityRepository repository = mock(PostgresChainIdentityRepository.class);
			when(repository.find()).thenReturn(Optional.of(IDENTITY));
			return repository;
		}

		@Bean
		AuthoritativeChainIdentityProvider authoritativeChainIdentityProvider() {
			AuthoritativeChainIdentityProvider provider = mock(AuthoritativeChainIdentityProvider.class);
			when(provider.identity()).thenReturn(IDENTITY);
			return provider;
		}

		@Bean
		GeneralProperties generalProperties() {
			GeneralProperties properties = new GeneralProperties();
			properties.setExplorerEnable(true);
			return properties;
		}

		@Bean
		SnapshotDistributionProperties snapshotDistributionProperties() {
			return new SnapshotDistributionProperties();
		}

		@Bean
		CoreSnapshotCheckpointFloorPolicy coreSnapshotCheckpointFloorPolicy() {
			CoreSnapshotCheckpointFloorPolicy policy = mock(CoreSnapshotCheckpointFloorPolicy.class);
			when(policy.floor()).thenReturn(Optional.empty());
			return policy;
		}

		@Bean
		ChainQuery chainQuery() {
			return mock(ChainQuery.class);
		}

		@Bean
		ExplorerSnapshotRemoteSource explorerSnapshotRemoteSource() {
			return mock(ExplorerSnapshotRemoteSource.class);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		StateProcessor stateProcessor() {
			return mock(StateProcessor.class);
		}

		@Bean
		BlockEventExtractor blockEventExtractor() {
			return mock(BlockEventExtractor.class);
		}

		@Bean
		ExIndexerService exIndexerService() {
			return mock(ExIndexerService.class);
		}

		@Bean
		ExIndexerStatusCoreService exIndexerStatusCoreService() {
			return mock(ExIndexerStatusCoreService.class);
		}

		@Bean
		ExplorerIndexingExecutionGate explorerIndexingExecutionGate() {
			return new ExplorerIndexingExecutionGate();
		}

		@Bean(name = "exIndexerQueueService")
		ExIndexerQueueService exIndexerQueueService(
				ExplorerChainIdentityInitializer explorerChainIdentityInitializer) {
			return mock(ExIndexerQueueService.class);
		}
	}

	private static final class CoreMarker {
	}

	private static final class GuardedWorker implements InitializingBean {

		private final ExplorerRuntimeReadiness readiness;
		private final AtomicBoolean ran = new AtomicBoolean();

		private GuardedWorker(ExplorerRuntimeReadiness readiness) {
			this.readiness = readiness;
		}

		@Override
		public void afterPropertiesSet() {
			if (readiness.isReady()) {
				ran.set(true);
			}
		}

		boolean ran() {
			return ran.get();
		}
	}
}
