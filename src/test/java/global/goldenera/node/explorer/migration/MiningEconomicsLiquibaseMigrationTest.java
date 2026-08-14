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
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

class MiningEconomicsLiquibaseMigrationTest {

	private static final String PRE_MIGRATION_CHANGELOG =
			"db/changelog/db.changelog-pre-mining-economics.yaml";
	private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
	private static final String POSTGRES_ONLY_CUSTOM_INDEXES =
			"db/changelog/changesets/003-custom-indexes.yaml";
	private static final String JDBC_URL = "jdbc:h2:mem:mining-economics-migration;"
			+ "MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

	@Test
	void migrationKeepsLegacyRowsNullableAndRollbackRemovesOnlyNewColumns() throws Exception {
		migrate(PRE_MIGRATION_CHANGELOG);
		// Changeset 003 contains PostgreSQL-only sequence SQL without a dbms guard.
		// Mark it as present in this H2 compatibility test; production PostgreSQL runs it.
		syncChangelog(POSTGRES_ONLY_CUSTOM_INDEXES);
		try (Connection connection = openConnection()) {
			insertLegacyRows(connection);
		}

		migrate(MASTER_CHANGELOG);

		try (Connection connection = openConnection()) {
			assertMiningEconomicsColumns(connection, true);
			assertLegacyRowsRemainNullable(connection);
		}

		rollbackLastTwoChangesets();

		try (Connection connection = openConnection()) {
			assertMiningEconomicsColumns(connection, false);
			assertLegacyBaseDataSurvivedRollback(connection);
		}
	}

	private void syncChangelog(String changelog) throws Exception {
		Connection connection = openConnection();
		Database database = DatabaseFactory.getInstance()
				.findCorrectDatabaseImplementation(new JdbcConnection(connection));
		try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
				Liquibase liquibase = new Liquibase(changelog, resources, database)) {
			liquibase.changeLogSync(new Contexts(), new LabelExpression());
		}
	}

	private void migrate(String changelog) throws Exception {
		Connection connection = openConnection();
		Database database = DatabaseFactory.getInstance()
				.findCorrectDatabaseImplementation(new JdbcConnection(connection));
		try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
				Liquibase liquibase = new Liquibase(changelog, resources, database)) {
			liquibase.update(new Contexts(), new LabelExpression());
		}
	}

	private void rollbackLastTwoChangesets() throws Exception {
		Connection connection = openConnection();
		Database database = DatabaseFactory.getInstance()
				.findCorrectDatabaseImplementation(new JdbcConnection(connection));
		try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
				Liquibase liquibase = new Liquibase(MASTER_CHANGELOG, resources, database)) {
			liquibase.rollback(2, new Contexts(), new LabelExpression());
		}
	}

	private Connection openConnection() throws Exception {
		return DriverManager.getConnection(JDBC_URL, "sa", "");
	}

	private void insertLegacyRows(Connection connection) throws Exception {
		Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
		try (PreparedStatement validator = connection.prepareStatement("""
				INSERT INTO explorer_validator
				(address, created_at_block_height, created_at_timestamp, origin_tx_hash, validator_version)
				VALUES (?, ?, ?, ?, ?)
				""")) {
			validator.setBytes(1, new byte[20]);
			validator.setLong(2, 7);
			validator.setObject(3, timestamp);
			validator.setBytes(4, new byte[32]);
			validator.setInt(5, 1);
			validator.executeUpdate();
		}

		try (PreparedStatement params = connection.prepareStatement("""
				INSERT INTO explorer_network_params
				(id, asert_anchor_height, asert_half_life_blocks, block_reward, block_reward_pool_address,
				 current_authority_count, current_validator_count, min_difficulty, min_tx_base_fee,
				 min_tx_byte_fee, target_mining_time_ms, updated_at_block_height, updated_at_timestamp,
				 updated_by_tx_hash, network_params_version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			params.setInt(1, 1);
			params.setLong(2, 1);
			params.setLong(3, 10);
			params.setLong(4, 50);
			params.setBytes(5, new byte[20]);
			params.setLong(6, 2);
			params.setLong(7, 3);
			params.setLong(8, 1);
			params.setLong(9, 1);
			params.setLong(10, 1);
			params.setLong(11, 1_000);
			params.setLong(12, 7);
			params.setObject(13, timestamp);
			params.setBytes(14, new byte[32]);
			params.setInt(15, 1);
			params.executeUpdate();
		}
	}

	private void assertMiningEconomicsColumns(Connection connection, boolean expected) throws Exception {
		assertThat(columnExists(connection, "explorer_validator", "mining_limit_mode")).isEqualTo(expected);
		assertThat(columnExists(connection, "explorer_validator", "policy_updated_at_timestamp")).isEqualTo(expected);
		assertThat(columnExists(connection, "explorer_network_params", "validator_mining_window_blocks"))
				.isEqualTo(expected);
		assertThat(columnExists(connection, "explorer_network_params", "current_unlimited_validator_count"))
				.isEqualTo(expected);
	}

	private void assertLegacyRowsRemainNullable(Connection connection) throws Exception {
		try (Statement statement = connection.createStatement();
				ResultSet validator = statement.executeQuery("""
						SELECT mining_limit_mode, mining_policy_source, max_mining_share_bps,
						       policy_updated_by_tx_hash, policy_updated_at_block_height,
						       policy_updated_at_timestamp
						FROM explorer_validator
						""")) {
			assertThat(validator.next()).isTrue();
			for (int column = 1; column <= 6; column++) {
				assertThat(validator.getObject(column)).isNull();
			}
		}

		try (Statement statement = connection.createStatement();
				ResultSet params = statement.executeQuery("""
						SELECT validator_mining_window_blocks, current_unlimited_validator_count
						FROM explorer_network_params
						""")) {
			assertThat(params.next()).isTrue();
			assertThat(params.getObject(1)).isNull();
			assertThat(params.getObject(2)).isNull();
		}
	}

	private void assertLegacyBaseDataSurvivedRollback(Connection connection) throws Exception {
		try (Statement statement = connection.createStatement();
				ResultSet validator = statement.executeQuery(
						"SELECT created_at_block_height, validator_version FROM explorer_validator")) {
			assertThat(validator.next()).isTrue();
			assertThat(validator.getLong(1)).isEqualTo(7);
			assertThat(validator.getInt(2)).isEqualTo(1);
		}
	}

	private boolean columnExists(Connection connection, String table, String column) throws Exception {
		DatabaseMetaData metadata = connection.getMetaData();
		try (ResultSet columns = metadata.getColumns(null, null,
				table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
			return columns.next();
		}
	}
}
