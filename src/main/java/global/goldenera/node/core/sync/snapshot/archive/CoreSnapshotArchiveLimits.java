/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

public final class CoreSnapshotArchiveLimits {

	public static final int FORMAT_VERSION = 1;
	public static final int MAX_CHUNK_COUNT = 16_384;
	public static final int MAX_BLOCKS_PER_CHUNK = 4_096;
	public static final int MAX_ENCODED_BLOCK_BYTES = 48 * 1024 * 1024;
	public static final long MAX_CHUNK_BYTES = 256L * 1024 * 1024;
	public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024 * 1024;
	public static final long MAX_TOTAL_BLOCKS = (long) MAX_CHUNK_COUNT * MAX_BLOCKS_PER_CHUNK;

	private CoreSnapshotArchiveLimits() {
	}
}
