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
package global.goldenera.node.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class ApiKeyAuthEpochLiquibaseTest {

	private static final String CHANGELOG = "db/changelog/changesets/011-api-key-auth-epoch.yaml";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("goldenera")
			.withUsername("goldenera")
			.withPassword("goldenera");

	@BeforeAll
	static void migrateLegacySchema() throws Exception {
		try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE api_key (
					  id BIGINT PRIMARY KEY,
					  created_at TIMESTAMPTZ NOT NULL,
					  description VARCHAR(255),
					  enabled BOOLEAN NOT NULL,
					  expires_at TIMESTAMPTZ,
					  key_prefix VARCHAR(255) NOT NULL UNIQUE,
					  label VARCHAR(32) NOT NULL,
					  max_webhooks BIGINT,
					  secret_key BYTEA NOT NULL,
					  version BIGINT NOT NULL
					)
					""");
			statement.execute("""
					CREATE TABLE api_key_permission (
					  api_key_id BIGINT NOT NULL REFERENCES api_key(id),
					  permission INTEGER NOT NULL,
					  PRIMARY KEY(api_key_id, permission)
					)
					""");
		}

		try (Connection connection = openConnection()) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(CHANGELOG, resources, database)) {
				liquibase.update();
			}
		}
	}

	@BeforeEach
	void reset() throws Exception {
		try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("TRUNCATE api_key CASCADE");
			statement.execute("UPDATE api_key_auth_epoch SET epoch = 0 WHERE singleton = TRUE");
		}
	}

	@Test
	void directSqlMutationsAdvanceEpochOnlyWhenTheirTransactionCommits() throws Exception {
		try (Connection writer = openConnection()) {
			writer.setAutoCommit(false);
			insertLegacyApiKey(writer, 1L, "sk_legacy");
			assertThat(epoch()).isZero();
			writer.commit();
		}
		long afterCreate = epoch();
		assertThat(afterCreate).isPositive();

		try (Connection writer = openConnection(); Statement statement = writer.createStatement()) {
			writer.setAutoCommit(false);
			statement.executeUpdate("INSERT INTO api_key_permission(api_key_id, permission) VALUES (1, 8)");
			writer.commit();
		}
		long afterPermission = epoch();
		assertThat(afterPermission).isGreaterThan(afterCreate);

		try (Connection writer = openConnection(); Statement statement = writer.createStatement()) {
			writer.setAutoCommit(false);
			statement.executeUpdate("UPDATE api_key SET enabled = FALSE WHERE id = 1");
			assertThat(epoch()).isEqualTo(afterPermission);
			writer.rollback();
		}
		assertThat(epoch()).isEqualTo(afterPermission);
	}

	@Test
	void legacyBinaryStatementsIgnoreTheAdditiveEpochSchema() throws Exception {
		try (Connection connection = openConnection()) {
			insertLegacyApiKey(connection, 2L, "sk_old_binary");
			try (Statement statement = connection.createStatement()) {
				assertThat(statement.executeUpdate(
						"UPDATE api_key SET label = 'old-binary-update', version = version + 1 WHERE id = 2"))
						.isOne();
				assertThat(statement.executeUpdate("DELETE FROM api_key WHERE id = 2")).isOne();
			}
		}

		assertThat(epoch()).isEqualTo(3);
	}

	@Test
	void truncateAlsoInvalidatesSnapshots() throws Exception {
		try (Connection connection = openConnection()) {
			insertLegacyApiKey(connection, 3L, "sk_truncated");
		}
		long beforeTruncate = epoch();

		try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("TRUNCATE api_key CASCADE");
		}

		assertThat(epoch()).isGreaterThan(beforeTruncate);
	}

	private static void insertLegacyApiKey(Connection connection, long id, String prefix) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO api_key(
				  id, created_at, description, enabled, expires_at, key_prefix, label,
				  max_webhooks, secret_key, version)
				VALUES (?, now(), NULL, TRUE, NULL, ?, 'legacy', NULL, decode('01', 'hex'), 0)
				""")) {
			statement.setLong(1, id);
			statement.setString(2, prefix);
			statement.executeUpdate();
		}
	}

	private static long epoch() throws Exception {
		try (Connection connection = openConnection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT epoch FROM api_key_auth_epoch WHERE singleton = TRUE")) {
			result.next();
			return result.getLong(1);
		}
	}

	private static Connection openConnection() throws Exception {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}
