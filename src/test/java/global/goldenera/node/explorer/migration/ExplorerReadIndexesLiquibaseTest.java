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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import global.goldenera.node.explorer.services.core.ExplorerSearchQueryPlan;

@Testcontainers(disabledWithoutDocker = true)
class ExplorerReadIndexesLiquibaseTest {
	private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
	private static final String READ_INDEX_CHANGELOG =
			"db/changelog/changesets/009-explorer-read-indexes.yaml";

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
		assertThat(jdbcTemplate.queryForObject("""
				SELECT bool_and(index_state.indisvalid) AND count(*) = 2
				FROM pg_index index_state
				WHERE index_state.indexrelid IN (
				  'idx_ex_transfer_page_order'::regclass,
				  'idx_explorer_tx_page_order'::regclass)
				""", Boolean.class)).isTrue();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM pg_inherits
				WHERE inhparent IN (
				  'idx_ex_transfer_page_order'::regclass,
				  'idx_explorer_tx_page_order'::regclass)
				""", Integer.class)).isEqualTo(10);
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

	@Test
	void searchPlanAppliesARealPostgresStatementTimeout() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		ExplorerSearchQueryPlan plan = new ExplorerSearchQueryPlan(
				new DataSourceTransactionManager(dataSource), new JdbcTemplate(dataSource));
		long started = System.nanoTime();

		assertThatThrownBy(() -> plan.execute(100L, () -> {
			new JdbcTemplate(dataSource).execute("SELECT pg_sleep(2)");
			return null;
		})).isInstanceOf(RuntimeException.class);

		assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(2).toNanos());
	}

	@Test
	void upgradeInterpretsLegacyTimestampValuesAsUtcWithoutSessionTimezoneDependence() throws Exception {
		String databaseName = "upgrade_009";
		jdbcTemplate.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
		jdbcTemplate.execute("CREATE DATABASE " + databaseName);
		String upgradeUrl = POSTGRES.getJdbcUrl().replace("/goldenera?", "/" + databaseName + "?");
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
		JdbcTemplate upgrade = new JdbcTemplate(dataSource);
		try {
			createLegacy009Schema(upgrade);
			applyReadIndexMigration(upgradeUrl);

			BigDecimal expectedEpoch = BigDecimal.valueOf(
					Instant.parse("2024-01-02T03:04:05.123456Z").getEpochSecond())
					.add(new BigDecimal("0.123456"));
			assertThat(upgrade.queryForObject(
					"SELECT EXTRACT(EPOCH FROM timestamp) FROM explorer_tx", BigDecimal.class))
					.isEqualByComparingTo(expectedEpoch);
			assertThat(upgrade.queryForObject(
					"SELECT EXTRACT(EPOCH FROM timestamp) FROM explorer_transfer", BigDecimal.class))
					.isEqualByComparingTo(expectedEpoch);
			assertThat(upgrade.queryForObject("""
					SELECT col_description('explorer_tx'::regclass,
					  (SELECT attnum FROM pg_attribute
					   WHERE attrelid = 'explorer_tx'::regclass AND attname = 'timestamp'))
					""", String.class)).contains("interpreted as UTC");
			upgrade.update("""
					UPDATE databasechangelog
					SET md5sum = '9:00000000000000000000000000000000'
					WHERE id = '009-explorer-instant-columns-utc'
					""");
			applyReadIndexMigration(upgradeUrl);
		} finally {
			jdbcTemplate.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
		}
	}

	private void applyReadIndexMigration(String jdbcUrl) throws Exception {
		try (Connection connection = DriverManager.getConnection(
				jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(READ_INDEX_CHANGELOG, resources, database)) {
				liquibase.update();
			}
		}
	}

	private void createLegacy009Schema(JdbcTemplate jdbc) {
		jdbc.execute("""
				CREATE TABLE explorer_tx (
				  hash BYTEA NOT NULL, block_height BIGINT NOT NULL, tx_index INTEGER NOT NULL,
				  timestamp TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL
				) PARTITION BY RANGE (block_height);
				CREATE TABLE explorer_transfer (
				  id BIGINT NOT NULL, block_height BIGINT NOT NULL, tx_index INTEGER,
				  timestamp TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL
				) PARTITION BY RANGE (block_height);
				""");
		for (int partition = 0; partition < 5; partition++) {
			long from = partition * 1_000_000L;
			long to = from + 1_000_000L;
			jdbc.execute("CREATE TABLE explorer_tx_p" + partition
					+ " PARTITION OF explorer_tx FOR VALUES FROM (" + from + ") TO (" + to + ")");
			jdbc.execute("CREATE TABLE explorer_transfer_p" + partition
					+ " PARTITION OF explorer_transfer FOR VALUES FROM (" + from + ") TO (" + to + ")");
		}
		jdbc.execute("CREATE TABLE explorer_account_nonce (nonce BIGINT, address BYTEA)");
		jdbc.execute("CREATE TABLE explorer_account_balance (balance NUMERIC, address BYTEA, token_address BYTEA)");
		jdbc.execute("CREATE TABLE explorer_address_alias (alias TEXT)");
		jdbc.execute("CREATE TABLE explorer_token (name TEXT, smallest_unit_name TEXT)");
		jdbc.update("""
				INSERT INTO explorer_tx(hash, block_height, tx_index, timestamp)
				VALUES (decode('01', 'hex'), 1, 0, TIMESTAMP '2024-01-02 03:04:05.123456')
				""");
		jdbc.update("""
				INSERT INTO explorer_transfer(id, block_height, tx_index, timestamp)
				VALUES (1, 1, 0, TIMESTAMP '2024-01-02 03:04:05.123456')
				""");
	}
}
