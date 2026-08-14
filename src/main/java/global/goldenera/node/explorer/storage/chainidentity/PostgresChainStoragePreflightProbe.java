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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.sql.DataSource;

import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.node.core.storage.chainidentity.ChainStorageGuardException;
import global.goldenera.node.core.storage.chainidentity.ChainStoragePreflightObservation;
import global.goldenera.node.core.storage.chainidentity.ChainStoragePreflightProbe;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

/**
 * Uses JDBC metadata and read-only SELECT statements only. In particular, it
 * never creates the identity table that Liquibase owns.
 */
public final class PostgresChainStoragePreflightProbe implements ChainStoragePreflightProbe {

	private static final String IDENTITY_TABLE = "explorer_chain_identity";
	private static final String GENESIS_TABLE = "explorer_block_header";
	private static final String TRANSACTION_TABLE = "explorer_tx";

	private final DataSource dataSource;

	public PostgresChainStoragePreflightProbe(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public ChainStoragePreflightObservation inspect() {
		try (Connection connection = dataSource.getConnection()) {
			connection.setReadOnly(true);
			List<TableReference> tables = tables(connection);
			Optional<TableReference> identityTable = findTable(tables, IDENTITY_TABLE);
			Optional<StoredChainIdentity> identity = identityTable.flatMap(table -> readIdentity(connection, table));
			List<TableReference> explorerTables = tables.stream()
					.filter(table -> table.name().toLowerCase(Locale.ROOT).startsWith("explorer_"))
					.filter(table -> !table.name().equalsIgnoreCase(IDENTITY_TABLE))
					.toList();
			boolean occupied = explorerTables.stream().anyMatch(table -> hasRows(connection, table));
			Optional<TableReference> transactionTable = findTable(tables, TRANSACTION_TABLE);
			Optional<String> genesisHash = findTable(tables, GENESIS_TABLE)
					.flatMap(table -> readAndVerifyGenesisHeader(connection, table, transactionTable));
			return new ChainStoragePreflightObservation(
					"PostgreSQL", identityTable.isPresent(), identity, occupied, genesisHash);
		} catch (SQLException e) {
			throw new ChainStorageGuardException("Failed to inspect PostgreSQL before database migration", e);
		}
	}

	private List<TableReference> tables(Connection connection) throws SQLException {
		DatabaseMetaData metadata = connection.getMetaData();
		List<TableReference> tables = new ArrayList<>();
		try (ResultSet resultSet = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%",
				new String[] { "TABLE", "PARTITIONED TABLE" })) {
			while (resultSet.next()) {
				tables.add(new TableReference(resultSet.getString("TABLE_SCHEM"), resultSet.getString("TABLE_NAME")));
			}
		}
		return List.copyOf(tables);
	}

	private Optional<TableReference> findTable(List<TableReference> tables, String name) {
		return tables.stream().filter(table -> table.name().equalsIgnoreCase(name)).findFirst();
	}

