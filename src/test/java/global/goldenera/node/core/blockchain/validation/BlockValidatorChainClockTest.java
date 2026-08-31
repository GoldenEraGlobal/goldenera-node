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
package global.goldenera.node.core.blockchain.validation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;

class BlockValidatorChainClockTest {

	@Test
	void contextualValidationDelegatesConsensusTimestampPolicyToChainClock() {
		ChainClock chainClock = mock(ChainClock.class);
		BlockValidator validator = new BlockValidator(
				mock(ProofOfWorkProvider.class), mock(DifficultyCalculator.class), mock(CheckpointRegistry.class),
				mock(TxValidator.class), mock(ValidatorMiningPolicyService.class), null, chainClock);
		BlockHeader parent = mock(BlockHeader.class);
		BlockHeader child = mock(BlockHeader.class);
		WorldState worldState = mock(WorldState.class);
		NetworkParamsState params = mock(NetworkParamsState.class);
		Hash parentHash = Hash.fromHexString(
				"0x0000000000000000000000000000000000000000000000000000000000000001");

		when(parent.getHash()).thenReturn(parentHash);
		when(parent.getHeight()).thenReturn(10L);
		when(parent.getTimestamp()).thenReturn(Instant.EPOCH);
		when(child.getPreviousHash()).thenReturn(parentHash);
		when(child.getHeight()).thenReturn(11L);
		when(child.getTimestamp()).thenReturn(Instant.EPOCH.plusSeconds(1));
		when(worldState.getParams()).thenReturn(params);
		when(params.getTargetMiningTimeMs()).thenReturn(1_000L);
		doThrow(new IllegalArgumentException("controlled future timestamp rejection"))
				.when(chainClock).validateBlockTimestamp(same(child), same(parent), anyLong());

		assertThatThrownBy(() -> validator.validateHeaderContext(child, parent, worldState))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("controlled future timestamp rejection");
		verify(chainClock).validateBlockTimestamp(child, parent, 15_000L);
	}
}
