/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.bootstrap;

/** Activation may have touched live storage, so startup must stop instead of falling back. */
public final class CoreSnapshotBootstrapActivationException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public CoreSnapshotBootstrapActivationException(String message, Throwable cause) {
		super(message, cause);
	}
}