	private Optional<StoredChainIdentity> readIdentity(Connection connection, TableReference table) {
		String sql = "SELECT id, format_version, carrier_network_code, chain_id, genesis_hash, "
				+ "manifest_fingerprint FROM " + table.sqlName() + " ORDER BY id FETCH FIRST 2 ROWS ONLY";
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			if (!rows.next()) {
				return Optional.empty();
			}
			if (rows.getInt("id") != 1) {
				throw new ChainStorageGuardException("PostgreSQL chain identity row has an invalid singleton ID");
			}
			StoredChainIdentity identity = new StoredChainIdentity(
					rows.getInt("format_version"),
					rows.getInt("carrier_network_code"),
					rows.getString("chain_id"),
					rows.getString("genesis_hash"),
					rows.getString("manifest_fingerprint"));
			if (rows.next()) {
				throw new ChainStorageGuardException("PostgreSQL chain identity table contains multiple rows");
			}
			return Optional.of(identity);
		} catch (SQLException e) {
			throw new ChainStorageGuardException("Failed to read existing PostgreSQL chain identity", e);
		}
	}

	private boolean hasRows(Connection connection, TableReference table) {
		String sql = "SELECT 1 FROM " + table.sqlName() + " FETCH FIRST 1 ROW ONLY";
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			return rows.next();
		} catch (SQLException e) {
			throw new ChainStorageGuardException("Failed to inspect PostgreSQL explorer table " + table.name(), e);
		}
	}

	private Optional<String> readAndVerifyGenesisHeader(
			Connection connection,
			TableReference table,
			Optional<TableReference> transactionTable) {
		String sql = "SELECT hash, block_version, height, timestamp, previous_hash, tx_root_hash, "
				+ "state_root_hash, difficulty, coinbase, nonce, signature, identity, block_size, "
				+ "cumulative_difficulty, number_of_txs FROM " + table.sqlName()
				+ " WHERE height = 0 FETCH FIRST 2 ROWS ONLY";
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			if (!rows.next()) {
				return Optional.empty();
			}
			byte[] storedHash = requiredBytes(rows, "hash", 32);
			long height = rows.getLong("height");
			if (rows.wasNull() || height != 0) {
				throw new ChainStorageGuardException("PostgreSQL explorer genesis height is invalid");
			}
			long nonce = rows.getLong("nonce");
			if (rows.wasNull()) {
				throw new ChainStorageGuardException("PostgreSQL explorer genesis nonce is missing");
			}
			BlockHeaderImpl header = BlockHeaderImpl.builder()
					.version(BlockVersion.fromCode(rows.getInt("block_version")))
					.height(height)
					.timestamp(requiredTimestamp(rows, "timestamp").toInstant())
					.previousHash(Hash.wrap(requiredBytes(rows, "previous_hash", 32)))
					.txRootHash(Hash.wrap(requiredBytes(rows, "tx_root_hash", 32)))
					.stateRootHash(Hash.wrap(requiredBytes(rows, "state_root_hash", 32)))
					.difficulty(requiredPositiveInteger(rows, "difficulty"))
					.coinbase(Address.wrap(requiredBytes(rows, "coinbase", 20)))
					.nonce(nonce)
					.signature(requiredSignature(rows, "signature"))
					.build();
			Hash recalculatedHash = header.getHash();
			if (!recalculatedHash.equals(Hash.wrap(storedHash))) {
				throw new ChainStorageGuardException(
						"PostgreSQL explorer genesis hash does not match its canonical header fields");
			}
			byte[] storedIdentity = requiredBytes(rows, "identity", 20);
			if (!header.getIdentity().equals(Address.wrap(storedIdentity))) {
				throw new ChainStorageGuardException(
						"PostgreSQL explorer genesis identity does not match its signature");
			}
			if (rows.getInt("block_size") != header.getSize() || rows.wasNull()) {
				throw new ChainStorageGuardException(
						"PostgreSQL explorer genesis header size is inconsistent");
			}
			BigInteger cumulativeDifficulty = requiredPositiveInteger(rows, "cumulative_difficulty");
			if (!cumulativeDifficulty.equals(header.getDifficulty())) {
				throw new ChainStorageGuardException(
						"PostgreSQL explorer genesis cumulative difficulty is inconsistent");
			}
			if (rows.getInt("number_of_txs") != 0 || rows.wasNull()
					|| transactionTable.filter(txTable -> hasGenesisTransactions(connection, txTable)).isPresent()) {
				throw new ChainStorageGuardException(
						"PostgreSQL explorer genesis body is missing or inconsistent");
			}
			if (rows.next()) {
				throw new ChainStorageGuardException(
						"PostgreSQL explorer genesis row is missing or ambiguous");
			}
			return Optional.of("0x" + HexFormat.of().formatHex(storedHash));
		} catch (SQLException | RuntimeException e) {
			if (e instanceof ChainStorageGuardException guardException) {
				throw guardException;
			}
			throw new ChainStorageGuardException(
					"Failed to reconstruct PostgreSQL explorer genesis header", e);
		}
	}

	private boolean hasGenesisTransactions(Connection connection, TableReference table) {
		String sql = "SELECT 1 FROM " + table.sqlName()
				+ " WHERE block_height = 0 FETCH FIRST 1 ROW ONLY";
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			return rows.next();
		} catch (SQLException e) {
			throw new ChainStorageGuardException(
					"Failed to verify the PostgreSQL explorer genesis transaction body", e);
		}
	}

	private byte[] requiredBytes(ResultSet rows, String column, int length) throws SQLException {
		byte[] value = rows.getBytes(column);
		if (value == null || value.length != length) {
			throw new ChainStorageGuardException(
					"PostgreSQL explorer genesis column " + column + " has an invalid length");
		}
		return value;
	}

	private Timestamp requiredTimestamp(ResultSet rows, String column) throws SQLException {
		Timestamp value = rows.getTimestamp(column);
		if (value == null) {
			throw new ChainStorageGuardException(
					"PostgreSQL explorer genesis timestamp is missing");
		}
		return value;
	}

	private BigInteger requiredPositiveInteger(ResultSet rows, String column) throws SQLException {
		var value = rows.getBigDecimal(column);
		try {
			BigInteger integer = value == null ? null : value.toBigIntegerExact();
			if (integer == null || integer.signum() <= 0) {
				throw new ArithmeticException("not positive");
			}
			return integer;
		} catch (ArithmeticException e) {
			throw new ChainStorageGuardException(
					"PostgreSQL explorer genesis column " + column + " is not a positive integer", e);
		}
	}

	private Signature requiredSignature(ResultSet rows, String column) throws SQLException {
		byte[] value = rows.getBytes(column);
		if (value == null || value.length != 65) {
			throw new ChainStorageGuardException(
					"PostgreSQL explorer genesis signature has an invalid length");
		}
		return Signature.wrap(value);
	}

	private record TableReference(String schema, String name) {

		private String sqlName() {
			return schema == null || schema.isBlank()
					? quote(name)
					: quote(schema) + "." + quote(name);
		}

		private static String quote(String identifier) {
			return '"' + identifier.replace("\"", "\"\"") + '"';
		}
	}
}
