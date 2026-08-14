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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisConfigLoader;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.storage.chainidentity.ChainStoragePreflightObservation;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

class PostgresChainStoragePreflightProbeTest {

	private static final String GENESIS =
			"924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f";

	@TempDir
	Path tempDirectory;

	@Test
	void freshInspectionDoesNotCreateIdentityTable() {
		DriverManagerDataSource dataSource = dataSource();
		PostgresChainStoragePreflightProbe probe = new PostgresChainStoragePreflightProbe(dataSource);

		ChainStoragePreflightObservation observation = probe.inspect();

		assertThat(observation.identityStorageExists()).isFalse();
		assertThat(observation.identity()).isEmpty();
		assertThat(observation.hasChainData()).isFalse();
		Integer tableCount = new JdbcTemplate(dataSource).queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE lower(table_name) = 'explorer_chain_identity'
				""", Integer.class);
		assertThat(tableCount).isZero();
	}

	@Test
	void identityOnlyRestartDoesNotReportIndexedChainData() {
		DriverManagerDataSource dataSource = dataSource();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("""
				CREATE TABLE explorer_chain_identity (
				 id SMALLINT PRIMARY KEY, format_version INT NOT NULL, carrier_network_code INT NOT NULL,
				 chain_id VARCHAR(128) NOT NULL, genesis_hash VARCHAR(66) NOT NULL,
				 manifest_fingerprint VARCHAR(64))
				""");
		jdbc.update("""
				INSERT INTO explorer_chain_identity
				(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
				VALUES (1, 1, 0, 'mainnet', ?, NULL)
				""", "0x" + GENESIS);

		ChainStoragePreflightObservation observation =
				new PostgresChainStoragePreflightProbe(dataSource).inspect();

		assertThat(observation.identity()).contains(new StoredChainIdentity(
				1, 0, "mainnet", "0x" + GENESIS, null));
		assertThat(observation.hasChainData()).isFalse();
		assertThat(observation.observedGenesisHash()).isEmpty();
	}

	@Test
	void readsIdentityExplorerOccupancyAndReconstructsGenesisWithoutMutation() throws Exception {
		DriverManagerDataSource dataSource = dataSource();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		Block genesis = mainnetGenesis();
		jdbc.execute("""
				CREATE TABLE explorer_chain_identity (
				 id SMALLINT PRIMARY KEY, format_version INT NOT NULL, carrier_network_code INT NOT NULL,
				 chain_id VARCHAR(128) NOT NULL, genesis_hash VARCHAR(66) NOT NULL,
				 manifest_fingerprint VARCHAR(64))
				""");
		jdbc.update("""
				INSERT INTO explorer_chain_identity
				(id, format_version, carrier_network_code, chain_id, genesis_hash, manifest_fingerprint)
				VALUES (1, 1, 0, 'mainnet', ?, NULL)
				""", "0x" + GENESIS);
		jdbc.execute("""
				CREATE TABLE explorer_block_header (
				 hash BYTEA NOT NULL, block_version INT NOT NULL, height BIGINT NOT NULL,
				 timestamp TIMESTAMP WITH TIME ZONE NOT NULL, previous_hash BYTEA NOT NULL,
				 tx_root_hash BYTEA NOT NULL, state_root_hash BYTEA NOT NULL,
				 difficulty NUMERIC NOT NULL, coinbase BYTEA NOT NULL, nonce BIGINT NOT NULL,
				 signature BYTEA, identity BYTEA NOT NULL, block_size INT NOT NULL,
				 cumulative_difficulty NUMERIC NOT NULL, number_of_txs INT NOT NULL)
				""");
		var header = genesis.getHeader();
		jdbc.update("""
				INSERT INTO explorer_block_header
				(hash, block_version, height, timestamp, previous_hash, tx_root_hash,
				 state_root_hash, difficulty, coinbase, nonce, signature, identity,
				 block_size, cumulative_difficulty, number_of_txs)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				header.getHash().toArray(), header.getVersion().getCode(), header.getHeight(),
				Timestamp.from(header.getTimestamp()), header.getPreviousHash().toArray(),
				header.getTxRootHash().toArray(), header.getStateRootHash().toArray(),
				new BigDecimal(header.getDifficulty()), header.getCoinbase().toArray(),
				header.getNonce(), header.getSignature().toArray(), header.getIdentity().toArray(), header.getSize(),
				new BigDecimal(header.getDifficulty()), genesis.getTxs().size());

		ChainStoragePreflightObservation observation =
				new PostgresChainStoragePreflightProbe(dataSource).inspect();

		assertThat(observation.identityStorageExists()).isTrue();
		assertThat(observation.identity()).contains(new StoredChainIdentity(
				1, 0, "mainnet", "0x" + GENESIS, null));
		assertThat(observation.hasChainData()).isTrue();
		assertThat(observation.observedGenesisHash()).contains("0x" + GENESIS);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM explorer_chain_identity", Integer.class)).isOne();
	}

	private Block mainnetGenesis() throws Exception {
		GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(Network.MAINNET, "prod");
		NetworkSettings settings = NetworkSettings.fromGenesisSettings(genesis, Network.MAINNET, "prod");
		try (PersistentWorldStateTestSupport storage =
				new PersistentWorldStateTestSupport(tempDirectory.resolve("state"))) {
			return new GenesisCandidateFactory(storage.factory()).create(settings, 0L).block();
		}
	}

	private DriverManagerDataSource dataSource() {
		return new DriverManagerDataSource(
				"jdbc:h2:mem:preflight_" + UUID.randomUUID()
						+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
				"sa", "");
	}
}
