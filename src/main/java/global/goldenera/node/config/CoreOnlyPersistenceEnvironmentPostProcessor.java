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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Converts the explicit PostgreSQL disable switch into an early SQL auto-config
 * boundary and rejects feature combinations that require PostgreSQL.
 */
public final class CoreOnlyPersistenceEnvironmentPostProcessor
		implements EnvironmentPostProcessor, Ordered {

	static final String PROPERTY_SOURCE = "goldeneraCoreOnlyPersistence";

	private static final String EXPLORER_ENABLED = "ge.general.explorer-enable";
	private static final String POSTGRESQL_ENABLED = "ge.general.postgresql-enable";
	private static final String WEBHOOK_ENABLED = "ge.general.webhook-enable";
	private static final String CORE_API_SECURITY_ENABLED = "ge.security.core-api-enabled";
	private static final String AUTO_CONFIG_EXCLUDES = "spring.autoconfigure.exclude";

	private static final Set<String> SQL_AUTO_CONFIGURATIONS = Set.of(
			DataSourceAutoConfiguration.class.getName(),
			DataSourceTransactionManagerAutoConfiguration.class.getName(),
			JdbcTemplateAutoConfiguration.class.getName(),
			LiquibaseAutoConfiguration.class.getName(),
			HibernateJpaAutoConfiguration.class.getName());

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		boolean explorerEnabled = enabled(environment, EXPLORER_ENABLED, true);
		boolean postgresqlEnabled = enabled(environment, POSTGRESQL_ENABLED, explorerEnabled);
		boolean webhookEnabled = enabled(environment, WEBHOOK_ENABLED, postgresqlEnabled);
		boolean coreApiSecurityEnabled = enabled(environment, CORE_API_SECURITY_ENABLED, false);

		validateDependencies(explorerEnabled, postgresqlEnabled, webhookEnabled, coreApiSecurityEnabled);
		if (postgresqlEnabled) {
			return;
		}
		Set<String> exclusions = new LinkedHashSet<>();
		String configured = environment.getProperty(AUTO_CONFIG_EXCLUDES);
		if (StringUtils.hasText(configured)) {
			Arrays.stream(StringUtils.commaDelimitedListToStringArray(configured))
					.map(String::trim)
					.filter(StringUtils::hasText)
					.forEach(exclusions::add);
		}
		exclusions.addAll(SQL_AUTO_CONFIGURATIONS);
		environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of(
				AUTO_CONFIG_EXCLUDES, String.join(",", exclusions),
				"spring.liquibase.enabled", "false")));
	}

	private boolean enabled(
			ConfigurableEnvironment environment,
			String property,
			boolean defaultValue) {
		String configured = environment.getProperty(property);
		return StringUtils.hasText(configured) ? Boolean.parseBoolean(configured) : defaultValue;
	}

	private void validateDependencies(
			boolean explorerEnabled,
			boolean postgresqlEnabled,
			boolean webhookEnabled,
			boolean coreApiSecurityEnabled) {
		if (postgresqlEnabled) {
			return;
		}
		if (explorerEnabled) {
			throw dependencyError(EXPLORER_ENABLED);
		}
		if (webhookEnabled) {
			throw dependencyError(WEBHOOK_ENABLED);
		}
		if (coreApiSecurityEnabled) {
			throw dependencyError(CORE_API_SECURITY_ENABLED);
		}
	}

	private IllegalStateException dependencyError(String feature) {
		return new IllegalStateException(feature + "=true requires " + POSTGRESQL_ENABLED + "=true");
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
