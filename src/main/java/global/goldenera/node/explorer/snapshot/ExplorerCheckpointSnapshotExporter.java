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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class ExplorerCheckpointSnapshotExporter implements ExplorerSnapshotArtifactExporter {

	public static final String MANIFEST_FILE_NAME = "explorer-manifest.json";
	private static final int MIN_CHUNK_BYTES = 64 * 1024;
	private static final int MAX_CHUNK_BYTES = 64 * 1024 * 1024;

	private final DataSource dataSource;
	private final ExplorerSnapshotManifestCodec manifestCodec;

	public ExplorerCheckpointSnapshotExporter(DataSource dataSource, ObjectMapper objectMapper) {
		this.dataSource = dataSource;
		this.manifestCodec = new ExplorerSnapshotManifestCodec(objectMapper);
	}

	@Override
	public ExplorerSnapshotManifest export(ExplorerSnapshotBinding binding, Path destination, int chunkBytes) {
		if (chunkBytes < MIN_CHUNK_BYTES || chunkBytes > MAX_CHUNK_BYTES) {
			throw new IllegalArgumentException("Explorer snapshot chunk size must be between 64 KiB and 64 MiB");
		}
		Path target = destination.toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null || Files.isSymbolicLink(parent) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new ExplorerSnapshotException("Explorer snapshot destination must be a new directory");
		}
		Path staging = parent.resolve("." + target.getFileName() + ".staging-" + UUID.randomUUID());
		try {
			Files.createDirectories(staging);
			ExplorerSnapshotManifest manifest = exportConsistently(binding, staging, chunkBytes);
			Files.write(staging.resolve(MANIFEST_FILE_NAME), manifestCodec.encode(manifest));
			moveAtomically(staging, target);
			return manifest;
		} catch (Exception e) {
			deleteRecursively(staging);
			if (e instanceof ExplorerSnapshotException snapshotException) {
				throw snapshotException;
			}
			throw new ExplorerSnapshotException("Cannot export explorer checkpoint snapshot", e);
		}
	}

	private ExplorerSnapshotManifest exportConsistently(
			ExplorerSnapshotBinding binding,
			Path staging,
			int chunkBytes) throws SQLException, IOException {
		try (Connection connection = dataSource.getConnection()) {
			boolean previousAutoCommit = connection.getAutoCommit();
			boolean previousReadOnly = connection.isReadOnly();
			int previousIsolation = connection.getTransactionIsolation();
			try {
				connection.setAutoCommit(false);
				connection.setReadOnly(true);
				connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
				validateChainIdentity(connection, binding, true);
				validateCheckpoint(connection, binding);
				String migrationFingerprint = ExplorerMigrationFingerprint.calculate(connection);
				Map<String, Integer> versions = new HashMap<>();
				Map<String, Long> rowCounts = new HashMap<>();
				List<ExplorerSnapshotChunkDescriptor> chunks = new ArrayList<>();
				for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
					versions.put(table.tableName(), ExplorerSnapshotTable.SCHEMA_VERSION);
					chunks.addAll(exportTable(connection, table, staging, chunkBytes, rowCounts));
				}
				validateCheckpoint(connection, binding);
				connection.rollback();
				ExplorerSnapshotManifest unsigned = new ExplorerSnapshotManifest(
						ExplorerSnapshotManifest.FORMAT_VERSION, binding.carrierNetworkCode(), binding.chainId(),
						binding.genesisHash(), binding.checkpointHeight(), binding.checkpointHash(),
						binding.checkpointStateRoot(), binding.coreStateSigningHash(),
						binding.coreArchiveSigningHash(), migrationFingerprint, versions, rowCounts, chunks, null);
				return manifestCodec.sign(unsigned);
			} finally {
				connection.setReadOnly(previousReadOnly);
				connection.setTransactionIsolation(previousIsolation);
				connection.setAutoCommit(previousAutoCommit);
			}
		}
	}

	private List<ExplorerSnapshotChunkDescriptor> exportTable(
			Connection connection,
			ExplorerSnapshotTable table,
			Path staging,
			int maxChunkBytes,
			Map<String, Long> rowCounts) throws SQLException, IOException {
		List<ExplorerSnapshotColumn> columns = ExplorerSnapshotSchema.columns(connection, table);
		List<ExplorerSnapshotChunkDescriptor> descriptors = new ArrayList<>();
		List<byte[]> rows = new ArrayList<>();
		long totalRows = 0;
		int bufferedBytes = 0;
		int chunkIndex = 0;
		String order = String.join(", ", table.orderColumns());
		try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
			statement.setFetchSize(1_000);
			try (ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table.tableName() + " ORDER BY " + order)) {
				while (resultSet.next()) {
					byte[] row = ExplorerSnapshotChunkCodec.encodeRow(resultSet, columns);
					if (!rows.isEmpty() && bufferedBytes + row.length > maxChunkBytes) {
						descriptors.add(writeChunk(staging, table, columns, chunkIndex++, rows));
						rows.clear();
						bufferedBytes = 0;
					}
					rows.add(row);
					bufferedBytes += row.length;
					totalRows++;
				}
			}
		}
		if (!rows.isEmpty() || descriptors.isEmpty()) {
			descriptors.add(writeChunk(staging, table, columns, chunkIndex, rows));
		}
		rowCounts.put(table.tableName(), totalRows);
		return descriptors;
	}

	private ExplorerSnapshotChunkDescriptor writeChunk(
			Path staging,
			ExplorerSnapshotTable table,
			List<ExplorerSnapshotColumn> columns,
			int index,
			List<byte[]> rows) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(ExplorerSnapshotChunkCodec.encodeHeader(table, columns, rows.size()));
		for (byte[] row : rows) {
			output.write(row);
		}
		byte[] bytes = output.toByteArray();
		String fileName = "explorer-" + table.name().toLowerCase() + "-" + index + ".bin";
		Files.write(staging.resolve(fileName), bytes);
		return new ExplorerSnapshotChunkDescriptor(table, ExplorerSnapshotTable.SCHEMA_VERSION, index,
				rows.size(), bytes.length, ExplorerSnapshotDigests.sha256(bytes), fileName);
	}

	static void validateCheckpoint(Connection connection, ExplorerSnapshotBinding binding) throws SQLException {
		try (var statement = connection.prepareStatement("""
				SELECT s.synced_block_height, s.synced_block_hash, h.state_root_hash
				FROM explorer_status s
				JOIN explorer_block_header h ON h.height = s.synced_block_height AND h.hash = s.synced_block_hash
				WHERE s.id = 1
				""")) {
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()
						|| resultSet.getLong(1) != binding.checkpointHeight()
						|| !hex(resultSet.getBytes(2)).equals(binding.checkpointHash())
						|| !hex(resultSet.getBytes(3)).equals(binding.checkpointStateRoot())
						|| resultSet.next()) {
					throw new ExplorerSnapshotException("Explorer is not indexed exactly at the requested checkpoint");
				}
			}
		}
	}

	static void validateChainIdentity(
			Connection connection,
			ExplorerSnapshotBinding binding,
			boolean required) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						SELECT carrier_network_code, chain_id, genesis_hash
						FROM explorer_chain_identity
						WHERE id = 1
						""")) {
			if (!resultSet.next()) {
				if (required) {
					throw new ExplorerSnapshotException("Explorer chain identity is not initialized");
				}
				return;
			}
			if (resultSet.getInt(1) != binding.carrierNetworkCode()
					|| !resultSet.getString(2).equals(binding.chainId())
					|| !resultSet.getString(3).equals(binding.genesisHash())
					|| resultSet.next()) {
				throw new ExplorerSnapshotException("Explorer chain identity does not match snapshot binding");
			}
		}
	}

	static String hex(byte[] bytes) {
		return "0x" + HexFormat.of().formatHex(bytes);
	}

	private static void moveAtomically(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target);
		}
	}

	private static void deleteRecursively(Path path) {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(path)) {
			paths.sorted(Comparator.reverseOrder()).forEach(value -> {
				try {
					Files.deleteIfExists(value);
				} catch (IOException ignored) {
					// Best-effort cleanup of a uniquely named staging directory.
				}
			});
		} catch (IOException ignored) {
			// The original export failure remains the actionable cause.
		}
	}
}
