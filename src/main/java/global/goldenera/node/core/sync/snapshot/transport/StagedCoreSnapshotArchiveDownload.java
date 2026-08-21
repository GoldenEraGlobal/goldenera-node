/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
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

/** Complete, still non-activating state plus canonical-block archive staged on disk. */
public record StagedCoreSnapshotArchiveDownload(
		StagedSnapshotDownload stateSnapshot,
		CoreSnapshotArchiveTransportManifest transportManifest,
		CoreSnapshotArchiveManifest archiveManifest,
		Path archiveManifestFile,
		List<Path> blockChunkFiles) implements AutoCloseable {

	public StagedCoreSnapshotArchiveDownload {
		blockChunkFiles = List.copyOf(blockChunkFiles);
	}

	public CoreSnapshotArchiveChunkSource blockChunkSource() {
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
