/*
 * The MIT License (MIT)
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 */
package global.goldenera.node.core.sync.snapshot.archive;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

import global.goldenera.cryptoj.datatypes.Hash;

/**
 * Non-activation capability proving that a bounded streaming pass validated the
 * complete genesis-to-checkpoint block history. Its constructor is deliberately
 * package-private: only the full archive verifier can mint it.
 */
public final class VerifiedCoreArchiveHistory {

	private final long checkpointHeight;
	private final Hash checkpointHash;
	private final BigInteger checkpointCumulativeDifficulty;
	private final long anchorHeight;
	private final Hash anchorHash;
	private final BigInteger anchorCumulativeDifficulty;

	VerifiedCoreArchiveHistory(
			long checkpointHeight,
			Hash checkpointHash,
			BigInteger checkpointCumulativeDifficulty,
			long anchorHeight,
			Hash anchorHash,
			BigInteger anchorCumulativeDifficulty) {
		this.checkpointHeight = checkpointHeight;
		this.checkpointHash = Objects.requireNonNull(checkpointHash, "checkpointHash");
		this.checkpointCumulativeDifficulty = Objects.requireNonNull(
				checkpointCumulativeDifficulty, "checkpointCumulativeDifficulty");
		this.anchorHeight = anchorHeight;
		this.anchorHash = Objects.requireNonNull(anchorHash, "anchorHash");
		this.anchorCumulativeDifficulty = Objects.requireNonNull(
				anchorCumulativeDifficulty, "anchorCumulativeDifficulty");
	}

	public long checkpointHeight() {
		return checkpointHeight;
	}

	public Hash checkpointHash() {
		return checkpointHash;
	}

	public BigInteger checkpointCumulativeDifficulty() {
		return checkpointCumulativeDifficulty;
	}

	public Optional<BigInteger> findCumulativeDifficulty(long height, Hash hash) {
		return height == anchorHeight && anchorHash.equals(hash)
				? Optional.of(anchorCumulativeDifficulty) : Optional.empty();
	}
}
