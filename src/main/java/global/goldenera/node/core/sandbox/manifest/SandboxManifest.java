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
package global.goldenera.node.core.sandbox.manifest;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import global.goldenera.node.Constants.ForkName;

/**
 * Immutable, fully parsed sandbox manifest version 1.
 *
 * <p>The legacy carrier is deliberately represented separately from the chain
 * identity. Its only currently valid value is TESTNET/code 1; it is not an
 * authoritative network identity.</p>
 */
public record SandboxManifest(
		int schemaVersion,
		String chainId,
		boolean disposable,
		LegacyCarrier legacyCarrier,
		Genesis genesis,
		SortedMap<ForkName, Long> forks,
		Consensus consensus,
		Pow pow,
		Clock clock,
		Features features,
		LegacyPeers legacyPeers) {

	public SandboxManifest {
		chainId = Objects.requireNonNull(chainId, "chainId");
		legacyCarrier = Objects.requireNonNull(legacyCarrier, "legacyCarrier");
		genesis = Objects.requireNonNull(genesis, "genesis");
		forks = immutableSortedMap(forks, "forks");
		consensus = Objects.requireNonNull(consensus, "consensus");
		pow = Objects.requireNonNull(pow, "pow");
		clock = Objects.requireNonNull(clock, "clock");
		features = Objects.requireNonNull(features, "features");
		legacyPeers = Objects.requireNonNull(legacyPeers, "legacyPeers");
	}

	private static <K extends Comparable<? super K>, V> SortedMap<K, V> immutableSortedMap(
			Map<K, V> source,
			String name) {
		Objects.requireNonNull(source, name);
		return Collections.unmodifiableSortedMap(new TreeMap<>(source));
	}

	public record LegacyCarrier(String network, int code) {
		public LegacyCarrier {
			network = Objects.requireNonNull(network, "network");
		}
	}

	public record Genesis(
			long timestampMs,
			String seed,
			String expectedGenesisHash,
			List<String> authorities,
			List<String> validators,
			SortedMap<String, BigInteger> initialBalances,
			String blockRewardPoolAddress,
			BigInteger initialMintForBlockReward,
			BigInteger initialMintForAuthority,
			BigInteger blockDifficulty,
			NativeToken nativeToken) {

		public Genesis {
			seed = Objects.requireNonNull(seed, "seed");
			expectedGenesisHash = Objects.requireNonNull(expectedGenesisHash, "expectedGenesisHash");
			authorities = List.copyOf(authorities);
			validators = List.copyOf(validators);
			initialBalances = immutableSortedMap(initialBalances, "initialBalances");
			blockRewardPoolAddress = Objects.requireNonNull(blockRewardPoolAddress, "blockRewardPoolAddress");
			initialMintForBlockReward = Objects.requireNonNull(initialMintForBlockReward, "initialMintForBlockReward");
			initialMintForAuthority = Objects.requireNonNull(initialMintForAuthority, "initialMintForAuthority");
			blockDifficulty = Objects.requireNonNull(blockDifficulty, "blockDifficulty");
			nativeToken = Objects.requireNonNull(nativeToken, "nativeToken");
		}
	}

	public record NativeToken(
			String name,
			String ticker,
			int decimals,
			String website,
			String logo,
			boolean userBurnable) {

		public NativeToken {
			name = Objects.requireNonNull(name, "name");
			ticker = Objects.requireNonNull(ticker, "ticker");
			website = Objects.requireNonNull(website, "website");
			logo = Objects.requireNonNull(logo, "logo");
		}
	}

	public record Consensus(
			BigInteger blockReward,
			long targetBlockIntervalMs,
			long asertHalfLifeBlocks,
			BigInteger minDifficulty,
			BigInteger minTransactionBaseFee,
			BigInteger minTransactionByteFee,
			long validatorMiningWindowBlocks,
			int bipApprovalThresholdBps,
			long bipExpirationPeriodMs,
			long maxHeaderSizeBytes,
			long maxTransactionSizeBytes,
			long maxBlockSizeBytes,
			long maxTransactionsPerBlock) {

		public Consensus {
			blockReward = Objects.requireNonNull(blockReward, "blockReward");
			minDifficulty = Objects.requireNonNull(minDifficulty, "minDifficulty");
			minTransactionBaseFee = Objects.requireNonNull(minTransactionBaseFee, "minTransactionBaseFee");
			minTransactionByteFee = Objects.requireNonNull(minTransactionByteFee, "minTransactionByteFee");
		}
	}

	public record Pow(PowAlgorithm algorithm, RandomX randomX, Deterministic deterministic) {
		public Pow {
			algorithm = Objects.requireNonNull(algorithm, "algorithm");
			randomX = Objects.requireNonNull(randomX, "randomX");
			deterministic = Objects.requireNonNull(deterministic, "deterministic");
		}
	}

	public enum PowAlgorithm {
		RANDOMX,
		DETERMINISTIC_SHA256_V1
	}

	public record RandomX(long epochLength, String genesisKey, int batchSize) {
		public RandomX {
			genesisKey = Objects.requireNonNull(genesisKey, "genesisKey");
		}
	}

	public record Deterministic(String domain) {
		public Deterministic {
			domain = Objects.requireNonNull(domain, "domain");
		}
	}

	public record Clock(ClockMode mode, long blockTimestampStepMs, long maxFutureSkewMs) {
		public Clock {
			mode = Objects.requireNonNull(mode, "mode");
		}
	}

	public enum ClockMode {
		DETERMINISTIC,
		PRODUCTION_LIKE
	}

	/** Exact feature/capability contract for manifest schema v1. */
	public record Features(
			boolean sandboxManifest,
			boolean deterministicClock,
			boolean legacyPeerCompatibility,
			boolean controlApi) {
	}

	public record LegacyPeers(List<String> allowlistedNodeIds) {
		public LegacyPeers {
			allowlistedNodeIds = List.copyOf(allowlistedNodeIds);
		}
	}
}
