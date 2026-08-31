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
package global.goldenera.node.core.blockchain.genesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.sandbox.genesis.SandboxNetworkSettingsAdapter;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;

class SandboxGenesisPlanFactoryTest {

	private static final String EXPECTED_PLACEHOLDER =
			"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@TempDir
	Path temporaryDirectory;

	@Test
	void distinctChainIdsProduceDistinctGenesisHashesWithIdenticalStateInputs() throws Exception {
		String json = resource();
		SandboxManifestContext first = loadManifest("first.json", json);
		SandboxManifestContext second = loadManifest("second.json", json.replace(
				"sandbox-00112233445566778899aabbccddeeff",
				"sandbox-00112233445566778899aabbccddeefe"));

		try (PersistentWorldStateTestSupport storage = storage("chain-id")) {
			SandboxGenesisPlanFactory factory = factory(storage, mock(BlockStateTransitions.class));
			Hash firstHash = factory.calculateGenesisHash(first);
			Hash secondHash = factory.calculateGenesisHash(second);

			assertThat(firstHash).isNotEqualTo(secondHash);
		}
	}

	@Test
	void distinctGenesisSeedsProduceDistinctGenesisHashesWithIdenticalChainIdAndState() throws Exception {
		String json = resource();
		SandboxManifestContext first = loadManifest("seed-first.json", json);
		SandboxManifestContext second = loadManifest("seed-second.json", json.replace(
				"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
				"1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));

		try (PersistentWorldStateTestSupport storage = storage("genesis-seed")) {
			SandboxGenesisPlanFactory factory = factory(storage, mock(BlockStateTransitions.class));

			assertThat(factory.calculateGenesisHash(first))
					.isNotEqualTo(factory.calculateGenesisHash(second));
		}
	}

	@Test
	void rejectsExpectedHashMismatchBeforeAnyPersistenceCall() throws Exception {
		BlockStateTransitions transitions = mock(BlockStateTransitions.class);
		try (PersistentWorldStateTestSupport storage = storage("mismatch")) {
			SandboxGenesisPlanFactory factory = factory(storage, transitions);
			SandboxManifestContext context = loadManifest("mismatch.json", resource());

			assertThatThrownBy(() -> factory.createVerified(context))
					.isInstanceOf(SandboxGenesisException.class)
					.hasMessageContaining("expected " + EXPECTED_PLACEHOLDER)
					.hasMessageContaining("calculated 0x");
			verifyNoInteractions(transitions);
		}
	}

	@Test
	void verifiedPlanUsesManifestBalancesAndExpectedHash() throws Exception {
		String json = resource().replace(
				"\"0x2222222222222222222222222222222222222222\": 5000000000000000000",
				"\"0x2222222222222222222222222222222222222222\": 5000000000000000000,\n"
						+ "      \"0x3333333333333333333333333333333333333333\": 7");
		SandboxManifestContext placeholder = loadManifest("placeholder.json", json);
		try (PersistentWorldStateTestSupport storage = storage("verified")) {
			SandboxGenesisPlanFactory factory = factory(storage, mock(BlockStateTransitions.class));
			Hash calculated = factory.calculateGenesisHash(placeholder);
			SandboxManifestContext matching = loadManifest(
					"matching.json", json.replace(EXPECTED_PLACEHOLDER, calculated.toHexString()));

			SandboxGenesisPlan plan = factory.createVerified(matching);

			assertThat(plan.genesisBlock().getHash()).isEqualTo(calculated);
			assertThat(plan.genesisBlock().getHeader().getNonce()).isEqualTo(1_617_611_282_905_388_541L);
			assertThat(plan.worldState().getBalance(
					Address.fromHexString("0x1111111111111111111111111111111111111111"),
					Address.NATIVE_TOKEN).getBalance())
						.isEqualTo(Wei.valueOf(new BigInteger("10000000000000000000")));
			assertThat(plan.worldState().getBalance(
					Address.fromHexString("0x2222222222222222222222222222222222222222"),
					Address.NATIVE_TOKEN).getBalance()).isEqualTo(Wei.valueOf(5_000_000_000_000_000_000L));
			assertThat(plan.worldState().getBalance(
					Address.fromHexString("0x3333333333333333333333333333333333333333"),
					Address.NATIVE_TOKEN).getBalance()).isEqualTo(Wei.valueOf(7));
			assertThat(plan.worldState().getToken(Address.NATIVE_TOKEN).getTotalSupply())
					.isEqualTo(Wei.valueOf(new BigInteger("15000000000000000007")));
		}
	}

	private SandboxGenesisPlanFactory factory(
			PersistentWorldStateTestSupport storage,
			BlockStateTransitions transitions) {
		return new SandboxGenesisPlanFactory(
				new GenesisCandidateFactory(storage.factory()), new SandboxNetworkSettingsAdapter());
	}

	private PersistentWorldStateTestSupport storage(String name) throws Exception {
		return new PersistentWorldStateTestSupport(temporaryDirectory.resolve(name));
	}

	private SandboxManifestContext loadManifest(String name, String json) throws IOException {
		Path path = temporaryDirectory.resolve(name);
		Files.writeString(path, json, StandardCharsets.UTF_8);
		return new SandboxManifestLoader().load(path);
	}

	private String resource() throws IOException {
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream("sandbox/manifest/manifest-v1-valid.json")) {
			assertThat(stream).isNotNull();
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
