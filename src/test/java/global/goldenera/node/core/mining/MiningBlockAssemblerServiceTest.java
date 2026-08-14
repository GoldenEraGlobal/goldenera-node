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
package global.goldenera.node.core.mining;

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.BOB;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolTestFixtures;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.shared.properties.GeneralProperties;

class MiningBlockAssemblerServiceTest {

	MempoolManager mempool;
	WorldState worldState;
	MiningBlockAssemblerService assembler;

	@BeforeEach
	void setUp() {
		mempool = mock(MempoolManager.class);
		worldState = mock(WorldState.class);
		AccountNonceState nonce = mock(AccountNonceState.class);
		when(nonce.getNonce()).thenReturn(0L);
		when(worldState.getNonce(ALICE)).thenReturn(nonce);
		when(worldState.getNonce(BOB)).thenReturn(nonce);
		assembler = new MiningBlockAssemblerService(mock(WorldStateFactory.class), mempool,
				MempoolTestFixtures.properties(100), mock(GeneralProperties.class), mock(StateProcessor.class),
				mock(DifficultyCalculator.class), mock(IdentityService.class),
				mock(ValidatorMiningPolicyService.class));
	}

	@Test
	void oversizedTransactionDoesNotStopSelectionOfLaterSmallerTransaction() {
		MempoolEntry oversized = transfer(1, ALICE, 1, 100);
		MempoolEntry small = transfer(2, BOB, 1, 10);
		when(oversized.getTx().getSize()).thenReturn(1000);
		when(small.getTx().getSize()).thenReturn(100);
		when(mempool.getTxIterator()).thenReturn(List.of(oversized, small).iterator());

		assertThat(assembler.getExecutableTransactions(200, worldState)).containsExactly(small.getTx());
	}

	@Test
	void oversizedOccurrenceDoesNotPoisonSeenHashForLaterFittingOccurrence() {
		MempoolEntry oversized = transfer(1, null, 0, 100);
		MempoolEntry fitting = transfer(2, null, 0, 100);
		Tx oversizedTx = oversized.getTx();
		Tx fittingTx = fitting.getTx();
		Hash duplicateHash = oversizedTx.getHash();
		when(fittingTx.getHash()).thenReturn(duplicateHash);
		when(oversizedTx.getSize()).thenReturn(1000);
		when(fittingTx.getSize()).thenReturn(100);
		when(mempool.getTxIterator()).thenReturn(List.of(oversized, fitting).iterator());

		assertThat(assembler.getExecutableTransactions(200, worldState)).containsExactly(fittingTx);
	}

	@Test
	void highFeeChildSeenBeforeParentIsDeferredThenIncludedInNonceOrder() {
		MempoolEntry child = transfer(2, ALICE, 2, 100);
		MempoolEntry parent = transfer(1, ALICE, 1, 10);
		when(mempool.getTxIterator()).thenReturn(List.of(child, parent).iterator());

		assertThat(assembler.getExecutableTransactions(1000, worldState))
				.containsExactly(parent.getTx(), child.getTx());
	}

	@Test
	void exhaustedLimitedMinerReturnsNoTemplateBeforeExecutionOrRandomXJobCanStart() throws Exception {
		WorldStateFactory worldStateFactory = mock(WorldStateFactory.class);
		StateProcessor stateProcessor = mock(StateProcessor.class);
		IdentityService identityService = mock(IdentityService.class);
		ValidatorMiningPolicyService policyService = mock(ValidatorMiningPolicyService.class);
		GeneralProperties generalProperties = mock(GeneralProperties.class);
		MiningBlockAssemblerService localAssembler = new MiningBlockAssemblerService(
				worldStateFactory, mempool, MempoolTestFixtures.properties(100), generalProperties,
				stateProcessor, mock(DifficultyCalculator.class), identityService, policyService);
		Block parent = mock(Block.class);
		BlockHeader parentHeader = mock(BlockHeader.class);
		NetworkParamsState params = mock(NetworkParamsState.class);
		ValidatorState validator = mock(ValidatorState.class);
		Address identity = Address.fromHexString("0x0000000000000000000000000000000000000001");
		when(parent.getHeight()).thenReturn(10L);
		when(parent.getHeader()).thenReturn(parentHeader);
		when(parentHeader.getStateRootHash()).thenReturn(Hash.ZERO);
		when(parentHeader.getTimestamp()).thenReturn(Instant.EPOCH);
		when(parentHeader.getDifficulty()).thenReturn(BigInteger.ONE);
		when(worldStateFactory.createForMining(Hash.ZERO)).thenReturn(worldState);
		when(worldState.getParams()).thenReturn(params);
		when(params.getCurrentValidatorCount()).thenReturn(1L);
		when(worldState.getValidator(identity)).thenReturn(validator);
		when(validator.exists()).thenReturn(true);
		when(identityService.getNodeIdentityAddress()).thenReturn(identity);
		when(generalProperties.getBeneficiaryAddress()).thenReturn(Address.ZERO);
		when(policyService.isCandidateEligible(worldState, 11, identity)).thenReturn(false);

		Optional<MiningBlockAssemblerService.AssembledBlock> result = localAssembler.createBlockTemplate(parent);

		assertThat(result).isEmpty();
		verify(mempool, never()).getTxIterator();
		verify(stateProcessor, never()).executeMiningBatch(
				any(), any(), anyList(), any());
	}
}
