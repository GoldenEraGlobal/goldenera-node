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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;

/** Isolated, closed RocksDB directory ready for a later filesystem activation step. */
public final class DiskPreparedCoreSnapshotImport implements PreparedCoreSnapshotImport {

	private final Path databaseDirectory;
	private final VerifiedCoreSnapshotArchive verifiedArchive;
	private final AtomicBoolean installed = new AtomicBoolean();
	private final AtomicBoolean closed = new AtomicBoolean();

	public DiskPreparedCoreSnapshotImport(
			Path databaseDirectory, VerifiedCoreSnapshotArchive verifiedArchive) {
		this.databaseDirectory = Objects.requireNonNull(databaseDirectory, "databaseDirectory");
		this.verifiedArchive = Objects.requireNonNull(verifiedArchive, "verifiedArchive");
	}

	public Path databaseDirectory() {
		return databaseDirectory;
	}

	@Override
	public VerifiedCoreSnapshotArchive verifiedArchive() {
		return verifiedArchive;
	}

	/** Transfers directory ownership to the activator after a successful install. */
	public void markInstalled() {
		if (closed.get()) {
			throw new IllegalStateException("Prepared snapshot import is already closed");
		}
		installed.set(true);
	}

	@Override
	public void close() throws IOException {
		if (!closed.compareAndSet(false, true) || installed.get()
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
