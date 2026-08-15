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
package global.goldenera.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import global.goldenera.cryptoj.enums.Network;

class GenesisConfigLoaderTest {

	@Test
	void exactLegacyProductionInputReceivesNetworkSpecificPostForkWindowDefault() throws Exception {
		JsonNode mainnet = productionGenesis("genesis/genesis-mainnet-prod.json");
		JsonNode testnet = productionGenesis("genesis/genesis-testnet-prod.json");
		((ObjectNode) mainnet.get("networkParams")).remove("validatorMiningWindowBlocks");
		((ObjectNode) testnet.get("networkParams")).remove("validatorMiningWindowBlocks");

		assertThat(GenesisConfigLoader.parseProductionGenesisSettings(mainnet, Network.MAINNET)
				.genesisNetworkValidatorMiningWindowBlocks()).isEqualTo(1_000);
		assertThat(GenesisConfigLoader.parseProductionGenesisSettings(testnet, Network.TESTNET)
				.genesisNetworkValidatorMiningWindowBlocks()).isEqualTo(100);
	}

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void productionResourcesContainConsensusMiningWindow() {
		assertThat(GenesisConfigLoader.loadGenesisSettings(Network.MAINNET, "prod")
				.genesisNetworkValidatorMiningWindowBlocks()).isEqualTo(1000);
		assertThat(GenesisConfigLoader.loadGenesisSettings(Network.TESTNET, "prod")
				.genesisNetworkValidatorMiningWindowBlocks()).isEqualTo(100);
	}

	@Test
	void miningWindowIsRequired() throws Exception {
		JsonNode root = productionMainnet();
		((ObjectNode) root.get("networkParams"))
				.remove("validatorMiningWindowBlocks");

		assertThatThrownBy(() -> GenesisConfigLoader.parseGenesisSettings(root))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("validatorMiningWindowBlocks");
	}

	@Test
	void miningWindowMustBeWithinConsensusRange() throws Exception {
		JsonNode belowMinimum = productionMainnet();
		((ObjectNode) belowMinimum.get("networkParams"))
				.put("validatorMiningWindowBlocks", 99);
		JsonNode aboveMaximum = productionMainnet();
		((ObjectNode) aboveMaximum.get("networkParams"))
				.put("validatorMiningWindowBlocks", 10_001);

		assertThatThrownBy(() -> GenesisConfigLoader.parseGenesisSettings(belowMinimum))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Invalid networkParams.validatorMiningWindowBlocks");
		assertThatThrownBy(() -> GenesisConfigLoader.parseGenesisSettings(aboveMaximum))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Invalid networkParams.validatorMiningWindowBlocks");
	}

	@Test
	void miningWindowRejectsNonIntegralConsensusValues() throws Exception {
		JsonNode root = productionMainnet();
		((ObjectNode) root.get("networkParams")).put("validatorMiningWindowBlocks", 100.5);

		assertThatThrownBy(() -> GenesisConfigLoader.parseGenesisSettings(root))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("consensus long field");
	}

	private JsonNode productionMainnet() throws Exception {
		return productionGenesis("genesis/genesis-mainnet-prod.json");
	}

	private JsonNode productionGenesis(String resource) throws Exception {
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream(resource)) {
			return OBJECT_MAPPER.readTree(stream);
		}
	}
}
