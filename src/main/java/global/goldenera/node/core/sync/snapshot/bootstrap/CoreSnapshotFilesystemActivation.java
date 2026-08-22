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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.tuweni.bytes.Bytes;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;

/**
 * Crash-consistent pre-open installation of a prepared sibling RocksDB.
 * This utility must only run while the target database is closed.
 */
public final class CoreSnapshotFilesystemActivation {

	public static final String ACTIVATION_MARKER_FILE = ".goldenera-snapshot-activation-v1";

	private static final int JOURNAL_FORMAT_VERSION = 1;
	private static final int MAX_CONTROL_FILE_BYTES = 4096;
	private static final String ROCKS_CURRENT_FILE = "CURRENT";
	private static final String CHAIN_IDENTITY_KEY = "CHAIN_IDENTITY_V1";
	private final Path targetDirectory;
	private final Path parentDirectory;
	private final Path journalFile;
	private final Path lockFile;
	private final ActivationStepHook stepHook;

	public CoreSnapshotFilesystemActivation(Path targetDirectory) {
		this(targetDirectory, ignored -> { });
	}

	CoreSnapshotFilesystemActivation(Path targetDirectory, ActivationStepHook stepHook) {
		this.targetDirectory = requireNormalizedAbsolute(targetDirectory, "targetDirectory");
		this.stepHook = Objects.requireNonNull(stepHook, "stepHook");
		Path parent = this.targetDirectory.getParent();
		if (parent == null || this.targetDirectory.getFileName() == null) {
			throw new IllegalArgumentException("Snapshot activation target must have a parent and file name");
		}
		this.parentDirectory = parent;
		String targetName = this.targetDirectory.getFileName().toString();
		this.journalFile = parent.resolve("." + targetName + ".snapshot-activation-v1.journal");
		this.lockFile = parent.resolve("." + targetName + ".snapshot-activation-v1.lock");
	}

	public void activate(Path preparedDirectory) throws IOException {
		withLock(() -> {
			recoverUnderLock();
			Path prepared = validatePreparedSibling(preparedDirectory);
			TargetState targetState = inspectTarget();
			if (!targetState.eligible()) {
				throw new IOException("Snapshot activation requires an absent, empty, or genesis-only target");
			}

			String activationId = ensureActivationMarker(prepared);
			String backupName = "." + targetDirectory.getFileName()
					+ ".snapshot-backup-" + UUID.randomUUID();
			Path backup = parentDirectory.resolve(backupName);
			Journal journal = new Journal(
					activationId, prepared.getFileName().toString(), backupName, Phase.PREPARED);
			writeJournal(journal, false);
			stepHook.after(DurableStep.JOURNALED);

			if (targetState != TargetState.ABSENT) {
				moveAtomic(targetDirectory, backup);
				forceDirectory(parentDirectory);
				journal = journal.withPhase(Phase.TARGET_MOVED);
				writeJournal(journal, true);
				stepHook.after(DurableStep.TARGET_MOVED);
			}

			moveAtomic(prepared, targetDirectory);
			forceDirectory(parentDirectory);
			journal = journal.withPhase(Phase.INSTALLED);
			writeJournal(journal, true);
			stepHook.after(DurableStep.INSTALLED);
			finishInstalled(journal);
		});
	}

	public RecoveryOutcome recover() throws IOException {
		if (!hasDirectRealParent()) {
			if (Files.exists(journalFile, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException(
						"Snapshot activation journal exists under an unsafe legacy database parent");
			}
			return RecoveryOutcome.SKIPPED_UNSAFE_LEGACY_PATH;
		}
		return withLock(this::recoverUnderLock);
	}

	/** Returns the exact pre-open eligibility state without mutating the target database. */
	public TargetState inspectTargetState() throws IOException {
		return withLock(this::inspectTarget);
	}

	public boolean hasRecoveryJournal() {
		return Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS);
	}

	public Path journalFile() {
		return journalFile;
	}

