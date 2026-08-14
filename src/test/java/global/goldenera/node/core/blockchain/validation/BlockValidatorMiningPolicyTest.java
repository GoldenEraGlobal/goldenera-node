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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.MiningWindowState;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;

class BlockValidatorMiningPolicyTest {

	private static final Address IDENTITY = Address.fromHexString(
			"0x0000000000000000000000000000000000000001");
	private static final Address COINBASE = Address.fromHexString(
			"0x0000000000000000000000000000000000000002");
	private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void contextualValidationUsesParentWindowAndRecoveredIdentityIndependentlyOfCoinbase() {
		Fixture boundary = fixture(39);
		Fixture above = fixture(40);

		assertThatCode(() -> boundary.validator().validateHeaderContext(boundary.child(), boundary.parent(),
				boundary.state())).doesNotThrowAnyException();
		assertThatThrownBy(() -> above.validator().validateHeaderContext(above.child(), above.parent(), above.state()))
				.isInstanceOf(GEValidationException.class)
				.hasMessageContaining("candidate count 41 exceeds maximum 40");
	}

	private Fixture fixture(int existingBlocks) {
		DifficultyCalculator difficulty = mock(DifficultyCalculator.class);
		BlockValidator validator = new BlockValidator(
				mock(RandomXManager.class), difficulty, mock(CheckpointRegistry.class), mock(TxValidator.class),
				new ValidatorMiningPolicyService());
		BlockHeader parent = mock(BlockHeader.class);
		BlockHeader child = mock(BlockHeader.class);
		WorldState state = mock(WorldState.class);
		Hash parentHash = Hash.fromHexString(
				"0x00000000000000000000000000000000000000000000000000000000000000aa");
		NetworkParamsStateImpl params = params();
		ValidatorStateImpl limited = ValidatorStateImpl.builder()
				.version(ValidatorStateVersion.V2)
				.originTxHash(Hash.ZERO)
				.createdAtBlockHeight(0)
				.createdAtTimestamp(TIME)
				.miningLimitMode(MiningLimitMode.LIMITED)
				.maxMiningShareBps(4_000)
				.policyUpdatedByTxHash(Hash.ZERO)
				.policyUpdatedAtBlockHeight(0)
				.policyUpdatedAtTimestamp(TIME)
				.build();
		MiningWindowState window = MiningWindowStateImpl.empty(100, 10);
		for (int i = 0; i < existingBlocks; i++) {
			window = ((MiningWindowStateImpl) window).append(IDENTITY, 11 + i);
		}

		when(parent.getHash()).thenReturn(parentHash);
		when(parent.getHeight()).thenReturn(10L);
		when(parent.getTimestamp()).thenReturn(TIME);
		when(child.getPreviousHash()).thenReturn(parentHash);
		when(child.getHeight()).thenReturn(11L);
		when(child.getTimestamp()).thenReturn(TIME.plusSeconds(1));
		when(child.getDifficulty()).thenReturn(BigInteger.ONE);
		when(child.getIdentity()).thenReturn(IDENTITY);
		when(child.getCoinbase()).thenReturn(COINBASE);
		when(state.getParams()).thenReturn(params);
		when(state.getValidator(IDENTITY)).thenReturn(limited);
		when(state.getMiningWindow()).thenReturn(window);
		when(difficulty.calculateNextDifficulty(parent, params)).thenReturn(BigInteger.ONE);
		return new Fixture(validator, child, parent, state);
	}

	private NetworkParamsStateImpl params() {
		return NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.blockReward(Wei.ZERO)
				.blockRewardPoolAddress(Address.ZERO)
				.targetMiningTimeMs(30_000)
				.asertHalfLifeBlocks(64)
				.asertAnchorHeight(0)
				.minDifficulty(BigInteger.ONE)
				.minTxBaseFee(Wei.ZERO)
				.minTxByteFee(Wei.ZERO)
				.updatedByTxHash(Hash.ZERO)
				.currentAuthorityCount(1)
				.currentValidatorCount(2)
				.currentUnlimitedValidatorCount(1)
				.validatorMiningWindowBlocks(100)
				.updatedAtBlockHeight(10)
				.updatedAtTimestamp(TIME)
				.build();
	}

	private record Fixture(BlockValidator validator, BlockHeader child, BlockHeader parent, WorldState state) {
	}
}
