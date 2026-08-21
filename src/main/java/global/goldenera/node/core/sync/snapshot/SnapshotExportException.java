/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot;

/** Fail-closed error raised while creating an offline checkpoint snapshot. */
public final class SnapshotExportException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SnapshotExportException(String message) {
		super(message);
	}

	public SnapshotExportException(String message, Throwable cause) {
		super(message, cause);
	}
}
