/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.bootstrap;

/**
 * Atomic live-database boundary. An implementation must either install the
 * complete prepared core database (state, blocks, indexes, metadata) or leave
 * the current database untouched. There is intentionally no state-only method.
 */
@FunctionalInterface
public interface CoreSnapshotCanonicalActivator {

	void activateCanonical(PreparedCoreSnapshotImport preparedImport) throws Exception;
}
