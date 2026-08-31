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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import global.goldenera.node.core.storage.chainidentity.ChainStorageGuardException;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

class PostgresChainIdentityRepositoryTest {

	private static final String CHANGELOG = "db/changelog/changesets/006-explorer-chain-identity.yaml";
	private static final StoredChainIdentity IDENTITY = new StoredChainIdentity(
			1, 1, "sandbox", "0x" + "a".repeat(64), "b".repeat(64));
	private static final StoredChainIdentity OTHER_IDENTITY = new StoredChainIdentity(
			1, 1, "other", "0x" + "c".repeat(64), "d".repeat(64));

	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void migrateSchema() throws Exception {
		String url = "jdbc:h2:mem:chain-identity-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
		Connection connection = DriverManager.getConnection(url, "sa", "");
		Database database = DatabaseFactory.getInstance()
				.findCorrectDatabaseImplementation(new JdbcConnection(connection));
		try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
				Liquibase liquibase = new Liquibase(CHANGELOG, resources, database)) {
			liquibase.update(new Contexts(), new LabelExpression());
		}
		jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
	}

	@Test
	void readsIdentityCreatedInTheMigratedSingletonTable() {
		PostgresChainIdentityRepository repository = new PostgresChainIdentityRepository(jdbcTemplate);

		assertThat(repository.find()).isEmpty();
		jdbcTemplate.update("""
				INSERT INTO explorer_chain_identity
					(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
				VALUES (1, ?, ?, ?, ?, ?)
				""", IDENTITY.formatVersion(), IDENTITY.carrierNetworkCode(), IDENTITY.chainId(),
				IDENTITY.genesisHash(), IDENTITY.manifestFingerprint());

		assertThat(repository.find()).contains(IDENTITY);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM explorer_chain_identity", Integer.class))
				.isOne();
	}

	@Test
	void bindingUsesPostgresConflictProtectionAndAllIdentityFields() {
		JdbcTemplate mockedJdbc = mock(JdbcTemplate.class);
		PostgresChainIdentityRepository repository = new PostgresChainIdentityRepository(mockedJdbc);

		repository.bindIfAbsent(OTHER_IDENTITY);

		verify(mockedJdbc).update(
				contains("ON CONFLICT (id) DO NOTHING"),
				eq(OTHER_IDENTITY.formatVersion()),
				eq(OTHER_IDENTITY.carrierNetworkCode()),
				eq(OTHER_IDENTITY.chainId()),
				eq(OTHER_IDENTITY.genesisHash()),
				eq(OTHER_IDENTITY.manifestFingerprint()));
	}

	@Test
	void databaseConstraintEnforcesTheSingleRowIdentity() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO explorer_chain_identity
					(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
				VALUES (2, 1, 1, 'sandbox', ?, ?)
				""", IDENTITY.genesisHash(), IDENTITY.manifestFingerprint()))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	void databaseConstraintIsAttachedToGenesisHashRatherThanChainId() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO explorer_chain_identity
					(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
				VALUES (1, 1, 1, 'sandbox', 'not-a-genesis-hash', ?)
				""", IDENTITY.manifestFingerprint()))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	void databaseConstraintRejectsMalformedManifestFingerprint() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO explorer_chain_identity
					(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
				VALUES (1, 1, 1, 'sandbox', ?, 'not-a-fingerprint')
				""", IDENTITY.genesisHash()))
				.isInstanceOf(RuntimeException.class);
	}
}
