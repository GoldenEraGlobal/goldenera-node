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
package global.goldenera.node.explorer.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class ExplorerReadIndexesLiquibaseTest {
	private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:17-alpine")
					.withDatabaseName("goldenera")
					.withUsername("goldenera")
					.withPassword("goldenera");

	private static JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrateDatabase() throws Exception {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(MASTER_CHANGELOG, resources, database)) {
				liquibase.update();
			}
		}

		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Test
	void createsReadOrderAndTrigramIndexes() {
		List<String> indexNames = jdbcTemplate.queryForList(
				"SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()", String.class);

		assertThat(indexNames).contains(
				"idx_explorer_account_nonce_nonce_address",
				"idx_explorer_account_balance_page_order",
				"idx_ex_transfer_page_order",
				"idx_explorer_tx_page_order",
				"idx_explorer_address_alias_lower_alias",
				"idx_explorer_token_name_trgm",
				"idx_explorer_token_smallest_unit_name_trgm");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm')", Boolean.class)).isTrue();
	}

	@Test
	void storesExplorerTransactionInstantsWithTimeZone() {
		List<String> dataTypes = jdbcTemplate.queryForList("""
				SELECT data_type
				FROM information_schema.columns
				WHERE table_schema = current_schema()
				AND table_name IN ('explorer_tx', 'explorer_transfer')
				AND column_name = 'timestamp'
				ORDER BY table_name
				""", String.class);

		assertThat(dataTypes).containsExactly("timestamp with time zone", "timestamp with time zone");
	}

	@Test
	void postgresLikeEscapeTreatsSearchWildcardsLiterally() {
		Boolean literalMatch = jdbcTemplate.queryForObject(
				"SELECT 'ab%_' ILIKE ('%' || ? || '%') ESCAPE '\\'",
				Boolean.class,
				"ab\\%\\_");
		Boolean wildcardOnlyMatch = jdbcTemplate.queryForObject(
				"SELECT 'anything' ILIKE ('%' || ? || '%') ESCAPE '\\'",
				Boolean.class,
				"\\%");

		assertThat(literalMatch).isTrue();
		assertThat(wildcardOnlyMatch).isFalse();
	}
}
