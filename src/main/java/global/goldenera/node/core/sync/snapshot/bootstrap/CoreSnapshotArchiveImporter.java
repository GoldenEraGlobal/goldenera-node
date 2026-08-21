/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.bootstrap;

import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;

/** Prepares a complete import outside the live core database. */
@FunctionalInterface
public interface CoreSnapshotArchiveImporter {

	PreparedCoreSnapshotImport prepare(
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verifiedArchive) throws Exception;
}
