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
package global.goldenera.node.core.sync.snapshot.bootstrap;

import static global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotFilesystemActivation.DurableStep.INSTALLED;
import static global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotFilesystemActivation.DurableStep.JOURNALED;
import static global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotFilesystemActivation.DurableStep.TARGET_MOVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisConfigLoader;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncoder;

class CoreSnapshotFilesystemActivationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void atomicallyInstallsPreparedDatabaseIntoAbsentTarget() throws Exception {
		Path target = temporaryDirectory.resolve("blockchain");
		Path prepared = preparedDirectory("prepared-absent", "snapshot-data");
		CoreSnapshotFilesystemActivation activation = new CoreSnapshotFilesystemActivation(target);

		activation.activate(prepared);

		assertThat(target.resolve("payload")).hasContent("snapshot-data");
		assertThat(target.resolve(CoreSnapshotFilesystemActivation.ACTIVATION_MARKER_FILE)).isRegularFile();
		assertThat(prepared).doesNotExist();
		assertThat(activation.journalFile()).doesNotExist();
	}

	@Test
	void rejectsPopulatedNonRocksTargetWithoutChangingEitherDirectory() throws Exception {
		Path target = temporaryDirectory.resolve("occupied");
		Files.createDirectory(target);
		Files.writeString(target.resolve("user-data"), "keep");
		Path prepared = preparedDirectory("prepared-rejected", "snapshot-data");

		assertThatThrownBy(() -> new CoreSnapshotFilesystemActivation(target).activate(prepared))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("absent, empty, or genesis-only");

		assertThat(target.resolve("user-data")).hasContent("keep");
		assertThat(prepared.resolve("payload")).hasContent("snapshot-data");
	}

	@Test
	void rejectsPreparedDatabaseThatIsStillOpen() throws Exception {
		Path target = temporaryDirectory.resolve("closed-target");
		Path prepared = temporaryDirectory.resolve("prepared-open");
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(testProperties(prepared)).open(prepared, families);
		try {
			assertThatThrownBy(() -> new CoreSnapshotFilesystemActivation(target).activate(prepared))
					.isInstanceOf(IOException.class)
					.hasMessageContaining("not a closed RocksDB");
			assertThat(target).doesNotExist();
			assertThat(prepared).isDirectory();
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	@Test
	void skipsLegacyParentReachedThroughSymbolicLinkWhenNoJournalExists() throws Exception {
		Path realParent = temporaryDirectory.resolve("real-parent");
		Files.createDirectory(realParent);
		Path realTarget = Files.createDirectory(realParent.resolve("blockchain"));
		Files.writeString(realTarget.resolve("legacy-data"), "keep");
		Path linkedParent = temporaryDirectory.resolve("linked-parent");
		try {
			Files.createSymbolicLink(linkedParent, realParent);
		} catch (UnsupportedOperationException | IOException e) {
			assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
		}
		Path target = linkedParent.resolve("blockchain").toAbsolutePath().normalize();

		CoreSnapshotFilesystemActivation activation = new CoreSnapshotFilesystemActivation(target);

		assertThat(activation.recover())
				.isEqualTo(CoreSnapshotFilesystemActivation.RecoveryOutcome.SKIPPED_UNSAFE_LEGACY_PATH);
		assertThat(realTarget.resolve("legacy-data")).hasContent("keep");
	}

	@Test
	void failsClosedForSymlinkedLegacyParentWhenJournalExists() throws Exception {
		Path realParent = Files.createDirectory(temporaryDirectory.resolve("journal-real-parent"));
		Path linkedParent = temporaryDirectory.resolve("journal-linked-parent");
		try {
			Files.createSymbolicLink(linkedParent, realParent);
		} catch (UnsupportedOperationException | IOException e) {
			assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
		}
		CoreSnapshotFilesystemActivation activation = new CoreSnapshotFilesystemActivation(
				linkedParent.resolve("blockchain").toAbsolutePath().normalize());
		Files.writeString(activation.journalFile(), "unexpected journal");

		assertThatThrownBy(activation::recover)
				.isInstanceOf(IOException.class)
				.hasMessageContaining("journal exists under an unsafe legacy database parent");
	}

	@Test
	void recoveryCompletesInstallationAfterCrashFollowingOriginalMove() throws Exception {
		Path target = temporaryDirectory.resolve("recover-install");
		Files.createDirectory(target);
		Path prepared = preparedDirectory("prepared-recover-install", "snapshot-data");
		CoreSnapshotFilesystemActivation crashing = new CoreSnapshotFilesystemActivation(target, step -> {
			if (step == TARGET_MOVED) {
				throw new IOException("simulated crash");
			}
		});

		assertThatThrownBy(() -> crashing.activate(prepared)).hasMessageContaining("simulated crash");
		assertThat(target).doesNotExist();
		assertThat(crashing.journalFile()).isRegularFile();

		CoreSnapshotFilesystemActivation recovered = new CoreSnapshotFilesystemActivation(target);
		assertThat(recovered.recover())
				.isEqualTo(CoreSnapshotFilesystemActivation.RecoveryOutcome.COMPLETED_INSTALLATION);
		assertThat(target.resolve("payload")).hasContent("snapshot-data");
		assertThat(recovered.journalFile()).doesNotExist();
	}

	@Test
	void recoveryCompletesInstallationAfterCrashFollowingJournalCommit() throws Exception {
		Path target = temporaryDirectory.resolve("recover-journaled");
		Files.createDirectory(target);
		Path prepared = preparedDirectory("prepared-recover-journaled", "snapshot-data");
		CoreSnapshotFilesystemActivation crashing = new CoreSnapshotFilesystemActivation(target, step -> {
			if (step == JOURNALED) {
				throw new IOException("simulated crash");
			}
		});

		assertThatThrownBy(() -> crashing.activate(prepared)).hasMessageContaining("simulated crash");
		assertThat(target).isDirectory().isEmptyDirectory();
		assertThat(prepared.resolve("payload")).hasContent("snapshot-data");
		assertThat(crashing.journalFile()).isRegularFile();

		CoreSnapshotFilesystemActivation recovered = new CoreSnapshotFilesystemActivation(target);
		assertThat(recovered.recover())
				.isEqualTo(CoreSnapshotFilesystemActivation.RecoveryOutcome.COMPLETED_INSTALLATION);
		assertThat(target.resolve("payload")).hasContent("snapshot-data");
		assertThat(recovered.journalFile()).doesNotExist();
	}

	@Test
	void recoveryRecognizesInstalledTargetAndFinishesCleanup() throws Exception {
		Path target = temporaryDirectory.resolve("recover-cleanup");
		Files.createDirectory(target);
		Path prepared = preparedDirectory("prepared-recover-cleanup", "snapshot-data");
		CoreSnapshotFilesystemActivation crashing = new CoreSnapshotFilesystemActivation(target, step -> {
			if (step == INSTALLED) {
				throw new IOException("simulated crash");
			}
		});

		assertThatThrownBy(() -> crashing.activate(prepared)).hasMessageContaining("simulated crash");
		assertThat(target.resolve("payload")).hasContent("snapshot-data");
		assertThat(crashing.journalFile()).isRegularFile();

		CoreSnapshotFilesystemActivation recovered = new CoreSnapshotFilesystemActivation(target);
		assertThat(recovered.recover())
				.isEqualTo(CoreSnapshotFilesystemActivation.RecoveryOutcome.COMPLETED_INSTALLATION);
		assertThat(target.resolve("payload")).hasContent("snapshot-data");
		assertThat(recovered.journalFile()).doesNotExist();
	}

	@Test
	void recoveryRestoresOriginalWhenPreparedDatabaseDisappeared() throws Exception {
		Path target = temporaryDirectory.resolve("recover-rollback");
		Files.createDirectory(target);
		Path prepared = preparedDirectory("prepared-recover-rollback", "snapshot-data");
		CoreSnapshotFilesystemActivation crashing = new CoreSnapshotFilesystemActivation(target, step -> {
			if (step == TARGET_MOVED) {
				throw new IOException("simulated crash");
			}
		});

		assertThatThrownBy(() -> crashing.activate(prepared)).hasMessageContaining("simulated crash");
		deleteDirectory(prepared);

		CoreSnapshotFilesystemActivation recovered = new CoreSnapshotFilesystemActivation(target);
		assertThat(recovered.recover())
				.isEqualTo(CoreSnapshotFilesystemActivation.RecoveryOutcome.RESTORED_ORIGINAL);
		assertThat(target).isDirectory().isEmptyDirectory();
		assertThat(recovered.journalFile()).doesNotExist();
	}

	@Test
	void replacesExactGenesisOnlyRocksDatabase() throws Exception {
		Path target = temporaryDirectory.resolve("genesis-only");
		createGenesisOnlyDatabase(target);
		Path prepared = preparedDirectory("prepared-genesis", "snapshot-data");

		new CoreSnapshotFilesystemActivation(target).activate(prepared);

		assertThat(target.resolve("payload")).hasContent("snapshot-data");
	}

	private Path preparedDirectory(String name, String payload) throws Exception {
		Path prepared = temporaryDirectory.resolve(name);
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(testProperties(prepared)).open(prepared, families);
		families.getHandles().values().forEach(ColumnFamilyHandle::close);
		database.close();
		Files.writeString(prepared.resolve("payload"), payload);
		return prepared;
	}

	private void createGenesisOnlyDatabase(Path databasePath) throws Exception {
		Block genesis = mainnetGenesis(temporaryDirectory.resolve("genesis-state"));
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(testProperties(databasePath)).open(databasePath, families);
		try {
			StoredBlock stored = StoredBlock.builder()
					.block(genesis)
					.cumulativeDifficulty(genesis.getHeader().getDifficulty())
					.receivedAt(Instant.ofEpochMilli(genesis.getHeader().getTimestamp().toEpochMilli()))
					.receivedFrom(Address.ZERO)
					.connectedSource(ConnectedSource.GENESIS)
					.identity(genesis.getHeader().getIdentity())
					.computeIndexes()
					.build();
			byte[] hash = stored.getHash().toArray();
			database.put(families.blocks(), hash,
					StoredBlockEncoder.INSTANCE.encode(stored, StoredBlockVersion.V1).toArray());
			database.put(families.hashByHeight(), Bytes.ofUnsignedLong(0).toArray(), hash);
			database.put(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH, hash);
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	private Block mainnetGenesis(Path statePath) throws Exception {
		GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(Network.MAINNET, "prod");
		NetworkSettings settings = NetworkSettings.fromGenesisSettings(genesis, Network.MAINNET, "prod");
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(statePath)) {
			return new GenesisCandidateFactory(storage.factory()).create(settings, 0L).block();
		}
	}

	private BlockchainDbProperties testProperties(Path databasePath) {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(databasePath.toString());
		properties.setRocksdbBlockCacheMb(1);
		properties.setRocksdbWriteBufferMb(1);
		properties.setRocksdbMaxWriteBuffers(2);
		properties.setRocksdbMaxBackgroundJobs(1);
		properties.setRocksdbBlockSizeKb(4);
		properties.setRocksdbDirectReads(false);
		properties.setRocksdbDirectWrites(false);
		properties.setRocksdbBlobEnabled(false);
		return properties;
	}

	private void deleteDirectory(Path directory) throws Exception {
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
