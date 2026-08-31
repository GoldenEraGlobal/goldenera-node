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
package global.goldenera.node.core.sync.snapshot.operator;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

/** Closed consistent RocksDB checkpoint bound to one captured canonical head. */
public final class LiveHeadCoreSnapshotClone implements AutoCloseable {

	private final Path databaseDirectory;
	private final long height;
	private final Hash hash;
	private final Hash stateRoot;
	private final BigInteger cumulativeDifficulty;
	private final StoredChainIdentity identity;
	private final AtomicBoolean closed = new AtomicBoolean();

	LiveHeadCoreSnapshotClone(
			Path databaseDirectory,
			long height,
			Hash hash,
			Hash stateRoot,
			BigInteger cumulativeDifficulty,
			StoredChainIdentity identity) {
		this.databaseDirectory = databaseDirectory;
		this.height = height;
		this.hash = hash;
		this.stateRoot = stateRoot;
		this.cumulativeDifficulty = cumulativeDifficulty;
		this.identity = identity;
	}

	public Path databaseDirectory() {
		return databaseDirectory;
	}

	public long height() {
		return height;
	}

	public Hash hash() {
		return hash;
	}

	public Hash stateRoot() {
		return stateRoot;
	}

	public BigInteger cumulativeDifficulty() {
		return cumulativeDifficulty;
	}

	public StoredChainIdentity identity() {
		return identity;
	}

	@Override
	public void close() throws IOException {
		if (!closed.compareAndSet(false, true)
				|| Files.notExists(databaseDirectory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(databaseDirectory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
