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

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityBindingInitializer;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

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