	private RecoveryOutcome recoverUnderLock() throws IOException {
		validateParent();
		if (Files.notExists(journalFile, LinkOption.NOFOLLOW_LINKS)) {
			return RecoveryOutcome.NOTHING_TO_RECOVER;
		}
		if (!Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Snapshot activation journal is not a regular file");
		}

		Journal journal = readJournal();
		Path prepared = resolveJournalSibling(journal.preparedName());
		Path backup = resolveBackupSibling(journal.backupName());
		if (hasMatchingMarker(targetDirectory, journal.activationId())) {
			finishInstalled(journal);
			return RecoveryOutcome.COMPLETED_INSTALLATION;
		}

		boolean preparedReady = hasMatchingMarker(prepared, journal.activationId());
		boolean targetExists = Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS);
		boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
		if (preparedReady) {
			if (targetExists) {
				if (journal.phase() != Phase.PREPARED || backupExists || !inspectTarget().eligible()) {
					throw new IOException("Snapshot activation recovery found an unexpected target state");
				}
				moveAtomic(targetDirectory, backup);
				forceDirectory(parentDirectory);
				journal = journal.withPhase(Phase.TARGET_MOVED);
				writeJournal(journal, true);
			}
			moveAtomic(prepared, targetDirectory);
			forceDirectory(parentDirectory);
			journal = journal.withPhase(Phase.INSTALLED);
			writeJournal(journal, true);
			finishInstalled(journal);
			return RecoveryOutcome.COMPLETED_INSTALLATION;
		}

