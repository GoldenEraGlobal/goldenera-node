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
package global.goldenera.node.core.storage.chainidentity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.NetworkSettingsProvider;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory.GenesisCandidate;
import global.goldenera.node.core.blockchain.genesis.SandboxGenesisPlanFactory;
import global.goldenera.node.core.blockchain.reorg.ChainSwitchService;
import global.goldenera.node.core.blockchain.state.BlockEventExtractor;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.EntityIndexRepository;

class VerifiedGenesisPlanTest {

	@Test
	void rejectsWorldStateMutationImmediatelyBeforePersistence() {
		Hash expectedRoot = hash(1);
		Hash changedRoot = hash(2);
		Block block = genesisBlock(expectedRoot);
		WorldState worldState = mock(WorldState.class);
		when(worldState.calculateRootHash()).thenReturn(expectedRoot, changedRoot);
		VerifiedGenesisPlan plan = new VerifiedGenesisPlan(
				block, worldState, Address.ZERO, block.getHash().toHexString());
		BlockRepository repository = mock(BlockRepository.class);
		BlockStateTransitions transitions = new BlockStateTransitions(
				repository,
				mock(ChainQuery.class),
				mock(ChainSwitchService.class),
				mock(ApplicationEventPublisher.class),
				new ReentrantLock(),
				mock(EntityIndexRepository.class),
				mock(BlockEventExtractor.class));

		assertThatThrownBy(() -> transitions.connectVerifiedGenesis(plan))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("changed before persistence");
		verifyNoInteractions(repository);
	}

	@Test
	void verifiedGenesisAuthorizationCanOnlyBeClaimedOnce() {
		Hash root = hash(1);
		Block block = genesisBlock(root);
		WorldState worldState = mock(WorldState.class);
		when(worldState.calculateRootHash()).thenReturn(root);
		VerifiedGenesisPlan plan = new VerifiedGenesisPlan(
				block, worldState, Address.ZERO, block.getHash().toHexString());

		plan.claimForPersistence();

		assertThatThrownBy(plan::claimForPersistence)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already consumed");
	}

	@Test
	void verifierDoesNotReleaseGenesisPlanBeforeBindingAuthorization() {
		Hash root = hash(1);
		Block block = genesisBlock(root);
		WorldState worldState = mock(WorldState.class);
		NetworkSettings settings = mock(NetworkSettings.class);
		NetworkSettingsProvider settingsProvider = mock(NetworkSettingsProvider.class);
		when(settingsProvider.currentSettings()).thenReturn(settings);
		ExpectedChainIdentityProvider expectedProvider = mock(ExpectedChainIdentityProvider.class);
		ChainIdentityExpectation expectation = new ChainIdentityExpectation(
				new StoredChainIdentity(
						StoredChainIdentity.CURRENT_FORMAT_VERSION,
						1,
						"development-testnet",
						block.getHash().toHexString(),
						null),
				ChainIdentityExecutionScope.DEVELOPMENT);
		when(expectedProvider.expectedIdentity()).thenReturn(expectation);
		GenesisCandidateFactory candidateFactory = mock(GenesisCandidateFactory.class);
		when(candidateFactory.create(settings, 0L)).thenReturn(new GenesisCandidate(worldState, block));
		ChainIdentityGenesisVerifier verifier = new ChainIdentityGenesisVerifier(
				mock(SandboxRuntimeContext.class),
				settingsProvider,
				expectedProvider,
				mock(SandboxGenesisPlanFactory.class),
				candidateFactory);
		verifier.afterPropertiesSet();

		assertThatThrownBy(verifier::verifiedGenesisPlan)
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("before identity binding authorization");
	}

	private Block genesisBlock(Hash stateRoot) {
		return BlockImpl.builder()
				.header(BlockHeaderImpl.builder()
						.version(BlockVersion.V1)
						.height(0L)
						.timestamp(Instant.parse("2026-01-01T00:00:00Z"))
						.previousHash(Hash.ZERO)
						.txRootHash(Hash.ZERO)
						.stateRootHash(stateRoot)
						.difficulty(BigInteger.ONE)
						.coinbase(Address.ZERO)
						.nonce(0L)
						.signature(Signature.ZERO)
						.build())
				.txs(List.of())
				.build();
	}

	private Hash hash(int suffix) {
		return Hash.fromHexString("0x" + "00".repeat(31) + String.format("%02x", suffix));
	}
}
