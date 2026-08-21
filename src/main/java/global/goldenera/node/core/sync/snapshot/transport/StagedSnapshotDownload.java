/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.transport;

import java.nio.file.Path;
import java.util.List;

import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkSource;

public record StagedSnapshotDownload(
		SnapshotTransportManifest manifest,
		CheckpointSnapshotManifest domainManifest,
		Path stagingDirectory,
		Path manifestFile,
		List<Path> chunkFiles) {

	public StagedSnapshotDownload {
		chunkFiles = List.copyOf(chunkFiles);
	}

	/** Provides the verifier with forward-only node streams over the staged files. */
	public SnapshotChunkSource chunkSource() {
		return descriptor -> {
			if (descriptor.index() < 0 || descriptor.index() >= chunkFiles.size()) {
				throw new IllegalArgumentException("No staged file for snapshot chunk " + descriptor.index());
			}
			return new BinarySnapshotNodeSource(chunkFiles.get(descriptor.index()), descriptor);
		};
	}
}
