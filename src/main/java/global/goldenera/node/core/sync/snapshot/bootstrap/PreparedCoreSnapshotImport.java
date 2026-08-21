/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.bootstrap;

import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;

/**
 * Opaque, isolated import prepared from a fully verified archive. Implementations
 * must not mutate the live core database while creating this object.
 */
public interface PreparedCoreSnapshotImport extends AutoCloseable {

	VerifiedCoreSnapshotArchive verifiedArchive();

	@Override
	void close() throws Exception;
}
