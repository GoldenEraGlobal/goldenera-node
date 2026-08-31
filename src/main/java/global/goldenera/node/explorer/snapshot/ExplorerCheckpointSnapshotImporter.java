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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;

public final class ExplorerCheckpointSnapshotImporter {

	private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
	private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024 + 64L * 1024;
	private static final int MAX_CHUNKS = 100_000;
	private static final long MAX_ROWS = 2_000_000_000L;

	private final DataSource dataSource;
	private final ExplorerSnapshotManifestCodec manifestCodec;

	public ExplorerCheckpointSnapshotImporter(DataSource dataSource, ObjectMapper objectMapper) {
		this.dataSource = dataSource;
		this.manifestCodec = new ExplorerSnapshotManifestCodec(objectMapper);
	}

	public PreparedExplorerSnapshotImport importIntoEmptySchema(
			ExplorerSnapshotBinding expectedBinding,
			Path snapshotDirectory) {
		Path directory = snapshotDirectory.toAbsolutePath().normalize();
		try {
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
				throw new ExplorerSnapshotException("Explorer snapshot source must be a regular directory");
			}
			Path manifestPath = resolveRegularFile(directory, ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME);
			if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
				throw new ExplorerSnapshotException("Explorer snapshot manifest exceeds the safety limit");
			}
			ExplorerSnapshotManifest manifest = manifestCodec.decode(Files.readAllBytes(manifestPath));
			validateManifest(manifest, expectedBinding);
			verifyChunks(directory, manifest);
			return commit(expectedBinding, directory, manifest);
		} catch (IOException | SQLException e) {
			throw new ExplorerSnapshotException("Cannot import explorer checkpoint snapshot", e);
		}
	}

	private PreparedExplorerSnapshotImport commit(
			ExplorerSnapshotBinding binding,
			Path directory,
			ExplorerSnapshotManifest manifest)
			throws SQLException, IOException {
		try (Connection connection = dataSource.getConnection()) {
			boolean previousAutoCommit = connection.getAutoCommit();
			int previousIsolation = connection.getTransactionIsolation();
			try {
				connection.setAutoCommit(false);
				connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
				String localMigrationFingerprint = ExplorerMigrationFingerprint.calculate(connection);
				if (!localMigrationFingerprint.equals(manifest.explorerMigrationFingerprint())) {
					throw new ExplorerSnapshotException("Explorer Liquibase migration fingerprint mismatch");
				}
				ExplorerCheckpointSnapshotExporter.validateChainIdentity(connection, binding, false);
				assertEmpty(connection);
				long importedRows = 0;
				for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
					List<ExplorerSnapshotColumn> localColumns = ExplorerSnapshotSchema.columns(connection, table);
					for (ExplorerSnapshotChunkDescriptor descriptor : manifest.chunks()) {
						if (descriptor.table() == table) {
							Path path = resolveRegularFile(directory, descriptor.fileName());
							if (Files.size(path) != descriptor.uncompressedSize()
									|| !ExplorerSnapshotDigests.sha256(path).equals(descriptor.sha256())) {
								throw new ExplorerSnapshotException(
										"Explorer snapshot chunk changed during import: " + descriptor.fileName());
							}
							ExplorerSnapshotChunkCodec.DecodedChunk chunk =
									ExplorerSnapshotChunkCodec.decode(
											Files.readAllBytes(path), table, manifest.formatVersion());
							if (!localColumns.equals(chunk.columns())) {
								throw new ExplorerSnapshotException("Explorer table schema mismatch for " + table.tableName());
							}
							insertRows(connection, table, localColumns, chunk.rows());
							importedRows += chunk.rows().size();
						}
					}
				}
				validateImportedCounts(connection, manifest);
				validateImportedCheckpoint(connection, binding);
				advanceSequences(connection);
				connection.commit();
				return new PreparedExplorerSnapshotImport(binding, manifest.signingHash(),
						manifest.explorerMigrationFingerprint(), importedRows, Instant.now());
			} catch (Exception e) {
				connection.rollback();
				if (e instanceof ExplorerSnapshotException snapshotException) {
					throw snapshotException;
				}
				if (e instanceof SQLException sqlException) {
					throw sqlException;
				}
				throw new ExplorerSnapshotException("Explorer snapshot transaction failed", e);
			} finally {
				connection.setTransactionIsolation(previousIsolation);
				connection.setAutoCommit(previousAutoCommit);
			}
		}
	}

	private void verifyChunks(
			Path directory,
			ExplorerSnapshotManifest manifest) throws IOException {
		Map<ExplorerSnapshotTable, Integer> expectedIndexes = new EnumMap<>(ExplorerSnapshotTable.class);
		Map<ExplorerSnapshotTable, Long> decodedCounts = new EnumMap<>(ExplorerSnapshotTable.class);
		Set<String> fileNames = new HashSet<>();
		for (ExplorerSnapshotChunkDescriptor descriptor : manifest.chunks()) {
			if (descriptor == null || descriptor.table() == null || !fileNames.add(descriptor.fileName())) {
				throw new ExplorerSnapshotException("Duplicate or incomplete explorer snapshot chunk descriptor");
			}
			int expectedIndex = expectedIndexes.getOrDefault(descriptor.table(), 0);
			if (descriptor.index() != expectedIndex) {
				throw new ExplorerSnapshotException("Non-contiguous explorer chunk indexes for " + descriptor.table());
			}
			expectedIndexes.put(descriptor.table(), expectedIndex + 1);
			if (descriptor.tableSchemaVersion() != ExplorerSnapshotTable.SCHEMA_VERSION
					|| descriptor.rowCount() < 0 || descriptor.rowCount() > MAX_ROWS
					|| descriptor.uncompressedSize() < 0 || descriptor.uncompressedSize() > MAX_CHUNK_BYTES) {
				throw new ExplorerSnapshotException("Invalid explorer snapshot chunk limits");
			}
			Path path = resolveRegularFile(directory, descriptor.fileName());
			if (Files.size(path) != descriptor.uncompressedSize()
					|| !ExplorerSnapshotDigests.sha256(path).equals(descriptor.sha256())) {
				throw new ExplorerSnapshotException("Explorer snapshot chunk digest mismatch: " + descriptor.fileName());
			}
			ExplorerSnapshotChunkCodec.DecodedChunk chunk =
					ExplorerSnapshotChunkCodec.decode(
							Files.readAllBytes(path), descriptor.table(), manifest.formatVersion());
			if (chunk.rows().size() != descriptor.rowCount()) {
				throw new ExplorerSnapshotException("Explorer snapshot chunk row count mismatch");
			}
			decodedCounts.merge(descriptor.table(), descriptor.rowCount(), Long::sum);
		}
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			if (!expectedIndexes.containsKey(table)
					|| !manifest.tableRowCounts().get(table.tableName()).equals(decodedCounts.getOrDefault(table, 0L))) {
				throw new ExplorerSnapshotException("Explorer snapshot table coverage mismatch for " + table.tableName());
			}
		}
	}

	private void validateManifest(ExplorerSnapshotManifest manifest, ExplorerSnapshotBinding expected) {
		if (!SnapshotFormatCompatibility.supportsExplorer(manifest.formatVersion())
				|| !manifestCodec.hasValidSigningHash(manifest)) {
			throw new ExplorerSnapshotException("Invalid explorer snapshot manifest signing hash or version");
		}
		ExplorerSnapshotBinding actual;
		try {
			actual = new ExplorerSnapshotBinding(
					manifest.carrierNetworkCode(), manifest.chainId(), manifest.genesisHash(), manifest.checkpointHeight(),
					manifest.checkpointHash(), manifest.checkpointStateRoot(), manifest.coreStateSigningHash(),
					manifest.coreArchiveSigningHash());
		} catch (IllegalArgumentException e) {
			throw new ExplorerSnapshotException("Explorer snapshot manifest binding is invalid", e);
		}
		if (!actual.equals(expected)) {
			throw new ExplorerSnapshotException("Explorer snapshot is not bound to the activated core checkpoint");
		}
		Set<String> expectedTables = EnumSet.allOf(ExplorerSnapshotTable.class).stream()
				.map(ExplorerSnapshotTable::tableName).collect(Collectors.toUnmodifiableSet());
		if (!manifest.tableSchemaVersions().keySet().equals(expectedTables)
				|| !manifest.tableRowCounts().keySet().equals(expectedTables)
				|| manifest.tableSchemaVersions().values().stream()
						.anyMatch(version -> version != ExplorerSnapshotTable.SCHEMA_VERSION)
				|| manifest.tableRowCounts().values().stream().anyMatch(count -> count < 0 || count > MAX_ROWS)
				|| manifest.chunks().isEmpty() || manifest.chunks().size() > MAX_CHUNKS) {
			throw new ExplorerSnapshotException("Invalid explorer snapshot table manifest");
		}
		validateHex(manifest.explorerMigrationFingerprint(), false);
		validateHex(manifest.signingHash(), false);
	}

	private static void assertEmpty(Connection connection) throws SQLException {
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table.tableName())) {
				resultSet.next();
				if (resultSet.getLong(1) != 0) {
					throw new ExplorerSnapshotException(
							"Explorer snapshot import requires an empty schema; found data in " + table.tableName());
				}
			}
		}
	}

	private static void insertRows(
			Connection connection,
			ExplorerSnapshotTable table,
			List<ExplorerSnapshotColumn> columns,
			List<List<Object>> rows) throws SQLException {
		if (rows.isEmpty()) {
			return;
		}
		String names = String.join(", ", columns.stream().map(ExplorerSnapshotColumn::name).toList());
		String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO " + table.tableName() + " (" + names + ") VALUES (" + placeholders + ")")) {
			for (List<Object> row : rows) {
				for (int index = 0; index < columns.size(); index++) {
					ExplorerSnapshotChunkCodec.bind(statement, index + 1, columns.get(index), row.get(index));
				}
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	private static void validateImportedCounts(Connection connection, ExplorerSnapshotManifest manifest)
			throws SQLException {
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table.tableName())) {
				resultSet.next();
				if (resultSet.getLong(1) != manifest.tableRowCounts().get(table.tableName())) {
					throw new ExplorerSnapshotException("Imported explorer row count mismatch for " + table.tableName());
				}
			}
		}
	}

	private static void validateImportedCheckpoint(Connection connection, ExplorerSnapshotBinding binding)
			throws SQLException {
		ExplorerCheckpointSnapshotExporter.validateCheckpoint(connection, binding);
	}

	private static void advanceSequences(Connection connection) throws SQLException {
		String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
		advanceSequence(connection, product, "explorer_transfer_seq", "explorer_transfer");
		advanceSequence(connection, product, "explorer_revert_log_seq", "explorer_revert_log");
	}

	private static void advanceSequence(Connection connection, String product, String sequence, String table)
			throws SQLException {
		long next;
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM " + table)) {
			resultSet.next();
			next = resultSet.getLong(1);
		}
		try (Statement statement = connection.createStatement()) {
			if (product.contains("postgresql")) {
				statement.execute("SELECT setval('" + sequence + "', " + Math.max(next - 1, 1) + ", true)");
			} else if (product.contains("h2")) {
				statement.execute("ALTER SEQUENCE " + sequence + " RESTART WITH " + Math.max(next, 1));
			}
		}
	}

	private static Path resolveRegularFile(Path directory, String fileName) throws IOException {
		if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
			throw new ExplorerSnapshotException("Invalid explorer snapshot file name");
		}
		Path path = directory.resolve(fileName).normalize();
		if (!path.getParent().equals(directory) || Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new ExplorerSnapshotException("Unsafe explorer snapshot file: " + fileName);
		}
		return path;
	}

	private static void validateHex(String value, boolean prefixed) {
		String pattern = prefixed ? "0x[0-9a-f]{64}" : "[0-9a-f]{64}";
		if (value == null || !value.matches(pattern)) {
			throw new ExplorerSnapshotException("Invalid explorer snapshot hash encoding");
		}
	}
}
