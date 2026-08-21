/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import java.io.InputStream;

@FunctionalInterface
public interface CoreSnapshotArchiveChunkSource {

	InputStream open(CoreSnapshotBlockChunkDescriptor descriptor) throws Exception;
}
