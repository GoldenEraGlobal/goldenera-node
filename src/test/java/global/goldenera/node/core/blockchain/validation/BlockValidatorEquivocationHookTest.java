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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.monitoring.EquivocationDetectionService;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.randomx.RandomXVM;

class BlockValidatorEquivocationHookTest {

	private static final PrivateKey KEY = PrivateKey.wrap(Bytes32.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000001"));

	@Test
	void invalidProofOfWorkNeverReachesEvidenceHook() {
		Fixture fixture = fixture(fill((byte) 0xff));

		assertThatThrownBy(() -> fixture.validator.validateHeader(fixture.header))
				.isInstanceOf(GEValidationException.class);
		verify(fixture.evidence, never()).observeValidatedHeader(any(), any());
	}

	@Test
	void authenticatedHeaderIsObservedOnlyAfterSuccessfulProofOfWork() {
		Fixture fixture = fixture(new byte[32]);

		assertThatCode(() -> fixture.validator.validateHeader(fixture.header)).doesNotThrowAnyException();
		verify(fixture.evidence).observeValidatedHeader(any(BlockHeader.class), any(Instant.class));
	}

	private Fixture fixture(byte[] proofOfWorkHash) {
		RandomXManager randomX = mock(RandomXManager.class);
		RandomXVM vm = mock(RandomXVM.class);
		CheckpointRegistry checkpoints = mock(CheckpointRegistry.class);
		EquivocationDetectionService evidence = mock(EquivocationDetectionService.class);
		when(checkpoints.verifyCheckpoint(anyLong(), any(Hash.class))).thenReturn(true);
		when(randomX.getLightVMForVerification(anyLong(), any())).thenReturn(vm);
		when(vm.calculateHash(any(byte[].class))).thenReturn(proofOfWorkHash);
		BlockHeader header = signedHeader();
		BlockValidator validator = new BlockValidator(randomX, mock(DifficultyCalculator.class), checkpoints,
				mock(TxValidator.class), new ValidatorMiningPolicyService(), evidence);
		return new Fixture(validator, evidence, header);
	}

	private BlockHeader signedHeader() {
		BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(12)
				.timestamp(Instant.parse("2026-01-01T00:00:12Z"))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.ONE.shiftLeft(255))
				.coinbase(KEY.getAddress())
				.nonce(7)
				.build();
		return unsigned.toBuilder().signature(KEY.sign(BlockHeaderUtil.hashForSigning(unsigned))).build();
	}

	private byte[] fill(byte value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, value);
		return bytes;
	}

	private record Fixture(BlockValidator validator, EquivocationDetectionService evidence, BlockHeader header) {
	}
}
