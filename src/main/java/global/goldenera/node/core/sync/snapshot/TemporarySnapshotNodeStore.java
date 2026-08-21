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
package global.goldenera.node.core.sync.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.NodeLoader;

/** Temporary, isolated disk store. Prefix 0 contains nodes; prefix 1 contains traversal marks. */
final class TemporarySnapshotNodeStore implements NodeLoader, AutoCloseable {

	private static final byte NODE_PREFIX = 0;
	private static final byte VISITED_PREFIX = 1;
	private static final byte[] PRESENT = { 1 };

	private final Path directory;
	private final Options options;
	private final RocksDB database;
	private long nodeCount;

	private TemporarySnapshotNodeStore(Path directory, Options options, RocksDB database) {
		this.directory = directory;
		this.options = options;
		this.database = database;
	}

	static TemporarySnapshotNodeStore create() {
		RocksDB.loadLibrary();
		Path directory = null;
		Options options = null;
		try {
			directory = Files.createTempDirectory("goldenera-snapshot-verify-").toRealPath();
			options = new Options().setCreateIfMissing(true);
			return new TemporarySnapshotNodeStore(directory, options, RocksDB.open(options, directory.toString()));
		} catch (IOException | RocksDBException e) {
			if (options != null) {
				options.close();
			}
			deleteQuietly(directory);
			throw new SnapshotVerificationException("Cannot create isolated snapshot staging store", e);
		}
	}

	void put(SnapshotNode node) {
		byte[] key = key(NODE_PREFIX, node.key());
		try {
			if (database.get(key) != null) {
				throw new SnapshotVerificationException("Duplicate trie node: " + node.key());
			}
			database.put(key, node.content().toArray());
			nodeCount++;
		} catch (RocksDBException e) {
			throw new SnapshotVerificationException("Cannot stage snapshot trie node", e);
		}
	}

	long nodeCount() {
		return nodeCount;
	}

	@Override
	public Optional<Bytes> getNode(Bytes location, Bytes32 hash) {
		Hash key = Hash.wrap(hash);
		try {
			byte[] content = database.get(key(NODE_PREFIX, key));
			if (content == null) {
				return Optional.empty();
			}
			database.put(key(VISITED_PREFIX, key), PRESENT);
			return Optional.of(Bytes.wrap(content));
		} catch (RocksDBException e) {
			throw new SnapshotVerificationException("Cannot read isolated snapshot staging store", e);
		}
	}

	boolean hasUnvisitedNodes() {
		try (RocksIterator iterator = database.newIterator()) {
			iterator.seek(new byte[] { NODE_PREFIX });
			while (iterator.isValid() && iterator.key().length == Hash.SIZE + 1
					&& iterator.key()[0] == NODE_PREFIX) {
				byte[] visitedKey = Arrays.copyOf(iterator.key(), iterator.key().length);
				visitedKey[0] = VISITED_PREFIX;
				if (database.get(visitedKey) == null) {
					return true;
				}
				iterator.next();
			}
			return false;
		} catch (RocksDBException e) {
			throw new SnapshotVerificationException("Cannot audit snapshot trie reachability", e);
		}
	}

	private static byte[] key(byte prefix, Hash hash) {
		byte[] key = new byte[Hash.SIZE + 1];
		key[0] = prefix;
		System.arraycopy(hash.toArray(), 0, key, 1, Hash.SIZE);
		return key;
	}

	@Override
	public void close() {
		database.close();
		options.close();
		try {
			deleteDirectory(directory);
		} catch (IOException e) {
			throw new SnapshotVerificationException("Cannot remove isolated snapshot staging store", e);
		}
	}

	private static void deleteQuietly(Path directory) {
		if (directory == null) {
			return;
		}
		try {
			deleteDirectory(directory);
		} catch (IOException ignored) {
			// Best effort cleanup while preserving the initialization failure.
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
}
