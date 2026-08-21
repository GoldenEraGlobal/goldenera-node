/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import java.util.Objects;

import global.goldenera.cryptoj.datatypes.Hash;

/** Deterministic description of one canonical StoredBlock stream chunk. */
public record CoreSnapshotBlockChunkDescriptor(
		int index,
		long firstHeight,
		long lastHeight,
		int blockCount,
		long byteCount,
		Hash contentHash) {

	public CoreSnapshotBlockChunkDescriptor {
		Objects.requireNonNull(contentHash, "contentHash");
	}
}
