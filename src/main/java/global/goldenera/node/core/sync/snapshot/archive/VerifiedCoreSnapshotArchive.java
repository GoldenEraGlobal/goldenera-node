/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
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
 * consumers may preserve manifest-bound StoredBlock events as operational data,
 * but explorer/index data remains independently snapshotted and verified.
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
	private final int stateNodeCount;
	private final int entityChunkCount;
	private final long entityEntryCount;
	private final long entityEncodedBytes;

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
		this(checkpointHeight, checkpointHash, checkpointStateRoot, checkpointCumulativeDifficulty,
				stateManifestSigningHash, archiveManifestSigningHash, blockCount, chunkCount,
				encodedBytes, 0, 0, 0, 0);
	}

	VerifiedCoreSnapshotArchive(
			long checkpointHeight,
			Hash checkpointHash,
			Hash checkpointStateRoot,
			BigInteger checkpointCumulativeDifficulty,
			Hash stateManifestSigningHash,
			Hash archiveManifestSigningHash,
			long blockCount,
			int chunkCount,
			long encodedBytes,
			int stateNodeCount,
			int entityChunkCount,
			long entityEntryCount,
			long entityEncodedBytes) {
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
		this.stateNodeCount = stateNodeCount;
		this.entityChunkCount = entityChunkCount;
		this.entityEntryCount = entityEntryCount;
		this.entityEncodedBytes = entityEncodedBytes;
	}

	public boolean activationEligible() {
		return true;
	}

	/** Explorer indexes are independent from core activation eligibility. */
	public boolean requiresDerivedDataRebuild() {
		return true;
	}

	/** StoredBlock events are preserved as manifest-bound, non-consensus operational data. */
	public boolean preservesOperationalBlockEvents() {
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

	public int stateNodeCount() {
		return stateNodeCount;
	}

	public int entityChunkCount() {
		return entityChunkCount;
	}

	public long entityEntryCount() {
		return entityEntryCount;
	}

	public long entityEncodedBytes() {
		return entityEncodedBytes;
	}
}
