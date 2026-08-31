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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import javax.sql.DataSource;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloor;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotCheckpointFloorPolicy;
import lombok.extern.slf4j.Slf4j;

/** Restores explorer-only data after the verified core snapshot floor is open. */
@Slf4j
public final class ExplorerSnapshotBootstrapService {

	private final SnapshotDistributionProperties properties;
	private final CoreSnapshotCheckpointFloorPolicy floorPolicy;
	private final ChainQuery chainQuery;
	private final DataSource dataSource;
	private final ExplorerSnapshotRemoteSource remoteSource;
	private final ExplorerCheckpointSnapshotImporter importer;

	public ExplorerSnapshotBootstrapService(
			SnapshotDistributionProperties properties,
			CoreSnapshotCheckpointFloorPolicy floorPolicy,
			ChainQuery chainQuery,
			DataSource dataSource,
			ExplorerSnapshotRemoteSource remoteSource,
			ExplorerCheckpointSnapshotImporter importer) {
		this.properties = properties;
		this.floorPolicy = floorPolicy;
		this.chainQuery = chainQuery;
		this.dataSource = dataSource;
		this.remoteSource = remoteSource;
		this.importer = importer;
	}

	public Outcome prepareForIndexing(StoredChainIdentity identity) {
		Optional<CoreSnapshotCheckpointFloor> optionalFloor = floorPolicy.floor();
		if (optionalFloor.isEmpty()) {
			log.info("EXPLORER SNAPSHOT: No activated CORE snapshot floor; using live indexer path");
			return Outcome.LEGACY_NO_FLOOR;
		}
		CoreSnapshotCheckpointFloor floor = optionalFloor.orElseThrow();
		log.info("EXPLORER SNAPSHOT: Activated CORE floor at height {}; inspecting PostgreSQL",
				floor.height());
		ExplorerSnapshotBinding binding = binding(identity, floor);
		DatabaseState databaseState = inspectDatabase(floor);
		if (databaseState == DatabaseState.CANONICAL_AT_OR_ABOVE_FLOOR) {
			log.info("EXPLORER SNAPSHOT: PostgreSQL is already canonical at or above CORE floor {}",
					floor.height());
			return Outcome.ALREADY_INDEXED;
		}
		if (databaseState != DatabaseState.EMPTY) {
			throw new ExplorerSnapshotException(
					"Explorer database is not empty and is incompatible with the active snapshot floor");
		}
		if (!properties.isBootstrapEnabled()) {
			throw new ExplorerSnapshotException(
					"Explorer snapshot bootstrap is disabled while core starts above genesis");
		}
		log.info("EXPLORER SNAPSHOT: Empty PostgreSQL detected; downloading snapshot bound to CORE height {}",
				floor.height());
		try (StagedExplorerSnapshotDownload staged = remoteSource.stageFromFirstTrustedSource(binding)) {
			log.info("EXPLORER SNAPSHOT: Downloaded {} chunk(s); importing PostgreSQL snapshot",
					staged.manifest().chunks().size());
			PreparedExplorerSnapshotImport prepared = importer.importIntoEmptySchema(binding, staged.directory());
			if (!prepared.binding().equals(binding)) {
				throw new ExplorerSnapshotException("Explorer importer returned a mismatched prepared capability");
			}
		} catch (ExplorerSnapshotException e) {
			throw e;
		} catch (Exception e) {
			throw new ExplorerSnapshotException("Explorer snapshot bootstrap failed", e);
		}
		if (inspectDatabase(floor) != DatabaseState.CANONICAL_AT_OR_ABOVE_FLOOR) {
			throw new ExplorerSnapshotException("Imported explorer snapshot did not reach the active checkpoint floor");
		}
		log.info("EXPLORER SNAPSHOT: Imported PostgreSQL snapshot at CORE height {}", floor.height());
		return Outcome.IMPORTED;
	}

	private DatabaseState inspectDatabase(CoreSnapshotCheckpointFloor floor) {
		try (Connection connection = dataSource.getConnection()) {
			long rows = 0;
			for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
				try (Statement statement = connection.createStatement();
						ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table.tableName())) {
					resultSet.next();
					rows += resultSet.getLong(1);
				}
			}
			if (rows == 0) {
				return DatabaseState.EMPTY;
			}
			try (Statement statement = connection.createStatement();
					ResultSet status = statement.executeQuery("""
							SELECT s.synced_block_height, s.synced_block_hash, h.state_root_hash
							FROM explorer_status s
							JOIN explorer_block_header h
							  ON h.height = s.synced_block_height AND h.hash = s.synced_block_hash
							WHERE s.id = 1
							""")) {
				if (!status.next()) {
					return DatabaseState.INCOMPATIBLE;
				}
				long height = status.getLong(1);
				Hash hash = Hash.wrap(status.getBytes(2));
				Hash stateRoot = Hash.wrap(status.getBytes(3));
				if (status.next() || height < floor.height()) {
					return DatabaseState.INCOMPATIBLE;
				}
				StoredBlock canonical = chainQuery.getStoredBlockHeaderByHeight(height).orElse(null);
				if (canonical == null || !canonical.getHash().equals(hash)) {
					return DatabaseState.INCOMPATIBLE;
				}
				if (height == floor.height()
						&& (!hash.equals(floor.blockHash()) || !stateRoot.equals(floor.stateRoot()))) {
					return DatabaseState.INCOMPATIBLE;
				}
				return DatabaseState.CANONICAL_AT_OR_ABOVE_FLOOR;
			}
		} catch (SQLException e) {
			throw new ExplorerSnapshotException("Cannot inspect explorer snapshot state", e);
		}
	}

	private ExplorerSnapshotBinding binding(StoredChainIdentity identity, CoreSnapshotCheckpointFloor floor) {
		return new ExplorerSnapshotBinding(
				identity.carrierNetworkCode(), identity.chainId(), identity.genesisHash(), floor.height(),
				floor.blockHash().toHexString(), floor.stateRoot().toHexString(),
				withoutPrefix(floor.stateManifestSigningHash()), withoutPrefix(floor.archiveManifestSigningHash()));
	}

	private String withoutPrefix(Hash hash) {
		String value = hash.toHexString();
		return value.startsWith("0x") ? value.substring(2) : value;
	}

	public enum Outcome {
		LEGACY_NO_FLOOR,
		ALREADY_INDEXED,
		IMPORTED
	}

	private enum DatabaseState {
		EMPTY,
		CANONICAL_AT_OR_ABOVE_FLOOR,
		INCOMPATIBLE
	}
}
