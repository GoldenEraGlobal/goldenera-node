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
package global.goldenera.node.core.sandbox.genesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.MapEntry.entry;

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
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;

class SandboxNetworkSettingsAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void mapsEveryManifestSettingsGroupWithoutClasspathFallback() throws Exception {
		SandboxManifestContext context = loadManifest(resource());
		SandboxGenesisConfiguration configuration = new SandboxNetworkSettingsAdapter().adapt(context);
		GenesisSettings genesis = configuration.genesisSettings();
		NetworkSettings network = configuration.networkSettings();

		assertThat(configuration.chainId()).isEqualTo("sandbox-00112233445566778899aabbccddeeff");
		assertThat(configuration.manifestFingerprint()).isEqualTo(context.fingerprint());
		assertThat(configuration.genesisSeed())
				.isEqualTo("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
		assertThat(configuration.legacyCarrier()).isEqualTo(Network.TESTNET);
		assertThat(configuration.initialBalances()).containsExactly(
				entry(
						Address.fromHexString("0x1111111111111111111111111111111111111111"),
						Wei.valueOf(new BigInteger("10000000000000000000"))),
				entry(
						Address.fromHexString("0x2222222222222222222222222222222222222222"),
						Wei.valueOf(5_000_000_000_000_000_000L)));

		assertThat(genesis.maxHeaderSizeInBytes()).isEqualTo(500);
		assertThat(genesis.maxTxSizeInBytes()).isEqualTo(100_000);
		assertThat(genesis.maxBlockSizeInBytes()).isEqualTo(5_000_000);
		assertThat(genesis.maxTxCountPerBlock()).isEqualTo(1_000);
		assertThat(genesis.bipExpirationPeriodMs()).isEqualTo(86_400_000);
		assertThat(genesis.bipApprovalThresholdBps()).isEqualTo(5_100);
		assertThat(genesis.genesisNetworkBlockReward()).isEqualTo(Wei.valueOf(10_000_000));
		assertThat(genesis.genesisNetworkTargetMiningTimeMs()).isEqualTo(1_000);
		assertThat(genesis.genesisNetworkAsertHalfLifeBlocks()).isEqualTo(64);
		assertThat(genesis.genesisNetworkValidatorMiningWindowBlocks()).isEqualTo(100);
		assertThat(genesis.genesisBlockTimestamp()).isEqualTo(1_800_000_000_000L);
		assertThat(genesis.genesisNativeTokenName()).isEqualTo("Sandbox GoldenEra");
		assertThat(genesis.genesisNativeTokenTicker()).isEqualTo("SGE");
		assertThat(genesis.genesisNativeTokenDecimals()).isEqualTo(8);
		assertThat(genesis.randomXEpochLength()).isEqualTo(32);
		assertThat(genesis.randomXGenesisKey()).isEqualTo("SANDBOX_RANDOMX_GENESIS_V1");
		assertThat(genesis.randomXBatchSize()).isEqualTo(64);
		assertThat(network.forkActivationBlocks()).containsExactly(
				entry(ForkName.GENESIS, 0L),
				entry(ForkName.MINING_ECONOMICS, 0L));
		assertThat(network.blockCheckpoints()).isEmpty();
		assertThat(network.maxBlockSizeOverrides()).isEmpty();
	}

	@Test
	void rejectsManifestThatDoesNotAllocateItsDeclaredAuthorityMint() throws Exception {
		SandboxManifestContext context = loadManifest(resource().replace(
				"\"0x1111111111111111111111111111111111111111\": 10000000000000000000",
				"\"0x1111111111111111111111111111111111111111\": 999"));

		assertThatThrownBy(() -> new SandboxNetworkSettingsAdapter().adapt(context))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("first authority");
	}

	private SandboxManifestContext loadManifest(String json) throws IOException {
		Path path = temporaryDirectory.resolve("manifest.json");
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
