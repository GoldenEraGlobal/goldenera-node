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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

class IsolatedWorldStateStorageTest {

	@TempDir
	Path temporaryDirectory;

	@BeforeEach
	void canonicalizeTemporaryDirectory() throws Exception {
		temporaryDirectory = temporaryDirectory.toRealPath();
	}

	@Test
	void callerOwnedStorageRequiresAnEmptyAbsoluteDirectoryAndIsRetained() throws Exception {
		Path storageDirectory = temporaryDirectory.resolve("world-state");

		try (IsolatedWorldStateStorage storage = IsolatedWorldStateStorage.open(storageDirectory)) {
			assertThat(storage.worldStateFactory()).isNotNull();
		}

		assertThat(storageDirectory).isDirectory();
		assertThatThrownBy(() -> IsolatedWorldStateStorage.open(storageDirectory))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must be empty");
		assertThatThrownBy(() -> IsolatedWorldStateStorage.open(Path.of("relative")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must be absolute");
	}

	@Test
	void rejectsARegularFileAsStorageDirectory() throws Exception {
		Path file = Files.createFile(temporaryDirectory.resolve("not-a-directory"));

		assertThatThrownBy(() -> IsolatedWorldStateStorage.open(file))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must be a directory");
	}

	@Test
	void rejectsSymlinkInAnExistingStorageAncestor() throws Exception {
		Path real = Files.createDirectory(temporaryDirectory.resolve("real"));
		Path alias = temporaryDirectory.resolve("alias");
		Files.createSymbolicLink(alias, real);

		assertThatThrownBy(() -> IsolatedWorldStateStorage.open(alias.resolve("database")))
				.isInstanceOf(IOException.class);
	}

	@Test
	void initializationFailureClosesEveryAllocatedNativeResource() throws Exception {
		Path storageDirectory = Files.createDirectory(temporaryDirectory.resolve("failing-storage"));
		AtomicReference<DBOptions> databaseOptions = new AtomicReference<>();
		AtomicReference<List<ColumnFamilyOptions>> columnOptions = new AtomicReference<>();
		AtomicReference<List<ColumnFamilyHandle>> handles = new AtomicReference<>();
		AtomicReference<RocksDB> database = new AtomicReference<>();
		RuntimeException deliberateFailure = new RuntimeException("deliberate initialization failure");

		assertThatThrownBy(() -> IsolatedWorldStateStorage.openWithInitializationHook(
				storageDirectory,
				(options, columns, openedHandles, openedDatabase) -> {
					databaseOptions.set(options);
					columnOptions.set(List.copyOf(columns));
					handles.set(List.copyOf(openedHandles));
					database.set(openedDatabase);
					throw deliberateFailure;
				})).isSameAs(deliberateFailure);

		assertThat(databaseOptions.get().isOwningHandle()).isFalse();
		assertThat(columnOptions.get()).allSatisfy(option -> assertThat(option.isOwningHandle()).isFalse());
		assertThat(handles.get()).allSatisfy(handle -> assertThat(handle.isOwningHandle()).isFalse());
		assertThat(database.get().isOwningHandle()).isFalse();
	}
}