		if (!targetExists && backupExists) {
			moveAtomic(backup, targetDirectory);
			forceDirectory(parentDirectory);
			deleteJournal();
			return RecoveryOutcome.RESTORED_ORIGINAL;
		}
		if (targetExists && !backupExists && journal.phase() == Phase.PREPARED
				&& inspectTarget().eligible()) {
			deleteJournal();
			return RecoveryOutcome.RESTORED_ORIGINAL;
		}
		if (!targetExists && !backupExists && journal.phase() == Phase.PREPARED) {
			deleteJournal();
			return RecoveryOutcome.RESTORED_ORIGINAL;
		}
		throw new IOException("Snapshot activation recovery cannot prove either installation or rollback");
	}

	private void finishInstalled(Journal journal) throws IOException {
		if (!hasMatchingMarker(targetDirectory, journal.activationId())) {
			throw new IOException("Installed snapshot target is missing its activation marker");
		}
		Path prepared = resolveJournalSibling(journal.preparedName());
		Path backup = resolveBackupSibling(journal.backupName());
		deleteMarkedDirectoryIfPresent(prepared, journal.activationId());
		deleteDirectoryIfPresent(backup);
		forceDirectory(parentDirectory);
		deleteJournal();
	}

	private void deleteJournal() throws IOException {
		Files.deleteIfExists(journalFile);
		forceDirectory(parentDirectory);
	}

	private TargetState inspectTarget() throws IOException {
		validateParent();
		if (Files.notExists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
			return TargetState.ABSENT;
		}
		if (!Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(targetDirectory)) {
			return TargetState.INELIGIBLE;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(targetDirectory)) {
			if (!entries.iterator().hasNext()) {
				return TargetState.EMPTY;
			}
		}
		return isGenesisOnlyRocksDb(targetDirectory)
				? TargetState.GENESIS_ONLY : TargetState.INELIGIBLE;
	}

	private boolean isGenesisOnlyRocksDb(Path directory) throws IOException {
		RocksDB.loadLibrary();
		List<byte[]> names;
		try (Options options = new Options().setCreateIfMissing(false)) {
			names = RocksDB.listColumnFamilies(options, directory.toString());
		} catch (RocksDBException e) {
			return false;
		}
		Map<String, Integer> indexes = new HashMap<>();
		List<ColumnFamilyOptions> familyOptions = new ArrayList<>(names.size());
		List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
		for (int index = 0; index < names.size(); index++) {
			String name = new String(names.get(index), UTF_8);
			if (indexes.put(name, index) != null) {
				familyOptions.forEach(ColumnFamilyOptions::close);
				return false;
			}
			ColumnFamilyOptions options = new ColumnFamilyOptions();
			familyOptions.add(options);
			descriptors.add(new ColumnFamilyDescriptor(names.get(index), options));
		}
		Integer blocksIndex = indexes.get(RocksDbColumnFamilies.CF_BLOCKS);
		Integer heightsIndex = indexes.get(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT);
		Integer metadataIndex = indexes.get(RocksDbColumnFamilies.CF_METADATA);
		if (blocksIndex == null || heightsIndex == null || metadataIndex == null) {
			familyOptions.forEach(ColumnFamilyOptions::close);
			return false;
		}

		List<ColumnFamilyHandle> handles = new ArrayList<>(names.size());
		try (DBOptions options = new DBOptions()
				.setCreateIfMissing(false)
				.setCreateMissingColumnFamilies(false);
				RocksDB database = RocksDB.openReadOnly(
						options, directory.toString(), descriptors, handles)) {
			ColumnFamilyHandle blocks = handles.get(blocksIndex);
			ColumnFamilyHandle heights = handles.get(heightsIndex);
			ColumnFamilyHandle metadata = handles.get(metadataIndex);
			byte[] latestHash = database.get(metadata, RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH);
			byte[] indexedGenesis = database.get(heights, Bytes.ofUnsignedLong(0).toArray());
			if (latestHash == null || latestHash.length != Hash.SIZE
					|| !Arrays.equals(latestHash, indexedGenesis)
					|| !containsExactlyOneEntry(database, heights, Bytes.ofUnsignedLong(0).toArray(), indexedGenesis)
					|| !containsExactlyOneEntry(database, blocks, latestHash, database.get(blocks, latestHash))) {
				return false;
			}
			byte[] encoded = database.get(blocks, latestHash);
			if (encoded == null) {
				return false;
			}
			StoredBlock genesis;
			try {
				genesis = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(encoded));
			} catch (RuntimeException e) {
				return false;
			}
			if (genesis.isPartial() || genesis.getHeight() != 0
					|| !genesis.getHash().equals(Hash.wrap(latestHash))
					|| !genesis.getBlock().getHash().equals(genesis.getHash())) {
				return false;
			}
			Integer txIndex = indexes.get(RocksDbColumnFamilies.CF_TX_INDEX);
			if (txIndex != null && !allValuesEqual(database, handles.get(txIndex), latestHash,
					genesis.getTxCount())) {
				return false;
			}
			Integer equivocationsIndex = indexes.get(RocksDbColumnFamilies.CF_EQUIVOCATIONS);
			return (equivocationsIndex == null || isEmpty(database, handles.get(equivocationsIndex)))
					&& containsOnlyGenesisMetadata(metadata, database);
		} catch (RocksDBException | RuntimeException e) {
			return false;
		} finally {
			for (int index = handles.size() - 1; index >= 0; index--) {
				handles.get(index).close();
			}
			familyOptions.forEach(ColumnFamilyOptions::close);
		}
	}

	private boolean containsExactlyOneEntry(
			RocksDB database,
			ColumnFamilyHandle family,
			byte[] expectedKey,
			byte[] expectedValue) throws RocksDBException {
		if (expectedValue == null) {
			return false;
		}
		try (RocksIterator iterator = database.newIterator(family)) {
			iterator.seekToFirst();
			if (!iterator.isValid() || !Arrays.equals(iterator.key(), expectedKey)
					|| !Arrays.equals(iterator.value(), expectedValue)) {
				return false;
			}
			iterator.next();
			boolean exhausted = !iterator.isValid();
			iterator.status();
			return exhausted;
		}
	}

	private boolean allValuesEqual(
			RocksDB database,
			ColumnFamilyHandle family,
			byte[] expectedValue,
			int expectedCount) throws RocksDBException {
		int count = 0;
		try (RocksIterator iterator = database.newIterator(family)) {
			for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
				if (!Arrays.equals(iterator.value(), expectedValue)) {
					return false;
				}
				count++;
			}
			iterator.status();
		}
		return count == expectedCount;
	}

	private boolean isEmpty(RocksDB database, ColumnFamilyHandle family) throws RocksDBException {
		try (RocksIterator iterator = database.newIterator(family)) {
			iterator.seekToFirst();
			boolean empty = !iterator.isValid();
			iterator.status();
			return empty;
		}
	}

	private boolean containsOnlyGenesisMetadata(ColumnFamilyHandle metadata, RocksDB database)
			throws RocksDBException {
		try (RocksIterator iterator = database.newIterator(metadata)) {
			for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
				if (!Arrays.equals(iterator.key(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH)
						&& !Arrays.equals(iterator.key(), CHAIN_IDENTITY_KEY.getBytes(UTF_8))) {
					return false;
				}
			}
			iterator.status();
			return true;
		}
	}

	private Path validatePreparedSibling(Path candidate) throws IOException {
		Path prepared = requireNormalizedAbsolute(candidate, "preparedDirectory");
		validateParent();
		if (!Objects.equals(prepared.getParent(), parentDirectory) || prepared.equals(targetDirectory)) {
			throw new IOException("Prepared snapshot database must be a distinct sibling of the target");
		}
		if (!Files.isDirectory(prepared, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(prepared)
				|| !Files.isRegularFile(prepared.resolve(ROCKS_CURRENT_FILE), LinkOption.NOFOLLOW_LINKS)
				|| !isClosedCanonicalRocksDb(prepared)) {
			throw new IOException("Prepared snapshot path is not a closed RocksDB directory");
		}
		return prepared;
	}

	private boolean isClosedCanonicalRocksDb(Path directory) {
		RocksDB.loadLibrary();
		List<ColumnFamilyOptions> familyOptions = new ArrayList<>();
		List<ColumnFamilyHandle> handles = new ArrayList<>();
		try (Options options = new Options().setCreateIfMissing(false)) {
			List<byte[]> names = RocksDB.listColumnFamilies(options, directory.toString());
			List<String> decodedNames = names.stream().map(bytes -> new String(bytes, UTF_8)).toList();
			if (decodedNames.size() != BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES.size()
					|| !decodedNames.containsAll(BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES)) {
				return false;
			}
			List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
			for (byte[] name : names) {
				ColumnFamilyOptions family = new ColumnFamilyOptions();
				familyOptions.add(family);
				descriptors.add(new ColumnFamilyDescriptor(name, family));
			}
			try (DBOptions databaseOptions = new DBOptions()
					.setCreateIfMissing(false)
					.setCreateMissingColumnFamilies(false);
					RocksDB ignored = RocksDB.open(
							databaseOptions, directory.toString(), descriptors, handles)) {
				return true;
			}
		} catch (RocksDBException e) {
			return false;
		} finally {
			for (int index = handles.size() - 1; index >= 0; index--) {
				handles.get(index).close();
			}
			familyOptions.forEach(ColumnFamilyOptions::close);
		}
	}

	private String ensureActivationMarker(Path prepared) throws IOException {
		Path marker = prepared.resolve(ACTIVATION_MARKER_FILE);
		if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
			String existing = readControlFile(marker).trim();
			validateActivationId(existing);
			return existing;
		}
		String activationId = UUID.randomUUID().toString();
		writeForcedFile(marker, activationId + "\n", false);
		forceDirectory(prepared);
		return activationId;
	}

	private boolean hasMatchingMarker(Path directory, String activationId) throws IOException {
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
			return false;
		}
		Path marker = directory.resolve(ACTIVATION_MARKER_FILE);
		return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
				&& readControlFile(marker).trim().equals(activationId);
	}

	private void writeJournal(Journal journal, boolean replace) throws IOException {
		String encoded = "format=" + JOURNAL_FORMAT_VERSION + "\n"
				+ "activation=" + journal.activationId() + "\n"
				+ "prepared=" + encodeName(journal.preparedName()) + "\n"
				+ "backup=" + encodeName(journal.backupName()) + "\n"
				+ "phase=" + journal.phase().name() + "\n";
		Path temporary = parentDirectory.resolve(journalFile.getFileName() + ".tmp-" + UUID.randomUUID());
		writeForcedFile(temporary, encoded, false);
		try {
			if (replace) {
				Files.move(temporary, journalFile, ATOMIC_MOVE, REPLACE_EXISTING);
			} else {
				Files.move(temporary, journalFile, ATOMIC_MOVE);
			}
			forceDirectory(parentDirectory);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private Journal readJournal() throws IOException {
		List<String> lines = readControlFile(journalFile).lines().toList();
		Map<String, String> fields = new HashMap<>();
		for (String line : lines) {
			int separator = line.indexOf('=');
			if (separator <= 0 || fields.put(line.substring(0, separator), line.substring(separator + 1)) != null) {
				throw new IOException("Snapshot activation journal is malformed");
			}
		}
		if (fields.size() != 5 || !Integer.toString(JOURNAL_FORMAT_VERSION).equals(fields.get("format"))) {
			throw new IOException("Snapshot activation journal has an unsupported format");
		}
		String activationId = fields.get("activation");
		validateActivationId(activationId);
		try {
			return new Journal(
					activationId,
					decodeName(fields.get("prepared")),
					decodeName(fields.get("backup")),
					Phase.valueOf(fields.get("phase")));
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new IOException("Snapshot activation journal is malformed", e);
		}
	}

	private void validateActivationId(String activationId) throws IOException {
		try {
			UUID.fromString(activationId);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new IOException("Snapshot activation marker is malformed", e);
		}
	}

	private String encodeName(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(UTF_8));
	}

	private String decodeName(String value) {
		byte[] decoded = Base64.getUrlDecoder().decode(value);
		String name = new String(decoded, UTF_8);
		if (!encodeName(name).equals(value) || name.isEmpty()) {
			throw new IllegalArgumentException("Non-canonical journal path encoding");
		}
		return name;
	}

	private Path resolveJournalSibling(String name) throws IOException {
		Path resolved = parentDirectory.resolve(name).normalize();
		if (!Objects.equals(resolved.getParent(), parentDirectory) || resolved.equals(targetDirectory)) {
			throw new IOException("Snapshot activation journal references a path outside its parent");
		}
		return resolved;
	}

	private Path resolveBackupSibling(String name) throws IOException {
		String prefix = "." + targetDirectory.getFileName() + ".snapshot-backup-";
		if (!name.startsWith(prefix)) {
			throw new IOException("Snapshot activation journal contains an invalid backup name");
		}
		try {
			UUID.fromString(name.substring(prefix.length()));
		} catch (IllegalArgumentException e) {
			throw new IOException("Snapshot activation journal contains an invalid backup name", e);
		}
		return resolveJournalSibling(name);
	}

	private void deleteMarkedDirectoryIfPresent(Path directory, String activationId) throws IOException {
		if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!hasMatchingMarker(directory, activationId)) {
			throw new IOException("Refusing to delete an unmarked prepared snapshot directory");
		}
		deleteDirectory(directory);
	}

	private void deleteDirectoryIfPresent(Path directory) throws IOException {
		if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Objects.equals(directory.getParent(), parentDirectory) || directory.equals(targetDirectory)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
			throw new IOException("Refusing to delete an unsafe snapshot activation backup");
		}
		deleteDirectory(directory);
	}

	private void deleteDirectory(Path directory) throws IOException {
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	private void moveAtomic(Path source, Path target) throws IOException {
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Snapshot activation destination already exists: " + target.getFileName());
		}
		Files.move(source, target, ATOMIC_MOVE);
	}

	private void writeForcedFile(Path path, String value, boolean replace) throws IOException {
		OpenOption[] options = replace
				? new OpenOption[] { CREATE, WRITE, LinkOption.NOFOLLOW_LINKS }
				: new OpenOption[] { CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS };
		try (FileChannel channel = FileChannel.open(path, options)) {
			ByteBuffer buffer = ByteBuffer.wrap(value.getBytes(UTF_8));
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true);
		}
	}

	private String readControlFile(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Snapshot activation control file is not a regular file");
		}
		try (FileChannel channel = FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS)) {
			long size = channel.size();
			if (size <= 0 || size > MAX_CONTROL_FILE_BYTES) {
				throw new IOException("Snapshot activation control file has an invalid size");
			}
			ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(size));
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) {
					throw new IOException("Snapshot activation control file is truncated");
				}
			}
			return new String(buffer.array(), UTF_8);
		}
	}

	private void validateParent() throws IOException {
		if (!hasDirectRealParent()) {
			throw new IOException("Snapshot activation parent must be an existing real directory");
		}
	}

	private boolean hasDirectRealParent() {
		try {
			return Files.isDirectory(parentDirectory, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isSymbolicLink(parentDirectory)
					&& parentDirectory.toRealPath().equals(parentDirectory);
		} catch (IOException e) {
			return false;
		}
	}

	private void forceDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, READ)) {
			channel.force(true);
		}
	}

	private <T> T withLock(IoSupplier<T> operation) throws IOException {
		validateParent();
		try (FileChannel channel = FileChannel.open(lockFile, CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
				FileLock ignored = channel.tryLock()) {
			if (ignored == null) {
				throw new IOException("Another snapshot filesystem activation is already running");
			}
			forceDirectory(parentDirectory);
			return operation.get();
		} catch (OverlappingFileLockException e) {
			throw new IOException("Another snapshot filesystem activation is already running", e);
		}
	}

	private void withLock(IoRunnable operation) throws IOException {
		withLock(() -> {
			operation.run();
			return null;
		});
	}

	private static Path requireNormalizedAbsolute(Path path, String field) {
		Objects.requireNonNull(path, field);
		if (!path.isAbsolute() || !path.equals(path.normalize())) {
			throw new IllegalArgumentException(field + " must be a normalized absolute path");
		}
		return path;
	}

	public enum RecoveryOutcome {
		NOTHING_TO_RECOVER,
		SKIPPED_UNSAFE_LEGACY_PATH,
		COMPLETED_INSTALLATION,
		RESTORED_ORIGINAL
	}

	public enum TargetState {
		ABSENT,
		EMPTY,
		GENESIS_ONLY,
		INELIGIBLE;

		public boolean eligible() {
			return this != INELIGIBLE;
		}
	}

	private enum Phase {
		PREPARED,
		TARGET_MOVED,
		INSTALLED
	}

	enum DurableStep {
		JOURNALED,
		TARGET_MOVED,
		INSTALLED
	}

	private record Journal(String activationId, String preparedName, String backupName, Phase phase) {
		private Journal withPhase(Phase replacement) {
			return new Journal(activationId, preparedName, backupName, replacement);
		}
	}

	@FunctionalInterface
	private interface IoRunnable {
		void run() throws IOException;
	}

	@FunctionalInterface
	private interface IoSupplier<T> {
		T get() throws IOException;
	}

	@FunctionalInterface
	interface ActivationStepHook {
		void after(DurableStep step) throws IOException;
	}
}
