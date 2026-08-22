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
package global.goldenera.node.core.sync.snapshot.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveChunkSource;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkSource;

/** Complete, still non-activating state plus canonical-block archive staged on disk. */
public record StagedCoreSnapshotArchiveDownload(
		StagedSnapshotDownload stateSnapshot,
		CoreSnapshotArchiveTransportManifest transportManifest,
		CoreSnapshotArchiveManifest archiveManifest,
		Path archiveManifestFile,
		List<Path> blockChunkFiles,
		List<Path> entityChunkFiles) implements AutoCloseable {

	public StagedCoreSnapshotArchiveDownload {
		blockChunkFiles = List.copyOf(blockChunkFiles);
		entityChunkFiles = List.copyOf(entityChunkFiles);
	}

	public StagedCoreSnapshotArchiveDownload(
			StagedSnapshotDownload stateSnapshot,
			CoreSnapshotArchiveTransportManifest transportManifest,
			CoreSnapshotArchiveManifest archiveManifest,
			Path archiveManifestFile,
			List<Path> blockChunkFiles) {
		this(stateSnapshot, transportManifest, archiveManifest, archiveManifestFile, blockChunkFiles, List.of());
	}

	public CoreSnapshotArchiveChunkSource blockChunkSource() {
		return descriptor -> Files.newInputStream(fileFor(descriptor));
	}

	public CoreSnapshotEntityChunkSource entityChunkSource() {
		return descriptor -> Files.newInputStream(fileFor(descriptor));
	}

	private Path fileFor(CoreSnapshotBlockChunkDescriptor descriptor) {
		int index = descriptor.index();
		if (index < 0 || index >= archiveManifest.blockChunks().size()
				|| index >= blockChunkFiles.size()
				|| !archiveManifest.blockChunks().get(index).equals(descriptor)) {
			throw new IllegalArgumentException("No staged file for archive block chunk " + index);
		}
		return blockChunkFiles.get(index);
	}

	private Path fileFor(CoreSnapshotEntityChunkDescriptor descriptor) {
		int index = descriptor.index();
		if (index < 0 || index >= archiveManifest.entityChunks().size()
				|| index >= entityChunkFiles.size()
				|| !archiveManifest.entityChunks().get(index).equals(descriptor)) {
			throw new IllegalArgumentException("No staged file for entity chunk " + index);
		}
		return entityChunkFiles.get(index);
	}

	@Override
	public void close() throws IOException {
		Path directory = stateSnapshot.stagingDirectory();
		if (directory == null || Files.notExists(directory)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
