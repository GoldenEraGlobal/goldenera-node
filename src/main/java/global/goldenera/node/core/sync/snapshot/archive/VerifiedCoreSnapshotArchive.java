/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import java.math.BigInteger;
import java.util.Objects;

import global.goldenera.cryptoj.datatypes.Hash;

/**
 * Capability returned only after both checkpoint state and the complete
 * canonical block archive have been verified. This type does not mutate or
 * activate any database by itself. StoredBlock events and explorer/index data
 * are publisher-carried derived data, not consensus-rooted facts; activation
 * consumers must rebuild them from verified blocks and state execution.
 */
public final class VerifiedCoreSnapshotArchive {

	private final long checkpointHeight;
	private final Hash checkpointHash;
	private final Hash checkpointStateRoot;
	private final BigInteger checkpointCumulativeDifficulty;
	private final Hash stateManifestSigningHash;
	private final Hash archiveManifestSigningHash;
	private final long blockCount;
	private final int chunkCount;
	private final long encodedBytes;

	VerifiedCoreSnapshotArchive(
			long checkpointHeight,
			Hash checkpointHash,
			Hash checkpointStateRoot,
			BigInteger checkpointCumulativeDifficulty,
			Hash stateManifestSigningHash,
			Hash archiveManifestSigningHash,
			long blockCount,
			int chunkCount,
			long encodedBytes) {
		this.checkpointHeight = checkpointHeight;
		this.checkpointHash = Objects.requireNonNull(checkpointHash, "checkpointHash");
		this.checkpointStateRoot = Objects.requireNonNull(checkpointStateRoot, "checkpointStateRoot");
		this.checkpointCumulativeDifficulty = Objects.requireNonNull(
				checkpointCumulativeDifficulty, "checkpointCumulativeDifficulty");
		this.stateManifestSigningHash = Objects.requireNonNull(
				stateManifestSigningHash, "stateManifestSigningHash");
		this.archiveManifestSigningHash = Objects.requireNonNull(
				archiveManifestSigningHash, "archiveManifestSigningHash");
		this.blockCount = blockCount;
		this.chunkCount = chunkCount;
		this.encodedBytes = encodedBytes;
	}

	public boolean activationEligible() {
		return true;
	}

	/** Derived StoredBlock events/explorer indexes must be rebuilt, never trusted. */
	public boolean requiresDerivedDataRebuild() {
		return true;
	}

	public long checkpointHeight() {
		return checkpointHeight;
	}

	public Hash checkpointHash() {
		return checkpointHash;
	}

	public Hash checkpointStateRoot() {
		return checkpointStateRoot;
	}

	public BigInteger checkpointCumulativeDifficulty() {
		return checkpointCumulativeDifficulty;
	}

	public Hash stateManifestSigningHash() {
		return stateManifestSigningHash;
	}

	public Hash archiveManifestSigningHash() {
		return archiveManifestSigningHash;
	}

	public long blockCount() {
		return blockCount;
	}

	public int chunkCount() {
		return chunkCount;
	}

	public long encodedBytes() {
		return encodedBytes;
	}
}
