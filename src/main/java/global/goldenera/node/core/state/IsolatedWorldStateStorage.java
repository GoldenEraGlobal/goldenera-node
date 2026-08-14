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
package global.goldenera.node.core.state;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.node.core.state.trie.rocksdb.RocksDBMerkleStorageFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;

/**
 * A deliberately isolated RocksDB-backed world-state factory for offline
 * calculations. It never opens a configured node database.
 */
public final class IsolatedWorldStateStorage implements AutoCloseable {

	private final Path directory;
	private final boolean deleteOnClose;
	private final DBOptions databaseOptions;
	private final List<ColumnFamilyOptions> columnOptions;
	private final List<ColumnFamilyHandle> handles = new ArrayList<>();
	private final RocksDB database;
	private final WorldStateFactory worldStateFactory;

	private IsolatedWorldStateStorage(
			Path directory,
			boolean deleteOnClose,
			InitializationHook initializationHook) throws RocksDBException {
		this.directory = directory;
		this.deleteOnClose = deleteOnClose;
		OpenedStorage opened = initialize(directory, initializationHook);
		databaseOptions = opened.databaseOptions();
		columnOptions = opened.columnOptions();
		handles.addAll(opened.handles());
		database = opened.database();
		worldStateFactory = opened.worldStateFactory();
	}

	private static OpenedStorage initialize(Path directory, InitializationHook initializationHook)
			throws RocksDBException {
		RocksDB.loadLibrary();
		DBOptions databaseOptions = null;
		List<ColumnFamilyOptions> columnOptions = new ArrayList<>();
		List<ColumnFamilyHandle> handles = new ArrayList<>();
		RocksDB database = null;
		try {
			databaseOptions = new DBOptions()
					.setCreateIfMissing(true)
					.setCreateMissingColumnFamilies(true);
			columnOptions.add(new ColumnFamilyOptions());
			columnOptions.add(new ColumnFamilyOptions());
			List<ColumnFamilyDescriptor> descriptors = List.of(
					new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnOptions.get(0)),
					new ColumnFamilyDescriptor(
							RocksDbColumnFamilies.CF_STATE_TRIE.getBytes(UTF_8), columnOptions.get(1)));
			database = RocksDB.open(databaseOptions, directory.toString(), descriptors, handles);
			initializationHook.afterDatabaseOpen(databaseOptions, columnOptions, handles, database);
			RocksDbColumnFamilies families = new RocksDbColumnFamilies();
			families.addHandle("default", handles.get(0));
			families.addHandle(RocksDbColumnFamilies.CF_STATE_TRIE, handles.get(1));
			RocksDBMerkleStorageFactory storage = new RocksDBMerkleStorageFactory(
					database, families, Caffeine.newBuilder().build());
			WorldStateSerialization serialization = new WorldStateSerialization();
			WorldStateFactory worldStateFactory = new WorldStateFactory(
					storage,
					serialization.rootStateSerializer(), serialization.rootStateDeserializer(),
					serialization.balanceSerializer(), serialization.balanceDeserializer(),
					serialization.nonceSerializer(), serialization.nonceDeserializer(),
					serialization.addressAliasSerializer(), serialization.addressAliasDeserializer(),
					serialization.authoritySerializer(), serialization.authorityDeserializer(),
					serialization.validatorSerializer(), serialization.validatorDeserializer(),
					serialization.bipStateSerializer(), serialization.bipStateDeserializer(),
					serialization.networkParamsSerializer(), serialization.networkParamsDeserializer(),
					serialization.miningWindowSerializer(), serialization.miningWindowDeserializer(),
					serialization.tokenSerializer(), serialization.tokenDeserializer());
			return new OpenedStorage(
					databaseOptions, columnOptions, List.copyOf(handles), database, worldStateFactory);
		} catch (Throwable failure) {
			closePartial(failure, handles, database, columnOptions, databaseOptions);
			if (failure instanceof RocksDBException rocksException) {
				throw rocksException;
			}
			if (failure instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (failure instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("Unexpected isolated RocksDB initialization failure", failure);
		}
	}

	/** Creates owned temporary storage which is recursively removed on close. */
	public static IsolatedWorldStateStorage temporary(String prefix) throws IOException, RocksDBException {
		Path directory = Files.createTempDirectory(prefix).toRealPath();
		try {
			DirectoryIdentity identity = validateDirectoryPath(directory);
			return openValidated(directory, true, InitializationHook.NOOP, identity);
		} catch (IOException | RocksDBException | RuntimeException e) {
			try {
				deleteDirectory(directory);
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			throw e;
		}
	}

	/**
	 * Opens a caller-owned empty absolute directory. Its contents are retained on
	 * close, which is useful for deterministic fixture diagnostics.
	 */
	public static IsolatedWorldStateStorage open(Path directory) throws IOException, RocksDBException {
		validateStrictPath(directory);
		if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalArgumentException("Isolated world-state path must be a directory without symlinks");
			}
			try (var entries = Files.list(directory)) {
				if (entries.findAny().isPresent()) {
					throw new IllegalArgumentException("Isolated world-state directory must be empty");
				}
			}
		} else {
			Path parent = directory.getParent();
			if (parent == null) {
				throw new IllegalArgumentException("Isolated world-state parent is missing");
			}
			validateDirectoryPath(parent);
			Files.createDirectory(directory);
		}
		DirectoryIdentity identity = validateDirectoryPath(directory);
		return openValidated(directory, false, InitializationHook.NOOP, identity);
	}

