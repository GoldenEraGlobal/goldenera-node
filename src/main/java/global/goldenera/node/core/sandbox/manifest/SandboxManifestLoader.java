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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Clock;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.ClockMode;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Consensus;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Deterministic;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Features;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Genesis;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.LegacyCarrier;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.LegacyPeers;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.NativeToken;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Pow;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.PowAlgorithm;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.RandomX;

/**
 * Strict loader and canonicalizer for a mounted sandbox manifest v1.
 *
 * <p>This class has no Spring or storage integration. Activation and persisted
 * guard checks consume its immutable result in later bootstrap phases.</p>
 */
public final class SandboxManifestLoader {

	public static final int MAX_MANIFEST_BYTES = 1024 * 1024;

	private static final int MAX_COLLECTION_ENTRIES = 1024;
	private static final BigInteger MAX_UINT_256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
	private static final Pattern CHAIN_ID = Pattern.compile("sandbox-[0-9a-f]{32,64}");
	private static final Pattern HASH = Pattern.compile("0x[0-9a-f]{64}");
	private static final Pattern SEED = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");
	private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,256}");
	private static final Pattern TOKEN_NAME = Pattern.compile("[A-Za-z0-9 ._-]{1,64}");
	private static final Pattern TOKEN_TICKER = Pattern.compile("[A-Za-z0-9]{1,16}");
	private static final Pattern HTTP_URL = Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{1,500}");
	private static final Set<String> V1_FORK_NAMES = Set.of(
			ForkName.GENESIS.name(),
			ForkName.MINING_ECONOMICS.name());

	private static final ObjectMapper PARSER = new ObjectMapper(JsonFactory.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.streamReadConstraints(StreamReadConstraints.builder()
					.maxNestingDepth(32)
					.maxNumberLength(128)
					.maxStringLength(8192)
					.build())
			.build())
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final ObjectMapper CANONICAL_WRITER = new ObjectMapper();

	public SandboxManifestContext load(Path path) {
		byte[] input = readMountedManifest(path);
		validateUtf8(input);
		JsonNode root;
		try {
			root = PARSER.readTree(input);
		} catch (IOException e) {
			throw new SandboxManifestException("Sandbox manifest is not strict JSON: " + safeMessage(e), e);
		}
		if (root == null) {
			throw invalid("$", "manifest must not be empty");
		}

		SandboxManifest manifest = parseManifest(root);
		byte[] canonicalJson = canonicalize(manifest);
		return new SandboxManifestContext(manifest, canonicalJson, sha256(canonicalJson));
	}

	private void validateUtf8(byte[] input) {
		if (input.length >= 3 && input[0] == (byte) 0xef && input[1] == (byte) 0xbb && input[2] == (byte) 0xbf) {
			throw new SandboxManifestException("Sandbox manifest must be UTF-8 without a byte-order mark");
		}
		try {
			StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(input));
		} catch (CharacterCodingException e) {
			throw new SandboxManifestException("Sandbox manifest must contain valid UTF-8", e);
		}
	}

	private byte[] readMountedManifest(Path path) {
		if (path == null || !path.isAbsolute()) {
			throw new SandboxManifestException("Sandbox manifest path must be absolute");
		}
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new SandboxManifestException("Sandbox manifest path must be a regular file without symlinks");
		}
		try {
			if (Files.size(path) > MAX_MANIFEST_BYTES) {
				throw new SandboxManifestException("Sandbox manifest exceeds the 1 MiB limit");
			}
			try (InputStream stream = Files.newInputStream(path)) {
				byte[] bytes = stream.readNBytes(MAX_MANIFEST_BYTES + 1);
				if (bytes.length > MAX_MANIFEST_BYTES) {
					throw new SandboxManifestException("Sandbox manifest exceeds the 1 MiB limit");
				}
				return bytes;
			}
		} catch (IOException e) {
			throw new SandboxManifestException("Cannot read sandbox manifest", e);
		}
	}

	private SandboxManifest parseManifest(JsonNode root) {
		ObjectNode object = exactObject(root, "$", Set.of(
				"schemaVersion", "chainId", "disposable", "legacyCarrier", "genesis", "forks", "consensus", "pow",
				"clock", "features", "legacyPeers"));
		int schemaVersion = exactInt(object, "schemaVersion", 1, 1, "$" );
		String chainId = matchingString(object, "chainId", CHAIN_ID, "$", "high-entropy sandbox chain id");
		boolean disposable = exactBoolean(object, "disposable", "$");

		LegacyCarrier legacyCarrier = parseLegacyCarrier(required(object, "legacyCarrier", "$"));
		Genesis genesis = parseGenesis(required(object, "genesis", "$"));
		SortedMap<ForkName, Long> forks = parseForks(required(object, "forks", "$"));
		Consensus consensus = parseConsensus(required(object, "consensus", "$"));
		Pow pow = parsePow(required(object, "pow", "$"));
		Clock clock = parseClock(required(object, "clock", "$"));
		Features features = parseFeatures(required(object, "features", "$"));
		LegacyPeers legacyPeers = parseLegacyPeers(required(object, "legacyPeers", "$"));

		if (consensus.maxTransactionSizeBytes() > consensus.maxBlockSizeBytes()) {
			throw invalid("$.consensus", "maxTransactionSizeBytes must not exceed maxBlockSizeBytes");
		}
		validateFeatureSemantics(disposable, features, clock, legacyPeers);
		return new SandboxManifest(schemaVersion, chainId, disposable, legacyCarrier, genesis, forks, consensus, pow, clock,
				features, legacyPeers);
	}

	private LegacyCarrier parseLegacyCarrier(JsonNode node) {
		ObjectNode object = exactObject(node, "$.legacyCarrier", Set.of("network", "code"));
		String network = matchingString(object, "network", Pattern.compile("TESTNET"), "$.legacyCarrier",
				"TESTNET");
		int code = exactInt(object, "code", 1, 1, "$.legacyCarrier");
		return new LegacyCarrier(network, code);
	}

	private Genesis parseGenesis(JsonNode node) {
		String path = "$.genesis";
		ObjectNode object = exactObject(node, path, Set.of(
				"timestampMs", "seed", "expectedGenesisHash", "authorities", "validators", "initialBalances",
				"blockRewardPoolAddress", "initialMintForBlockReward", "initialMintForAuthority",
				"blockDifficulty", "nativeToken"));
		long timestamp = exactLong(object, "timestampMs", 1, Long.MAX_VALUE, path);
		String seed = matchingString(object, "seed", SEED, path, "64 lowercase hexadecimal characters");
		String expectedHash = matchingString(object, "expectedGenesisHash", HASH, path,
				"0x-prefixed lowercase SHA-256 hash");
		List<String> authorities = parseUniqueStrings(required(object, "authorities", path), path + ".authorities",
				ADDRESS, false).stream().sorted().toList();
		List<String> validators = parseUniqueStrings(required(object, "validators", path), path + ".validators",
				ADDRESS, false).stream().sorted().toList();
		SortedMap<String, BigInteger> balances = parseBalances(required(object, "initialBalances", path));
		String rewardPool = matchingString(object, "blockRewardPoolAddress", ADDRESS, path,
				"a canonical lowercase address");
		BigInteger rewardMint = exactBigInteger(object, "initialMintForBlockReward", BigInteger.ZERO,
				MAX_UINT_256, path);
		BigInteger authorityMint = exactBigInteger(object, "initialMintForAuthority", BigInteger.ZERO,
				MAX_UINT_256, path);
		BigInteger blockDifficulty = exactBigInteger(object, "blockDifficulty", BigInteger.ONE, MAX_UINT_256, path);
		NativeToken nativeToken = parseNativeToken(required(object, "nativeToken", path));
		return new Genesis(timestamp, seed, expectedHash, authorities, validators, balances, rewardPool, rewardMint,
				authorityMint, blockDifficulty, nativeToken);
	}

	private NativeToken parseNativeToken(JsonNode node) {
		String path = "$.genesis.nativeToken";
		ObjectNode object = exactObject(node, path,
				Set.of("name", "ticker", "decimals", "website", "logo", "userBurnable"));
		JsonNode burnable = required(object, "userBurnable", path);
		if (!burnable.isBoolean()) {
			throw invalid(path + ".userBurnable", "must be a boolean");
		}
		return new NativeToken(
				matchingString(object, "name", TOKEN_NAME, path, "a bounded ASCII token name"),
				matchingString(object, "ticker", TOKEN_TICKER, path, "an ASCII token ticker"),
				exactInt(object, "decimals", 0, 18, path),
				matchingString(object, "website", HTTP_URL, path, "an HTTP(S) URL"),
				matchingString(object, "logo", HTTP_URL, path, "an HTTP(S) URL"),
				burnable.booleanValue());
	}

	private SortedMap<String, BigInteger> parseBalances(JsonNode node) {
		String path = "$.genesis.initialBalances";
		ObjectNode object = object(node, path);
		checkCollectionSize(object.size(), path, false);
		SortedMap<String, BigInteger> balances = new TreeMap<>();
		for (Map.Entry<String, JsonNode> field : object.properties()) {
			if (!ADDRESS.matcher(field.getKey()).matches()) {
				throw invalid(path, "invalid address key: " + field.getKey());
			}
			balances.put(field.getKey(), integral(field.getValue(), path + "." + field.getKey(), BigInteger.ZERO,
					MAX_UINT_256));
		}
		return balances;
	}

	private SortedMap<ForkName, Long> parseForks(JsonNode node) {
		String path = "$.forks";
		ObjectNode object = exactObject(node, path, V1_FORK_NAMES);
		SortedMap<ForkName, Long> forks = new TreeMap<>();
		forks.put(ForkName.GENESIS, exactLong(object, ForkName.GENESIS.name(), 0, Long.MAX_VALUE, path));
		forks.put(ForkName.MINING_ECONOMICS,
				exactLong(object, ForkName.MINING_ECONOMICS.name(), 0, Long.MAX_VALUE, path));
		return forks;
	}

	private Consensus parseConsensus(JsonNode node) {
		String path = "$.consensus";
		ObjectNode object = exactObject(node, path, Set.of(
				"blockReward", "targetBlockIntervalMs", "asertHalfLifeBlocks", "minDifficulty",
				"minTransactionBaseFee", "minTransactionByteFee", "validatorMiningWindowBlocks",
				"bipApprovalThresholdBps", "bipExpirationPeriodMs", "maxHeaderSizeBytes",
				"maxTransactionSizeBytes", "maxBlockSizeBytes", "maxTransactionsPerBlock"));
		return new Consensus(
				exactBigInteger(object, "blockReward", BigInteger.ZERO, MAX_UINT_256, path),
				exactLong(object, "targetBlockIntervalMs", 1, 86_400_000, path),
				exactLong(object, "asertHalfLifeBlocks", 1, 1_000_000, path),
				exactBigInteger(object, "minDifficulty", BigInteger.ONE, MAX_UINT_256, path),
				exactBigInteger(object, "minTransactionBaseFee", BigInteger.ZERO, MAX_UINT_256, path),
				exactBigInteger(object, "minTransactionByteFee", BigInteger.ZERO, MAX_UINT_256, path),
				exactLong(object, "validatorMiningWindowBlocks", 100, 10_000, path),
				exactInt(object, "bipApprovalThresholdBps", 1, 10_000, path),
				exactLong(object, "bipExpirationPeriodMs", 1, Long.MAX_VALUE, path),
				exactLong(object, "maxHeaderSizeBytes", 1, 16L * 1024 * 1024, path),
				exactLong(object, "maxTransactionSizeBytes", 1, 64L * 1024 * 1024, path),
				exactLong(object, "maxBlockSizeBytes", 1, 512L * 1024 * 1024, path),
				exactLong(object, "maxTransactionsPerBlock", 1, 10_000_000, path));
	}

	private Pow parsePow(JsonNode node) {
		String path = "$.pow";
		ObjectNode object = exactObject(node, path, Set.of("algorithm", "randomX", "deterministic"));
		PowAlgorithm algorithm = enumValue(object, "algorithm", PowAlgorithm.class, path);

		String randomXPath = path + ".randomX";
		ObjectNode randomX = exactObject(required(object, "randomX", path), randomXPath,
				Set.of("epochLength", "genesisKey", "batchSize"));
		RandomX parsedRandomX = new RandomX(
				exactLong(randomX, "epochLength", 1, 10_000_000, randomXPath),
				matchingString(randomX, "genesisKey", SAFE_VALUE, randomXPath, "bounded ASCII genesis key"),
				exactInt(randomX, "batchSize", 1, 10_000_000, randomXPath));

		String deterministicPath = path + ".deterministic";
		ObjectNode deterministic = exactObject(required(object, "deterministic", path), deterministicPath,
				Set.of("domain"));
		Deterministic parsedDeterministic = new Deterministic(matchingString(deterministic, "domain", SAFE_VALUE,
				deterministicPath, "bounded ASCII proof domain"));
		return new Pow(algorithm, parsedRandomX, parsedDeterministic);
	}

	private Clock parseClock(JsonNode node) {
		String path = "$.clock";
		ObjectNode object = exactObject(node, path, Set.of("mode", "blockTimestampStepMs", "maxFutureSkewMs"));
		return new Clock(
				enumValue(object, "mode", ClockMode.class, path),
				exactLong(object, "blockTimestampStepMs", 1, 86_400_000, path),
				exactLong(object, "maxFutureSkewMs", 0, 86_400_000, path));
	}

	private Features parseFeatures(JsonNode node) {
		String path = "$.features";
		ObjectNode object = exactObject(node, path, Set.of(
				"sandboxManifest", "deterministicClock", "legacyPeerCompatibility", "controlApi"));
		return new Features(
				exactBoolean(object, "sandboxManifest", path),
				exactBoolean(object, "deterministicClock", path),
				exactBoolean(object, "legacyPeerCompatibility", path),
				exactBoolean(object, "controlApi", path));
	}

	private void validateFeatureSemantics(
			boolean disposable,
			Features features,
			Clock clock,
			LegacyPeers legacyPeers) {
		if (!features.sandboxManifest()) {
			throw invalid("$.features.sandboxManifest", "must be true for a sandbox manifest");
		}
		boolean deterministicClock = clock.mode() == ClockMode.DETERMINISTIC;
		if (features.deterministicClock() != deterministicClock) {
			throw invalid("$.features.deterministicClock", "must match whether clock.mode is DETERMINISTIC");
		}
		boolean legacyCompatibility = !legacyPeers.allowlistedNodeIds().isEmpty();
		if (features.legacyPeerCompatibility() != legacyCompatibility) {
			throw invalid("$.features.legacyPeerCompatibility",
					"must match whether the legacy peer allowlist is non-empty");
		}
		if (features.controlApi() && !disposable) {
			throw invalid("$.features.controlApi", "requires disposable=true");
		}
	}

	private LegacyPeers parseLegacyPeers(JsonNode node) {
		String path = "$.legacyPeers";
		ObjectNode object = exactObject(node, path, Set.of("allowlistedNodeIds"));
		List<String> nodeIds = parseUniqueStrings(required(object, "allowlistedNodeIds", path),
				path + ".allowlistedNodeIds", ADDRESS, true);
		return new LegacyPeers(nodeIds.stream().sorted().toList());
	}

	private List<String> parseUniqueStrings(JsonNode node, String path, Pattern pattern, boolean mayBeEmpty) {
		if (!node.isArray()) {
			throw invalid(path, "must be an array");
		}
		checkCollectionSize(node.size(), path, mayBeEmpty);
		List<String> values = new ArrayList<>(node.size());
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode entry = node.get(index);
			if (!entry.isTextual() || !pattern.matcher(entry.textValue()).matches()) {
				throw invalid(path + "[" + index + "]", "has an invalid string value");
			}
			if (!seen.add(entry.textValue())) {
				throw invalid(path, "must not contain duplicate values");
			}
			values.add(entry.textValue());
		}
		return List.copyOf(values);
	}

	private byte[] canonicalize(SandboxManifest manifest) {
		ObjectNode root = CANONICAL_WRITER.createObjectNode();
		root.put("schemaVersion", manifest.schemaVersion());
		root.put("chainId", manifest.chainId());
		root.put("disposable", manifest.disposable());

		ObjectNode carrier = root.putObject("legacyCarrier");
		carrier.put("network", manifest.legacyCarrier().network());
		carrier.put("code", manifest.legacyCarrier().code());

		ObjectNode genesis = root.putObject("genesis");
		genesis.put("timestampMs", manifest.genesis().timestampMs());
		genesis.put("seed", manifest.genesis().seed());
		genesis.put("expectedGenesisHash", manifest.genesis().expectedGenesisHash());
		putStrings(genesis.putArray("authorities"), manifest.genesis().authorities());
		putStrings(genesis.putArray("validators"), manifest.genesis().validators());
		ObjectNode balances = genesis.putObject("initialBalances");
		manifest.genesis().initialBalances().forEach(balances::put);
		genesis.put("blockRewardPoolAddress", manifest.genesis().blockRewardPoolAddress());
		genesis.put("initialMintForBlockReward", manifest.genesis().initialMintForBlockReward());
		genesis.put("initialMintForAuthority", manifest.genesis().initialMintForAuthority());
		genesis.put("blockDifficulty", manifest.genesis().blockDifficulty());
		ObjectNode nativeToken = genesis.putObject("nativeToken");
		nativeToken.put("name", manifest.genesis().nativeToken().name());
		nativeToken.put("ticker", manifest.genesis().nativeToken().ticker());
		nativeToken.put("decimals", manifest.genesis().nativeToken().decimals());
		nativeToken.put("website", manifest.genesis().nativeToken().website());
		nativeToken.put("logo", manifest.genesis().nativeToken().logo());
		nativeToken.put("userBurnable", manifest.genesis().nativeToken().userBurnable());

		ObjectNode forks = root.putObject("forks");
		forks.put(ForkName.GENESIS.name(), manifest.forks().get(ForkName.GENESIS));
		forks.put(ForkName.MINING_ECONOMICS.name(), manifest.forks().get(ForkName.MINING_ECONOMICS));

		Consensus consensusValue = manifest.consensus();
		ObjectNode consensus = root.putObject("consensus");
		consensus.put("blockReward", consensusValue.blockReward());
		consensus.put("targetBlockIntervalMs", consensusValue.targetBlockIntervalMs());
		consensus.put("asertHalfLifeBlocks", consensusValue.asertHalfLifeBlocks());
		consensus.put("minDifficulty", consensusValue.minDifficulty());
		consensus.put("minTransactionBaseFee", consensusValue.minTransactionBaseFee());
		consensus.put("minTransactionByteFee", consensusValue.minTransactionByteFee());
		consensus.put("validatorMiningWindowBlocks", consensusValue.validatorMiningWindowBlocks());
		consensus.put("bipApprovalThresholdBps", consensusValue.bipApprovalThresholdBps());
		consensus.put("bipExpirationPeriodMs", consensusValue.bipExpirationPeriodMs());
		consensus.put("maxHeaderSizeBytes", consensusValue.maxHeaderSizeBytes());
		consensus.put("maxTransactionSizeBytes", consensusValue.maxTransactionSizeBytes());
		consensus.put("maxBlockSizeBytes", consensusValue.maxBlockSizeBytes());
		consensus.put("maxTransactionsPerBlock", consensusValue.maxTransactionsPerBlock());

		ObjectNode pow = root.putObject("pow");
		pow.put("algorithm", manifest.pow().algorithm().name());
		ObjectNode randomX = pow.putObject("randomX");
		randomX.put("epochLength", manifest.pow().randomX().epochLength());
		randomX.put("genesisKey", manifest.pow().randomX().genesisKey());
		randomX.put("batchSize", manifest.pow().randomX().batchSize());
		ObjectNode deterministic = pow.putObject("deterministic");
		deterministic.put("domain", manifest.pow().deterministic().domain());

		ObjectNode clock = root.putObject("clock");
		clock.put("mode", manifest.clock().mode().name());
		clock.put("blockTimestampStepMs", manifest.clock().blockTimestampStepMs());
		clock.put("maxFutureSkewMs", manifest.clock().maxFutureSkewMs());

		ObjectNode features = root.putObject("features");
		features.put("sandboxManifest", manifest.features().sandboxManifest());
		features.put("deterministicClock", manifest.features().deterministicClock());
		features.put("legacyPeerCompatibility", manifest.features().legacyPeerCompatibility());
		features.put("controlApi", manifest.features().controlApi());

		ObjectNode legacyPeers = root.putObject("legacyPeers");
		putStrings(legacyPeers.putArray("allowlistedNodeIds"), manifest.legacyPeers().allowlistedNodeIds());

		try {
			return CANONICAL_WRITER.writeValueAsBytes(root);
		} catch (IOException e) {
			throw new SandboxManifestException("Cannot canonicalize validated sandbox manifest", e);
		}
	}

	private void putStrings(ArrayNode array, List<String> values) {
		values.forEach(array::add);
	}

	private ObjectNode exactObject(JsonNode node, String path, Set<String> expectedFields) {
		ObjectNode object = object(node, path);
		Set<String> actualFields = new HashSet<>();
		object.properties().forEach(field -> actualFields.add(field.getKey()));
		if (!actualFields.equals(expectedFields)) {
			Set<String> missing = new HashSet<>(expectedFields);
			missing.removeAll(actualFields);
			Set<String> unknown = new HashSet<>(actualFields);
			unknown.removeAll(expectedFields);
			throw invalid(path, "fields differ; missing=" + missing + ", unknown=" + unknown);
		}
		return object;
	}

	private ObjectNode object(JsonNode node, String path) {
		if (!node.isObject()) {
			throw invalid(path, "must be an object");
		}
		return (ObjectNode) node;
	}

	private JsonNode required(ObjectNode object, String field, String path) {
		JsonNode node = object.get(field);
		if (node == null || node.isNull()) {
			throw invalid(path + "." + field, "is required and must not be null");
		}
		return node;
	}

	private String matchingString(ObjectNode object, String field, Pattern pattern, String path, String expectation) {
		JsonNode node = required(object, field, path);
		if (!node.isTextual() || !pattern.matcher(node.textValue()).matches()) {
			throw invalid(path + "." + field, "must be " + expectation);
		}
		return node.textValue();
	}

	private int exactInt(ObjectNode object, String field, int minimum, int maximum, String path) {
		return integral(required(object, field, path), path + "." + field, BigInteger.valueOf(minimum),
				BigInteger.valueOf(maximum)).intValueExact();
	}

	private boolean exactBoolean(ObjectNode object, String field, String path) {
		JsonNode value = required(object, field, path);
		if (!value.isBoolean()) {
			throw invalid(path + "." + field, "must be a boolean");
		}
		return value.booleanValue();
	}

	private long exactLong(ObjectNode object, String field, long minimum, long maximum, String path) {
		return longValue(required(object, field, path), path + "." + field, minimum, maximum);
	}

	private long longValue(JsonNode node, String path, long minimum, long maximum) {
		return integral(node, path, BigInteger.valueOf(minimum), BigInteger.valueOf(maximum)).longValueExact();
	}

	private BigInteger exactBigInteger(
			ObjectNode object,
			String field,
			BigInteger minimum,
			BigInteger maximum,
			String path) {
		return integral(required(object, field, path), path + "." + field, minimum, maximum);
	}

	private BigInteger integral(JsonNode node, String path, BigInteger minimum, BigInteger maximum) {
		if (!node.isIntegralNumber()) {
			throw invalid(path, "must be an exact JSON integer");
		}
		BigInteger value = node.bigIntegerValue();
		if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
			throw invalid(path, "is outside the permitted range [" + minimum + ", " + maximum + "]");
		}
		return value;
	}

	private <E extends Enum<E>> E enumValue(ObjectNode object, String field, Class<E> type, String path) {
		JsonNode node = required(object, field, path);
		if (!node.isTextual()) {
			throw invalid(path + "." + field, "must be a string enum value");
		}
		try {
			return Enum.valueOf(type, node.textValue());
		} catch (IllegalArgumentException e) {
			throw invalid(path + "." + field, "has unsupported value " + node.textValue());
		}
	}

	private void checkCollectionSize(int size, String path, boolean mayBeEmpty) {
		if ((!mayBeEmpty && size == 0) || size > MAX_COLLECTION_ENTRIES) {
			throw invalid(path, "must contain " + (mayBeEmpty ? "at most " : "between 1 and ")
					+ MAX_COLLECTION_ENTRIES + " entries");
		}
	}

	private String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Required SHA-256 algorithm is unavailable", e);
		}
	}

	private SandboxManifestException invalid(String path, String reason) {
		return new SandboxManifestException("Invalid sandbox manifest at " + path + ": " + reason);
	}

	private String safeMessage(IOException exception) {
		String message = exception instanceof JsonProcessingException processingException
				? processingException.getOriginalMessage()
				: null;
		return message == null ? exception.getClass().getSimpleName() : message;
	}
}
