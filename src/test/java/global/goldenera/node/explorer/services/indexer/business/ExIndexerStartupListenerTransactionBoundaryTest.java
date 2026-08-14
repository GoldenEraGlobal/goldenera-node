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
package global.goldenera.node.explorer.services.indexer.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import global.goldenera.node.core.blockchain.events.CoreDbReadyEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.explorer.repositories.ExBlockHeaderRepository;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerMempoolCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerStartupListenerTransactionBoundaryTest {

	@Test
	void nonReadyListenersDoNotEnterRealTransactionalProxiesWithRefusedPostgres() {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(TestConfiguration.class)) {
			ExIndexerMempoolService mempoolWorker = context.getBean(ExIndexerMempoolService.class);
			ExIndexerSyncService syncWorker = context.getBean(ExIndexerSyncService.class);
			assertThat(AopUtils.isAopProxy(mempoolWorker)).isTrue();
			assertThat(AopUtils.isAopProxy(syncWorker)).isTrue();

			context.publishEvent(new CoreDbReadyEvent(this));

			assertThatThrownBy(mempoolWorker::resetOnCoreReady)
					.isInstanceOf(CannotCreateTransactionException.class)
					.hasMessageContaining("Could not open JDBC Connection");
			assertThatThrownBy(syncWorker::syncExplorerOnStartup)
					.isInstanceOf(CannotCreateTransactionException.class)
					.hasMessageContaining("Could not open JDBC Connection");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement(proxyTargetClass = true)
	static class TestConfiguration {

		@Bean
		GeneralProperties generalProperties() {
			GeneralProperties properties = new GeneralProperties();
			properties.setExplorerEnable(true);
			return properties;
		}

		@Bean
		ExplorerRuntimeReadiness readiness() {
			return new ExplorerRuntimeReadiness();
		}

		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource(
					"jdbc:postgresql://127.0.0.1:1/unavailable", "unavailable", "unavailable");
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean
		ExIndexerMempoolService mempoolWorker(
				GeneralProperties properties,
				ExplorerRuntimeReadiness readiness,
				MeterRegistry meterRegistry) {
			return new ExIndexerMempoolService(
					properties,
					readiness,
					meterRegistry,
					mock(ExIndexerMempoolCoreService.class),
					mock(ThreadPoolTaskScheduler.class));
		}

		@Bean
		ExIndexerSyncService syncWorker() {
			return new ExIndexerSyncService(
					mock(ExBlockHeaderRepository.class),
					mock(ExIndexerStatusCoreService.class),
					mock(ExIndexerQueueService.class),
					mock(ExIndexerRevertService.class),
					mock(ChainQuery.class),
					mock(ExIndexerEventReconstructionService.class));
		}

		@Bean
		ExIndexerMempoolStartupListener mempoolListener(
				GeneralProperties properties,
				ExplorerRuntimeReadiness readiness,
				ObjectProvider<ExIndexerMempoolService> worker) {
			return new ExIndexerMempoolStartupListener(properties, readiness, worker);
		}

		@Bean
		ExIndexerSyncStartupListener syncListener(
				GeneralProperties properties,
				ExplorerRuntimeReadiness readiness,
				ObjectProvider<ExIndexerSyncService> worker) {
			return new ExIndexerSyncStartupListener(properties, readiness, worker);
		}
	}
}