	static IsolatedWorldStateStorage openWithInitializationHook(
			Path directory,
			InitializationHook initializationHook) throws IOException, RocksDBException {
		validateStrictPath(directory);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("Isolated world-state path must be an existing directory");
		}
		DirectoryIdentity identity = validateDirectoryPath(directory);
		return openValidated(directory, false, initializationHook, identity);
	}

	public WorldStateFactory worldStateFactory() {
		return worldStateFactory;
	}

	@Override
	public void close() {
		for (int index = handles.size() - 1; index >= 0; index--) {
			handles.get(index).close();
		}
		database.close();
		for (int index = columnOptions.size() - 1; index >= 0; index--) {
			columnOptions.get(index).close();
		}
		databaseOptions.close();
		if (deleteOnClose) {
			try {
				deleteDirectory(directory);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to remove isolated world-state storage", e);
			}
		}
	}

	private static void deleteDirectory(Path directory) throws IOException {
		if (Files.notExists(directory)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void validateStrictPath(Path directory) {
		if (directory == null || !directory.isAbsolute()) {
			throw new IllegalArgumentException("Isolated world-state directory must be absolute");
		}
		if (!directory.equals(directory.normalize())) {
			throw new IllegalArgumentException("Isolated world-state directory must already be normalized");
		}
	}

	private static DirectoryIdentity validateDirectoryPath(Path directory) throws IOException {
		validateStrictPath(directory);
		List<Object> componentKeys = new ArrayList<>();
		Path current = directory.getRoot();
		componentKeys.add(directoryKey(current));
		for (Path component : directory) {
			current = current.resolve(component);
			componentKeys.add(directoryKey(current));
		}
		return new DirectoryIdentity(List.copyOf(componentKeys));
	}

	private static Object directoryKey(Path directory) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(
				directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null) {
			throw new IOException(
					"Isolated world-state path contains a symlink or unidentifiable directory: " + directory);
		}
		return attributes.fileKey();
	}

	private static IsolatedWorldStateStorage openValidated(
			Path directory,
			boolean deleteOnClose,
			InitializationHook initializationHook,
			DirectoryIdentity expectedIdentity) throws IOException, RocksDBException {
		IsolatedWorldStateStorage storage = new IsolatedWorldStateStorage(
				directory, deleteOnClose, initializationHook);
		try {
			if (!expectedIdentity.equals(validateDirectoryPath(directory))) {
				throw new IOException("Isolated world-state directory changed during initialization");
			}
			return storage;
		} catch (IOException | RuntimeException e) {
			try {
				storage.close();
			} catch (RuntimeException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw e;
		}
	}

	private static void closePartial(
			Throwable failure,
			List<ColumnFamilyHandle> handles,
			RocksDB database,
			List<ColumnFamilyOptions> columnOptions,
			DBOptions databaseOptions) {
		for (int index = handles.size() - 1; index >= 0; index--) {
			closeSuppressing(failure, handles.get(index));
		}
		closeSuppressing(failure, database);
		for (int index = columnOptions.size() - 1; index >= 0; index--) {
			closeSuppressing(failure, columnOptions.get(index));
		}
		closeSuppressing(failure, databaseOptions);
	}

	private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
		if (resource == null) {
			return;
		}
		try {
			resource.close();
		} catch (Throwable closeFailure) {
			if (closeFailure != failure) {
				failure.addSuppressed(closeFailure);
			}
		}
	}

	record OpenedStorage(
			DBOptions databaseOptions,
			List<ColumnFamilyOptions> columnOptions,
			List<ColumnFamilyHandle> handles,
			RocksDB database,
			WorldStateFactory worldStateFactory) {
	}

	private record DirectoryIdentity(List<Object> componentKeys) {
	}

	@FunctionalInterface
	interface InitializationHook {
		InitializationHook NOOP = (databaseOptions, columnOptions, handles, database) -> { };

		void afterDatabaseOpen(
				DBOptions databaseOptions,
				List<ColumnFamilyOptions> columnOptions,
				List<ColumnFamilyHandle> handles,
				RocksDB database);
	}
}
