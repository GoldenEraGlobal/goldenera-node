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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.monitoring.EquivocationDetectionService;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.randomx.RandomXVM;

class BlockValidatorEquivocationHookTest {

	private static final PrivateKey KEY = PrivateKey.wrap(Bytes32.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000001"));

	@Test
	void statelessHeaderValidationNeverPersistsEvidence() {
		Fixture fixture = fixture(new byte[32], true);

		assertThatCode(() -> fixture.validator.validateHeader(fixture.child)).doesNotThrowAnyException();
		verify(fixture.evidence, never()).enqueueValidatedHeader(any(), any());
	}

	@Test
	void invalidProofOfWorkNeverReachesEvidenceHook() {
		Fixture fixture = fixture(fill((byte) 0xff), true);

		assertThatThrownBy(() -> fixture.validator.validateHeader(fixture.child))
				.isInstanceOf(GEValidationException.class);
		verify(fixture.evidence, never()).enqueueValidatedHeader(any(), any());
	}

	@Test
	void evidenceIsObservedOnlyAfterDifficultyLinkageAndActiveValidatorChecks() {
		Fixture fixture = fixture(new byte[32], true);

		fixture.validator.validateHeader(fixture.child);
		fixture.validator.validateHeaderContext(fixture.child, fixture.parent, fixture.state);

		verify(fixture.evidence).enqueueValidatedHeader(any(BlockHeader.class), any(Instant.class));
	}

	@Test
	void remoteNonValidatorAndWrongDifficultyCannotCreateEvidence() {
		Fixture nonValidator = fixture(new byte[32], false);
		nonValidator.validator.validateHeader(nonValidator.child);
		assertThatThrownBy(() -> nonValidator.validator.validateHeaderContext(
				nonValidator.child, nonValidator.parent, nonValidator.state))
				.isInstanceOf(GEValidationException.class);
		verify(nonValidator.evidence, never()).enqueueValidatedHeader(any(), any());

		Fixture wrongDifficulty = fixture(new byte[32], true);
		BlockHeaderImpl unsigned = ((BlockHeaderImpl) wrongDifficulty.child).toBuilder()
				.difficulty(BigInteger.ONE).signature(null).build();
		BlockHeader difficultyOne = unsigned.toBuilder()
				.signature(KEY.sign(BlockHeaderUtil.hashForSigning(unsigned))).build();
		wrongDifficulty.validator.validateHeader(difficultyOne);
		assertThatThrownBy(() -> wrongDifficulty.validator.validateHeaderContext(
				difficultyOne, wrongDifficulty.parent, wrongDifficulty.state))
				.isInstanceOf(GEValidationException.class);
		verify(wrongDifficulty.evidence, never()).enqueueValidatedHeader(any(), any());
	}

	@Test
	void monitoringFailureDoesNotChangeConsensusValidation() {
		Fixture fixture = fixture(new byte[32], true);
		doThrow(new IllegalStateException("monitoring unavailable"))
				.when(fixture.evidence).enqueueValidatedHeader(any(), any());

		assertThatCode(() -> fixture.validator.validateHeaderContext(
				fixture.child, fixture.parent, fixture.state)).doesNotThrowAnyException();
	}

	private Fixture fixture(byte[] proofOfWorkHash, boolean activeValidator) {
		RandomXManager randomX = mock(RandomXManager.class);
		RandomXVM vm = mock(RandomXVM.class);
		CheckpointRegistry checkpoints = mock(CheckpointRegistry.class);
		EquivocationDetectionService evidence = mock(EquivocationDetectionService.class);
		DifficultyCalculator difficulty = mock(DifficultyCalculator.class);
		when(checkpoints.verifyCheckpoint(anyLong(), any(Hash.class))).thenReturn(true);
		when(randomX.getLightVMForVerification(anyLong(), any())).thenReturn(vm);
		when(vm.calculateHash(any(byte[].class))).thenReturn(proofOfWorkHash);
		BlockHeader parent = header(11, Hash.ZERO, BigInteger.TWO, 6);
		BlockHeader child = header(12, parent.getHash(), BigInteger.TWO, 7);
		when(difficulty.calculateNextDifficulty(any(), any())).thenReturn(BigInteger.TWO);

		WorldState state = mock(WorldState.class);
		when(state.getParams()).thenReturn(NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V2)
				.currentValidatorCount(1)
				.currentUnlimitedValidatorCount(1)
				.limitedValidatorMiningSharesBps(List.of())
				.validatorMiningWindowBlocks(100)
				.targetMiningTimeMs(1_000)
				.build());
		when(state.getMiningWindow()).thenReturn(MiningWindowStateImpl.empty(100, 10));
		when(state.getValidator(KEY.getAddress())).thenReturn(activeValidator
				? ValidatorStateImpl.builder().version(ValidatorStateVersion.V1).build()
				: ValidatorStateImpl.ZERO);

		BlockValidator validator = new BlockValidator(randomX, difficulty, checkpoints,
				mock(TxValidator.class), new ValidatorMiningPolicyService(), evidence);
		return new Fixture(validator, evidence, parent, child, state);
	}

	private BlockHeader header(long height, Hash previousHash, BigInteger difficulty, long nonce) {
		BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(height)
				.timestamp(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(height))
				.previousHash(previousHash)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(difficulty)
				.coinbase(KEY.getAddress())
				.nonce(nonce)
				.build();
		return unsigned.toBuilder().signature(KEY.sign(BlockHeaderUtil.hashForSigning(unsigned))).build();
	}

	private byte[] fill(byte value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, value);
		return bytes;
	}

	private record Fixture(BlockValidator validator, EquivocationDetectionService evidence,
			BlockHeader parent, BlockHeader child, WorldState state) {
	}
}
