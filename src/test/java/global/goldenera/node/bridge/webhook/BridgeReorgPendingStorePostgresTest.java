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
package global.goldenera.node.bridge.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers(disabledWithoutDocker = true)
class BridgeReorgPendingStorePostgresTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("goldenera")
			.withUsername("goldenera")
			.withPassword("goldenera");

	private static JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrate() throws Exception {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			Database database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
					Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", resources, database)) {
				liquibase.update();
			}
		}
		jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	@Test
	void canonicalFirstSurvivesGateRestartUntilReaddArrives() throws Exception {
		assertRestartedCorrelation(tx(41), true);
	}

	@Test
	void readdFirstSurvivesGateRestartUntilCanonicalRevertArrives() throws Exception {
		assertRestartedCorrelation(tx(42), false);
	}

	private void assertRestartedCorrelation(Tx tx, boolean canonicalFirst) {
		BridgeReorgPendingStore store = new BridgeReorgPendingStore(jdbcTemplate);
		BridgeLifecycleCoordinator coordinator = mock(BridgeLifecycleCoordinator.class);
		MempoolEntry entry = new MempoolEntry(tx, Instant.parse("2026-08-29T10:00:00Z"), 100L, null);
		Block orphan = mock(Block.class);
		when(orphan.getTxs()).thenReturn(List.of(tx));
		BridgeSourcePosition position = new BridgeSourcePosition(UUID.randomUUID(), 7L, UUID.randomUUID());

		if (canonicalFirst) {
			new BridgeReorgPendingGate(coordinator, store).canonicalRevertCommitted(orphan, 6L);
			new BridgeReorgPendingGate(coordinator, store).coreReadded(entry, position);
		} else {
			new BridgeReorgPendingGate(coordinator, store).coreReadded(entry, position);
			new BridgeReorgPendingGate(coordinator, store).canonicalRevertCommitted(orphan, 6L);
		}

		verify(coordinator).pendingAfterReorg(
				argThat(actual -> actual.getHash().equals(tx.getHash())),
				eq(position));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM bridge_reorg_pending WHERE tx_hash = ?",
				Integer.class,
				tx.getHash().toArray())).isZero();
	}

	private Tx tx(int value) throws Exception {
		PrivateKey key = PrivateKey.wrap(Bytes32.fromHexString("0x" + "%064x".formatted(value)));
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.recipient(Address.fromHexString("0x" + "%040x".formatted(value + 1)))
				.amount(Wei.valueOf(1L))
				.fee(Wei.valueOf(1L))
				.nonce(1L)
				.sign(key);
	}
}
