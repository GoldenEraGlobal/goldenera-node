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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.ClockMode;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.PowAlgorithm;

class SandboxManifestLoaderTest {

	private static final String VALID_RESOURCE = "sandbox/manifest/manifest-v1-valid.json";
	private static final String CANONICAL_RESOURCE = "sandbox/manifest/manifest-v1-canonical.json";
	private static final String GOLDEN_FINGERPRINT =
			"f39e05d5a4ef05450625e57304b68f801e2a6ab887d5f3b3b57e5c8ca8449d3d";

	private final SandboxManifestLoader loader = new SandboxManifestLoader();

	@TempDir
	Path temporaryDirectory;

	@Test
	void loadsStrictV1ManifestAndMatchesGoldenCanonicalFingerprint() throws Exception {
		SandboxManifestContext context = loader.load(write("manifest.json", resource(VALID_RESOURCE)));
		SandboxManifest manifest = context.manifest();

		assertThat(manifest.schemaVersion()).isEqualTo(1);
		assertThat(manifest.chainId()).isEqualTo("sandbox-00112233445566778899aabbccddeeff");
		assertThat(manifest.disposable()).isTrue();
		assertThat(manifest.legacyCarrier()).isEqualTo(new SandboxManifest.LegacyCarrier("TESTNET", 1));
		assertThat(manifest.pow().algorithm()).isEqualTo(PowAlgorithm.DETERMINISTIC_SHA256_V1);
		assertThat(manifest.clock().mode()).isEqualTo(ClockMode.DETERMINISTIC);
		assertThat(manifest.features().sandboxManifest()).isTrue();
		assertThat(manifest.features().deterministicClock()).isTrue();
		assertThat(manifest.features().legacyPeerCompatibility()).isTrue();
		assertThat(manifest.features().controlApi()).isFalse();
		assertThat(manifest.legacyPeers().allowlistedNodeIds())
				.containsExactly(
						"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
						"0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		assertThat(context.canonicalJsonUtf8()).isEqualTo(resource(CANONICAL_RESOURCE).stripTrailing());
		assertThat(context.fingerprint()).isEqualTo(GOLDEN_FINGERPRINT);
	}

	@Test
	void inputObjectAndSetOrderingDoesNotChangeCanonicalIdentity() throws Exception {
		SandboxManifestContext reordered = loader.load(write("reordered.json", resource(VALID_RESOURCE)));
		SandboxManifestContext canonical = loader.load(write("canonical.json", resource(CANONICAL_RESOURCE)));

		assertThat(reordered.canonicalJson()).isEqualTo(canonical.canonicalJson());
		assertThat(reordered.fingerprint()).isEqualTo(canonical.fingerprint());
	}

	@Test
	void dynamicIdentitySetOrderingDoesNotChangeCanonicalIdentity() throws Exception {
		String valid = resource(VALID_RESOURCE);
		String authority = "\"authorities\": [\n"
				+ "      \"0x1111111111111111111111111111111111111111\"\n"
				+ "    ]";
		String ascending = "\"authorities\": [\n"
				+ "      \"0x1111111111111111111111111111111111111111\",\n"
				+ "      \"0x3333333333333333333333333333333333333333\"\n"
				+ "    ]";
		String descending = "\"authorities\": [\n"
				+ "      \"0x3333333333333333333333333333333333333333\",\n"
				+ "      \"0x1111111111111111111111111111111111111111\"\n"
				+ "    ]";

		SandboxManifestContext first = loader.load(write("ascending.json", valid.replace(authority, ascending)));
		SandboxManifestContext second = loader.load(write("descending.json", valid.replace(authority, descending)));

		assertThat(first.canonicalJson()).isEqualTo(second.canonicalJson());
		assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
	}

	@Test
	void loadedContextAndNestedCollectionsAreImmutable() throws Exception {
		SandboxManifestContext context = loader.load(write("manifest.json", resource(VALID_RESOURCE)));
		byte[] leakedCopy = context.canonicalJson();
		leakedCopy[0] = 'x';

		assertThat(context.canonicalJsonUtf8()).startsWith("{");
		assertThatThrownBy(() -> context.manifest().forks().put(ForkName.GENESIS, 12L))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> context.manifest().genesis().initialBalances()
				.put("0x3333333333333333333333333333333333333333", BigInteger.ONE))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> context.manifest().legacyPeers().allowlistedNodeIds()
				.add("0xcccccccccccccccccccccccccccccccccccccccc"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsRelativeNonRegularAndSymbolicLinkPaths() throws Exception {
		Path target = write("target.json", resource(VALID_RESOURCE));
		Path link = temporaryDirectory.resolve("link.json");
		Files.createSymbolicLink(link, target);

		assertThatThrownBy(() -> loader.load(Path.of("manifest.json")))
				.isInstanceOf(SandboxManifestException.class)
				.hasMessageContaining("absolute");
		assertThatThrownBy(() -> loader.load(temporaryDirectory))
				.isInstanceOf(SandboxManifestException.class)
				.hasMessageContaining("regular file");
		assertThatThrownBy(() -> loader.load(link))
				.isInstanceOf(SandboxManifestException.class)
				.hasMessageContaining("without symlinks");
	}

	@Test
	void rejectsUnknownMissingAndDuplicateFields() throws Exception {
		String valid = resource(VALID_RESOURCE);

		assertInvalid(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"unknown\": true"),
				"unknown");
		assertInvalid(valid.replace("  \"chainId\": \"sandbox-00112233445566778899aabbccddeeff\",\n", ""),
				"missing");
		assertInvalid(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
				"Duplicate field");
		assertInvalid(valid.replace("\"algorithm\": \"DETERMINISTIC_SHA256_V1\"",
				"\"algorithm\": \"DETERMINISTIC_SHA256_V1\", \"memoryMode\": \"LIGHT\""), "unknown");
		assertInvalid(valid.replace(",\n    \"controlApi\": false", ""), "missing");
		assertInvalid(valid.replace("\"controlApi\": false", "\"futureFeature\": false"), "unknown");
	}

	@Test
	void requiresDisposableBooleanAndCompleteV1ForkSet() throws Exception {
		String valid = resource(VALID_RESOURCE);

		assertInvalid(valid.replace("\"disposable\": true", "\"disposable\": \"true\""), "must be a boolean");
		assertInvalid(valid.replace("    \"MINING_ECONOMICS\": 0,\n", ""), "missing");
		assertInvalid(valid.replace("\"MINING_ECONOMICS\": 0", "\"FUTURE_FORK\": 0"), "unknown");
	}

	@Test
	void enforcesFeatureCrossFieldSemantics() throws Exception {
		String valid = resource(VALID_RESOURCE);

		assertInvalid(valid.replace("\"sandboxManifest\": true", "\"sandboxManifest\": false"),
				"must be true");
		assertInvalid(valid.replace("\"deterministicClock\": true", "\"deterministicClock\": false"),
				"must match whether clock.mode is DETERMINISTIC");
		assertInvalid(valid.replace("\"legacyPeerCompatibility\": true",
				"\"legacyPeerCompatibility\": false"), "must match whether the legacy peer allowlist is non-empty");
		assertInvalid(valid.replace("\"disposable\": true", "\"disposable\": false")
				.replace("\"controlApi\": false", "\"controlApi\": true"),
				"requires disposable=true");
	}

	@Test
	void rejectsTrailingJsonAndNonIntegralConsensusNumbers() throws Exception {
		String valid = resource(VALID_RESOURCE);

		assertInvalid(valid + " true", "Trailing token");
		assertInvalid(valid.replace("\"targetBlockIntervalMs\": 1000", "\"targetBlockIntervalMs\": 1000.0"),
				"exact JSON integer");
	}

	@Test
	void rejectsNumericOverflowAndOutOfRangeValues() throws Exception {
		String valid = resource(VALID_RESOURCE);

		assertInvalid(valid.replace("\"timestampMs\": 1800000000000",
				"\"timestampMs\": 9223372036854775808"), "permitted range");
		assertInvalid(valid.replace("\"bipApprovalThresholdBps\": 5100",
				"\"bipApprovalThresholdBps\": 10001"), "permitted range");
		assertInvalid(valid.replace("\"maxTransactionSizeBytes\": 100000",
				"\"maxTransactionSizeBytes\": 6000000"), "must not exceed");
	}

	@Test
	void rejectsPublicIdentityOrNonTestnetLegacyCarrier() throws Exception {
		String valid = resource(VALID_RESOURCE);

		assertInvalid(valid.replace("sandbox-00112233445566778899aabbccddeeff", "TESTNET"),
				"high-entropy sandbox chain id");
		assertInvalid(valid.replace("\"network\": \"TESTNET\"", "\"network\": \"MAINNET\""),
				"must be TESTNET");
		assertInvalid(valid.replace("\"code\": 1", "\"code\": 0"), "permitted range");
	}

	@Test
	void legacyPeerAllowlistAcceptsOnlyCanonicalLowercaseTwentyByteAddresses() throws Exception {
		String valid = resource(VALID_RESOURCE);

		assertInvalid(valid.replace(
				"\"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
				"\"legacy-node-a\""), "invalid string value");
		assertInvalid(valid.replace(
				"\"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
				"\"0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\""), "invalid string value");
		assertInvalid(valid.replace(
				"\"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
				"\"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""), "invalid string value");
	}

	@Test
	void rejectsOversizedManifestBeforeParsing() throws Exception {
		Path path = temporaryDirectory.resolve("oversized.json");
		Files.write(path, new byte[SandboxManifestLoader.MAX_MANIFEST_BYTES + 1]);

		assertThatThrownBy(() -> loader.load(path))
				.isInstanceOf(SandboxManifestException.class)
				.hasMessageContaining("1 MiB");
	}

	@Test
	void rejectsNonUtf8AndByteOrderMark() throws Exception {
		Path invalidUtf8 = temporaryDirectory.resolve("invalid-utf8.json");
		Files.write(invalidUtf8, new byte[]{(byte) 0xc3, 0x28});
		Path withBom = temporaryDirectory.resolve("bom.json");
		byte[] json = resource(VALID_RESOURCE).getBytes(StandardCharsets.UTF_8);
		byte[] bomJson = new byte[json.length + 3];
		bomJson[0] = (byte) 0xef;
		bomJson[1] = (byte) 0xbb;
		bomJson[2] = (byte) 0xbf;
		System.arraycopy(json, 0, bomJson, 3, json.length);
		Files.write(withBom, bomJson);

		assertThatThrownBy(() -> loader.load(invalidUtf8))
				.isInstanceOf(SandboxManifestException.class)
				.hasMessageContaining("valid UTF-8");
		assertThatThrownBy(() -> loader.load(withBom))
				.isInstanceOf(SandboxManifestException.class)
				.hasMessageContaining("byte-order mark");
	}

	private void assertInvalid(String json, String messagePart) throws IOException {
		Path path = write("invalid-" + Math.abs(json.hashCode()) + ".json", json);
		assertThatThrownBy(() -> loader.load(path))
				.isInstanceOf(SandboxManifestException.class)
				.hasMessageContaining(messagePart);
	}

	private Path write(String fileName, String content) throws IOException {
		Path path = temporaryDirectory.resolve(fileName);
		Files.writeString(path, content, StandardCharsets.UTF_8);
		return path;
	}

	private String resource(String name) throws IOException {
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
			assertThat(stream).as("classpath resource %s", name).isNotNull();
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
