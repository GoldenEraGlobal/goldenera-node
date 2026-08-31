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
package global.goldenera.node.explorer.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloor;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

class ExplorerCheckpointSnapshotIntegrationTest {

	private static final String CHANGELOG = "db/changelog/db.changelog-explorer-snapshot-test.yaml";
	private static final byte[] GENESIS = bytes(1);
	private static final byte[] CHECKPOINT = bytes(2);
	private static final byte[] STATE_ROOT = bytes(3);
	private static final ExplorerSnapshotBinding BINDING = new ExplorerSnapshotBinding(
			1, "sandbox", hex(GENESIS), 42, hex(CHECKPOINT), hex(STATE_ROOT), "4".repeat(64), "5".repeat(64));

	@TempDir
	Path tempDirectory;

	@Test
	void roundTripsAllWhitelistedTablesAndLeavesChainIdentityUntouched() throws Exception {
		DatabaseFixture source = database("source");
		DatabaseFixture target = database("target");
		seedCheckpoint(source.jdbc());
		seedIdentity(target.jdbc(), "b".repeat(64));

		Path first = tempDirectory.resolve("first");
		Path second = tempDirectory.resolve("second");
		ExplorerCheckpointSnapshotExporter exporter =
				new ExplorerCheckpointSnapshotExporter(source.dataSource(), new ObjectMapper());
		ExplorerSnapshotManifest firstManifest = exporter.export(BINDING, first, 64 * 1024);
		ExplorerSnapshotManifest secondManifest = exporter.export(BINDING, second, 64 * 1024);

		assertThat(firstManifest.signingHash()).isEqualTo(secondManifest.signingHash());
		assertThat(firstManifest.tableRowCounts()).hasSize(ExplorerSnapshotTable.values().length);
		assertThat(firstManifest.tableRowCounts().get("explorer_block_header")).isEqualTo(1);
		assertThat(firstManifest.tableRowCounts().get("explorer_status")).isEqualTo(1);
		assertThat(firstManifest.tableRowCounts()).doesNotContainKeys(
				"explorer_mem_transfer", "explorer_chain_identity", "api_key", "webhook", "bridge_delivery");

		PreparedExplorerSnapshotImport prepared = new ExplorerCheckpointSnapshotImporter(
				target.dataSource(), new ObjectMapper()).importIntoEmptySchema(BINDING, first);

		assertThat(prepared.binding()).isEqualTo(BINDING);
		assertThat(prepared.importedRows()).isEqualTo(4);
		assertThat(target.jdbc().queryForObject("SELECT COUNT(*) FROM explorer_block_header", Long.class)).isOne();
		assertThat(target.jdbc().queryForObject("SELECT synced_block_height FROM explorer_status", Long.class))
				.isEqualTo(42);
		assertThat(target.jdbc().queryForObject("SELECT user_burnable FROM explorer_token", Boolean.class)).isTrue();
		assertThat(target.jdbc().queryForObject("SELECT old_value FROM explorer_revert_log", String.class))
				.contains("balance").contains("7");
		assertThat(target.jdbc().queryForObject(
				"SELECT manifest_fingerprint FROM explorer_chain_identity WHERE id = 1", String.class))
				.isEqualTo("b".repeat(64));

		CoreSnapshotCheckpointFloor floor = new CoreSnapshotCheckpointFloor(
				42, Hash.wrap(CHECKPOINT), Hash.wrap(STATE_ROOT), BigInteger.valueOf(2_000),
				Hash.fromHexString("0x" + "4".repeat(64)), Hash.fromHexString("0x" + "5".repeat(64)));
		StoredBlock canonical = mock(StoredBlock.class);
		when(canonical.getHash()).thenReturn(Hash.wrap(CHECKPOINT));
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getStoredBlockHeaderByHeight(42)).thenReturn(Optional.of(canonical));
		ExplorerSnapshotRemoteSource remote = mock(ExplorerSnapshotRemoteSource.class);
		ExplorerSnapshotBootstrapService bootstrap = new ExplorerSnapshotBootstrapService(
				new SnapshotDistributionProperties(), CoreSnapshotCheckpointFloorPolicy.enforcing(floor),
				chainQuery, target.dataSource(), remote,
				new ExplorerCheckpointSnapshotImporter(target.dataSource(), new ObjectMapper()));

