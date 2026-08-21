/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.transport;

import java.net.URI;
import java.nio.file.Path;

public interface CheckpointSnapshotTransport {

	StagedSnapshotDownload stage(URI trustedSource, Path stagingDirectory);
}
