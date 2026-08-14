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
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisConfigLoader;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;

class ProductionGenesisCompatibilityTest {

	private static final String MAINNET_RESOURCE = "genesis/genesis-mainnet-prod.json";
	private static final String TESTNET_RESOURCE = "genesis/genesis-testnet-prod.json";

	private static final String MAINNET_RESOURCE_SHA_256 =
			"3b700beab17027de431b6279ee896784bcb89eb0942f83f2a5b5949b3216e261";
	private static final String TESTNET_RESOURCE_SHA_256 =
			"db0b303e2863d0d11c10e9268e151fdc1f88089f308fcde5765aecb76a4fc6ff";

	private static final Hash MAINNET_GENESIS_BLOCK_HASH = Hash.fromHexString(
			"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f");
	private static final Hash TESTNET_GENESIS_BLOCK_HASH = Hash.fromHexString(
			"0xf403f287a52b794eba7645d193c53c2dfa084a52db11ad94d70d0c79107c05cc");

	@TempDir
	Path databaseDirectory;

	@Test
	void productionGenesisResourcesRemainByteIdentical() throws Exception {
		assertThat(resourceSha256(MAINNET_RESOURCE)).isEqualTo(MAINNET_RESOURCE_SHA_256);
		assertThat(resourceSha256(TESTNET_RESOURCE)).isEqualTo(TESTNET_RESOURCE_SHA_256);
	}

	@Test
	void productionGenesisBlockHashesRemainCompatible() throws Exception {
		assertThat(productionGenesisBlockHash(Network.MAINNET))
				.as("mainnet production genesis block hash")
				.isEqualTo(MAINNET_GENESIS_BLOCK_HASH);
		assertThat(productionGenesisBlockHash(Network.TESTNET))
				.as("testnet production genesis block hash")
				.isEqualTo(TESTNET_GENESIS_BLOCK_HASH);
	}

	private String resourceSha256(String resource) throws IOException, NoSuchAlgorithmException {
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertThat(stream).as("classpath resource %s", resource).isNotNull();
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
		}
	}

	private Hash productionGenesisBlockHash(Network network) throws Exception {
		GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(network, "prod");
		NetworkSettings settings = NetworkSettings.fromGenesisSettings(genesis, network, "prod");
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(
				databaseDirectory.resolve(network.name()))) {
			WorldState state = storage.createEmpty(false);
			Instant timestamp = Instant.ofEpochMilli(settings.genesisBlockTimestamp());
			GenesisInitializer.executeGenesisStateExplicitly(
					state, settings.genesisAuthorityAddresses(), timestamp, settings);

			return GenesisInitializer.GenesisBlockHeaderTemplate.builder()
					.version(BlockVersion.V1)
					.height(GenesisInitializer.GENESIS_HEIGHT)
					.timestamp(timestamp)
					.previousHash(Hash.ZERO)
					.difficulty(settings.genesisBlockDifficulty())
					.txRootHash(Hash.ZERO)
					.stateRootHash(state.calculateRootHash())
					.coinbase(Address.ZERO)
					.build()
					.getHash();
		}
	}

}
