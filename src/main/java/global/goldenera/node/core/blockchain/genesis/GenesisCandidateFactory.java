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

import java.time.Instant;
import java.util.Collections;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;

/** Builds a deterministic genesis candidate without persisting it. */
@Component
public final class GenesisCandidateFactory {

	private final WorldStateFactory worldStateFactory;

	public GenesisCandidateFactory(WorldStateFactory worldStateFactory) {
		this.worldStateFactory = worldStateFactory;
	}

	public GenesisCandidate create(NetworkSettings settings, long nonce) {
		Instant timestamp = Instant.ofEpochMilli(settings.genesisBlockTimestamp());
		WorldState worldState = worldStateFactory.createForValidation(MerkleTrie.EMPTY_TRIE_NODE_HASH);
		GenesisInitializer.executeGenesisStateExplicitly(
				worldState, settings.genesisAuthorityAddresses(), timestamp, settings);

		GenesisInitializer.GenesisBlockHeaderTemplate header =
				GenesisInitializer.GenesisBlockHeaderTemplate.builder()
						.version(BlockVersion.V1)
						.height(GenesisInitializer.GENESIS_HEIGHT)
						.timestamp(timestamp)
						.previousHash(Hash.ZERO)
						.difficulty(settings.genesisBlockDifficulty())
						.txRootHash(Hash.ZERO)
						.stateRootHash(worldState.calculateRootHash())
						.coinbase(Address.ZERO)
						.nonce(nonce)
						.build();
		Block block = BlockImpl.builder().header(header).txs(Collections.emptyList()).build();
		return new GenesisCandidate(worldState, block);
	}

	public record GenesisCandidate(WorldState worldState, Block block) {
	}
}
