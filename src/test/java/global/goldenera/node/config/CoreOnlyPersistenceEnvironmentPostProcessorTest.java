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
package global.goldenera.node.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

import global.goldenera.node.explorer.storage.chainidentity.PostgresChainIdentityRepository;

class CoreOnlyPersistenceEnvironmentPostProcessorTest {

	@Test
	void explicitCoreOnlyModeAddsSqlExclusionsAndPreservesExistingExclusions() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("ge.general.explorer-enable", "false")
				.withProperty("spring.autoconfigure.exclude", "example.ExistingAutoConfiguration");

		new CoreOnlyPersistenceEnvironmentPostProcessor()
				.postProcessEnvironment(environment, new SpringApplication());

		String exclusions = environment.getRequiredProperty("spring.autoconfigure.exclude");
		assertThat(exclusions)
				.contains("example.ExistingAutoConfiguration")
				.contains(DataSourceAutoConfiguration.class.getName())
				.contains(HibernateJpaAutoConfiguration.class.getName())
				.contains(LiquibaseAutoConfiguration.class.getName());
		assertThat(environment.getProperty("spring.liquibase.enabled", Boolean.class)).isFalse();
	}

	@Test
	void productionDefaultDoesNotDisableSqlRuntime() {
		MockEnvironment environment = new MockEnvironment();

		new CoreOnlyPersistenceEnvironmentPostProcessor()
				.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getPropertySources()
				.contains(CoreOnlyPersistenceEnvironmentPostProcessor.PROPERTY_SOURCE)).isFalse();
	}

	@Test
	void postgresqlCanRemainEnabledWithoutExplorer() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("ge.general.explorer-enable", "false")
				.withProperty("ge.general.postgresql-enable", "true");

		new CoreOnlyPersistenceEnvironmentPostProcessor()
				.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getPropertySources()
				.contains(CoreOnlyPersistenceEnvironmentPostProcessor.PROPERTY_SOURCE)).isFalse();
	}

	@Test
	void coreApiSecurityRequiresPostgresql() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("ge.general.explorer-enable", "false")
				.withProperty("ge.general.postgresql-enable", "false")
				.withProperty("ge.security.core-api-enabled", "true");

		assertThatThrownBy(() -> new CoreOnlyPersistenceEnvironmentPostProcessor()
				.postProcessEnvironment(environment, new SpringApplication()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("ge.security.core-api-enabled=true requires ge.general.postgresql-enable=true");
	}

	@Test
	void webhooksRequirePostgresql() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("ge.general.explorer-enable", "false")
				.withProperty("ge.general.postgresql-enable", "false")
				.withProperty("ge.general.webhook-enable", "true");

		assertThatThrownBy(() -> new CoreOnlyPersistenceEnvironmentPostProcessor()
				.postProcessEnvironment(environment, new SpringApplication()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("ge.general.webhook-enable=true requires ge.general.postgresql-enable=true");
	}

	@Test
	void explorerRequiresPostgresql() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("ge.general.explorer-enable", "true")
				.withProperty("ge.general.postgresql-enable", "false")
				.withProperty("ge.general.webhook-enable", "false");

		assertThatThrownBy(() -> new CoreOnlyPersistenceEnvironmentPostProcessor()
				.postProcessEnvironment(environment, new SpringApplication()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("ge.general.explorer-enable=true requires ge.general.postgresql-enable=true");
	}

	@Test
	void coreOnlyContextStartsWithUnreachablePostgresAndNoSqlBeans() {
		SpringApplication application = new SpringApplication(CoreOnlyProbeApplication.class);
		application.setInitializers(List.of());
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setDefaultProperties(Map.of(
				"ge.general.explorer-enable", "false",
				"spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unavailable",
				"spring.datasource.username", "unavailable",
				"spring.datasource.password", "unavailable"));

		try (ConfigurableApplicationContext context = application.run(
				"--spring.config.location=optional:classpath:/core-only-test-does-not-exist.properties",
				"--ge.general.explorer-enable=false")) {
			assertThat(context.getBean(CoreRuntimeMarker.class)).isNotNull();
			assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
			assertThat(context.containsBean("entityManagerFactory")).isFalse();
			assertThat(context.containsBean("liquibase")).isFalse();
		}
	}

	@Test
	void coreOnlyPropertyDoesNotScanExplorerPersistenceComponents() {
		new ApplicationContextRunner()
				.withUserConfiguration(ExplorerPersistenceConfiguration.class)
				.withPropertyValues("ge.general.explorer-enable=false")
				.run(context -> assertThat(context)
						.doesNotHaveBean(PostgresChainIdentityRepository.class));
	}

	@EnableAutoConfiguration
	static class CoreOnlyProbeApplication {

		@Bean
		CoreRuntimeMarker coreRuntimeMarker() {
			return new CoreRuntimeMarker();
		}
	}

	private static final class CoreRuntimeMarker {
	}
}
