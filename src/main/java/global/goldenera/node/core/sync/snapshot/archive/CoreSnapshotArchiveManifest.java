/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import java.util.List;
import java.util.Objects;

import global.goldenera.cryptoj.datatypes.Hash;

/**
 * FULL CORE archive manifest. The state manifest signing hash binds the archive
 * to the independently verified checkpoint state; block descriptors bind the
 * complete canonical StoredBlock history from genesis through that checkpoint.
 */
public record CoreSnapshotArchiveManifest(
		int formatVersion,
		Hash stateManifestSigningHash,
		List<CoreSnapshotBlockChunkDescriptor> blockChunks) {

	public CoreSnapshotArchiveManifest {
		Objects.requireNonNull(stateManifestSigningHash, "stateManifestSigningHash");
		blockChunks = List.copyOf(Objects.requireNonNull(blockChunks, "blockChunks"));
	}
}