		assertThat(bootstrap.prepareForIndexing(new StoredChainIdentity(
				1, 1, "sandbox", hex(GENESIS), "b".repeat(64))))
				.isEqualTo(ExplorerSnapshotBootstrapService.Outcome.ALREADY_INDEXED);
		verify(remote, never()).stageFromFirstTrustedSource(any());
	}

	@Test
	void rejectsCoreBindingMismatchBeforeWritingAnything() throws Exception {
		DatabaseFixture source = database("source-binding");
		DatabaseFixture target = database("target-binding");
		seedCheckpoint(source.jdbc());
		Path snapshot = tempDirectory.resolve("binding");
		new ExplorerCheckpointSnapshotExporter(source.dataSource(), new ObjectMapper())
				.export(BINDING, snapshot, 64 * 1024);
		ExplorerSnapshotBinding wrongCoreArchive = new ExplorerSnapshotBinding(
				BINDING.carrierNetworkCode(), BINDING.chainId(), BINDING.genesisHash(), BINDING.checkpointHeight(),
				BINDING.checkpointHash(), BINDING.checkpointStateRoot(), BINDING.coreStateSigningHash(),
				"6".repeat(64));

		assertThatThrownBy(() -> new ExplorerCheckpointSnapshotImporter(target.dataSource(), new ObjectMapper())
				.importIntoEmptySchema(wrongCoreArchive, snapshot))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("activated core checkpoint");
		assertExplorerTablesEmpty(target.jdbc());
	}

	@Test
	void rejectsCorruptChunkWithoutPartialImport() throws Exception {
		DatabaseFixture source = database("source-corrupt");
		DatabaseFixture target = database("target-corrupt");
		seedCheckpoint(source.jdbc());
		Path snapshot = tempDirectory.resolve("corrupt");
		ExplorerSnapshotManifest manifest = new ExplorerCheckpointSnapshotExporter(
				source.dataSource(), new ObjectMapper()).export(BINDING, snapshot, 64 * 1024);
		Path chunk = snapshot.resolve(manifest.chunks().get(0).fileName());
		byte[] corrupt = Files.readAllBytes(chunk);
		corrupt[corrupt.length - 1] ^= 1;
		Files.write(chunk, corrupt);

		assertThatThrownBy(() -> new ExplorerCheckpointSnapshotImporter(target.dataSource(), new ObjectMapper())
				.importIntoEmptySchema(BINDING, snapshot))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("digest mismatch");
		assertExplorerTablesEmpty(target.jdbc());
	}

	@Test
	void rollsBackEarlierTablesWhenAStagingConstraintRejectsALaterTable() throws Exception {
		DatabaseFixture source = database("source-rollback");
		DatabaseFixture target = database("target-rollback");
		seedCheckpoint(source.jdbc());
		Path snapshot = tempDirectory.resolve("rollback");
		new ExplorerCheckpointSnapshotExporter(source.dataSource(), new ObjectMapper())
				.export(BINDING, snapshot, 64 * 1024);
		target.jdbc().execute("""
				ALTER TABLE explorer_token ADD CONSTRAINT reject_snapshot_token
				CHECK (name <> 'Snapshot Token')
				""");

		assertThatThrownBy(() -> new ExplorerCheckpointSnapshotImporter(target.dataSource(), new ObjectMapper())
				.importIntoEmptySchema(BINDING, snapshot))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("Cannot import explorer checkpoint snapshot");
		assertExplorerTablesEmpty(target.jdbc());
	}

	@Test
	void rejectsExplorerThatIsNotExactlyAtCheckpointAndCleansStagingDirectory() throws Exception {
		DatabaseFixture source = database("source-stale");
		seedCheckpoint(source.jdbc());
		source.jdbc().update("UPDATE explorer_status SET synced_block_height = 41");
		Path destination = tempDirectory.resolve("stale");

		assertThatThrownBy(() -> new ExplorerCheckpointSnapshotExporter(source.dataSource(), new ObjectMapper())
				.export(BINDING, destination, 64 * 1024))
				.isInstanceOf(ExplorerSnapshotException.class)
				.hasMessageContaining("not indexed exactly");
		assertThat(destination).doesNotExist();
		try (var children = Files.list(tempDirectory)) {
			assertThat(children.map(path -> path.getFileName().toString()))
					.noneMatch(name -> name.contains("staging"));
		}
	}

	private static DatabaseFixture database(String label) throws Exception {
		String url = "jdbc:h2:mem:explorer-snapshot-" + label + "-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH";
		try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase(CHANGELOG, resources, database)) {
				liquibase.update(new Contexts(), new LabelExpression());
			}
		}
		DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
		return new DatabaseFixture(dataSource, new JdbcTemplate(dataSource));
	}

	private static void seedCheckpoint(JdbcTemplate jdbc) {
		OffsetDateTime timestamp = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000, ZoneOffset.UTC);
		seedIdentity(jdbc, "a".repeat(64));
		jdbc.update("""
				INSERT INTO explorer_block_header
				(hash, block_reward, coinbase, cumulative_difficulty, difficulty, height, identity, nonce,
				 number_of_txs, previous_hash, signature, block_size, state_root_hash, timestamp,
				 total_fees, tx_root_hash, block_version)
				VALUES (?, 50, ?, 2000, 20, 42, NULL, 7, 0, ?, NULL, 512, ?, ?, 3, ?, 1)
				""", CHECKPOINT, Arrays.copyOf(bytes(8), 20), bytes(9), STATE_ROOT, timestamp, bytes(10));
		jdbc.update("""
				INSERT INTO explorer_status
				(id, app_version, last_updated_at, synced_block_hash, synced_block_height)
				VALUES (1, 'test', ?, ?, 42)
				""", timestamp, CHECKPOINT);
		jdbc.update("""
				INSERT INTO explorer_token
				(address, created_at_block_height, created_at_timestamp, logo_url, max_supply, name,
				 number_of_decimals, origin_tx_hash, smallest_unit_name, total_supply, updated_at_block_height,
				 updated_at_timestamp, updated_by_tx_hash, user_burnable, token_state_version, website_url)
				VALUES (?, 42, ?, NULL, 1000000, 'Snapshot Token', 8, ?, 'unit', 12345, 42, ?, ?, TRUE, 1, NULL)
				""", Arrays.copyOf(bytes(11), 20), timestamp, bytes(12), timestamp, bytes(13));
		jdbc.update("""
				INSERT INTO explorer_revert_log
				(id, block_hash, block_height, entity_type, old_value, operation_type, ref_key_1, ref_key_2)
				VALUES (11, ?, 42, 1, '{"balance":"7"}', 1, ?, NULL)
				""", CHECKPOINT, bytes(14));
	}

	private static void seedIdentity(JdbcTemplate jdbc, String fingerprint) {
		jdbc.update("""
				INSERT INTO explorer_chain_identity
				(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
				VALUES (1, 1, 1, 'sandbox', ?, ?)
				""", hex(GENESIS), fingerprint);
	}

	private static void assertExplorerTablesEmpty(JdbcTemplate jdbc) {
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table.tableName(), Long.class))
					.as(table.tableName()).isZero();
		}
	}

	private static byte[] bytes(int value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	private static String hex(byte[] value) {
		return ExplorerCheckpointSnapshotExporter.hex(value);
	}

	private record DatabaseFixture(DataSource dataSource, JdbcTemplate jdbc) {
	}
}
