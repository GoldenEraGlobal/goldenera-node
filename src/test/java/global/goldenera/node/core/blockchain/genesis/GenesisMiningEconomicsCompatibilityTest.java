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

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.GenesisConfigLoader;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;

class GenesisMiningEconomicsCompatibilityTest {

	@TempDir
	Path databaseDirectory;

	@Test
	void productionGenesisStateRemainsLegacyAndMatchesFrozenRoots() throws Exception {
		assertProductionGenesis(Network.MAINNET,
				Hash.fromHexString("0x1d23acb535dfc90ab50047ce8997b0b239e1ab4884e80d850330b0fb79e3f151"));
		assertProductionGenesis(Network.TESTNET,
				Hash.fromHexString("0xf02c74185fd42d383b255d7984231e5790bc8c24226d46af6d50bf341d5d0575"));
	}

	@Test
	void forkAtGenesisCreatesV2ParamsAndEmptyWindowWithoutInventingValidators() throws Exception {
		GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(Network.TESTNET, "prod");
		NetworkSettings settings = NetworkSettings.fromGenesisSettings(genesis, Network.TESTNET, "dev");
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(
				databaseDirectory.resolve("dev"))) {
			WorldState state = storage.createEmpty(false);
			initializer(storage).executeGenesisStateExplicitly(state, settings.genesisAuthorityAddresses(),
					Instant.ofEpochMilli(settings.genesisBlockTimestamp()), settings);

			assertThat(state.getParams().getVersion()).isEqualTo(NetworkParamsStateVersion.V2);
			assertThat(state.getParams().getCurrentUnlimitedValidatorCount())
					.isEqualTo(settings.genesisValidatorAddresses().size());
			assertThat(state.getMiningWindow().getOrderedValidatorIdentities()).isEmpty();
			assertThat(state.getMiningWindow().getWindowSize())
					.isEqualTo(settings.genesisNetworkValidatorMiningWindowBlocks());
			settings.genesisValidatorAddresses().forEach(address -> assertThat(state.getValidator(address).getVersion())
					.isEqualTo(ValidatorStateVersion.V2));
		}
	}

	private void assertProductionGenesis(Network network, Hash expectedRoot) throws Exception {
		GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(network, "prod");
		NetworkSettings settings = NetworkSettings.fromGenesisSettings(genesis, network, "prod");
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(
				databaseDirectory.resolve(network.name()))) {
			WorldState state = storage.createEmpty(false);
			initializer(storage).executeGenesisStateExplicitly(state, settings.genesisAuthorityAddresses(),
					Instant.ofEpochMilli(settings.genesisBlockTimestamp()), settings);

			assertThat(state.getParams().getVersion()).isEqualTo(NetworkParamsStateVersion.V1);
			assertThat(state.getMiningWindow()).isEqualTo(MiningWindowStateImpl.ZERO);
			settings.genesisValidatorAddresses().forEach(address -> assertThat(state.getValidator(address).getVersion())
					.isEqualTo(ValidatorStateVersion.V1));
			assertThat(state.calculateRootHash()).isEqualTo(expectedRoot);
		}
	}

	private GenesisInitializer initializer(PersistentWorldStateTestSupport storage) {
		return new GenesisInitializer(mock(ChainQuery.class), mock(BlockStateTransitions.class), storage.factory());
	}
}
