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

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import liquibase.integration.spring.SpringLiquibase;

/** Explorer-owned PostgreSQL identity mirror wiring. */
@Configuration(proxyBeanMethods = false)
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

	@Bean(name = ExplorerChainIdentityInitializer.BEAN_NAME)
	ExplorerChainIdentityInitializer explorerChainIdentityInitializer(
			AuthoritativeChainIdentityProvider authoritativeIdentityProvider,
			ExplorerSchemaMigrator schemaMigrator,
			ExplorerChainIdentityGuard guard,
			ExplorerRuntimeReadiness readiness) {
		return new ExplorerChainIdentityInitializer(
				authoritativeIdentityProvider, schemaMigrator, guard, readiness);
	}
}
