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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkHasher;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationContext;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationMode;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkVerificationSession;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.sync.BlockOrphanBufferService;
import global.goldenera.node.shared.exceptions.GEValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class BlockValidatorProofOfWorkProviderTest {

	private static final PrivateKey KEY = PrivateKey.wrap(Bytes32.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000001"));
	private static final PrivateKey OTHER_KEY = PrivateKey.wrap(Bytes32.fromHexString(
			"0x0000000000000000000000000000000000000000000000000000000000000002"));

	@Test
	void authenticatesMinerSignatureOnlyFromMiningEconomicsActivation() {
		ProofOfWorkProvider provider = mock(ProofOfWorkProvider.class);
		when(provider.openVerificationHasher(anyLong(), any()))
				.thenAnswer(invocation -> new ProofOfWorkHasher(input -> new byte[32], () -> { }));
		BlockValidator validator = validator(provider);

		assertThatCode(() -> validator.validateHeader(mockHeaderWithForeignIdentity(753_995)))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> validator.validateHeader(mockHeaderWithForeignIdentity(753_996)))
				.isInstanceOf(BlockValidationException.class)
				.hasRootCauseMessage("Miner signature does not authenticate the recovered identity");
	}

	@Test
	@SuppressWarnings("unchecked")
	void batchValidationHashesCanonicalInputAndPassesBatchSeedResolver() {
		ProofOfWorkProvider provider = mock(ProofOfWorkProvider.class);
		ProofOfWorkHasher hasher = mock(ProofOfWorkHasher.class);
		CheckpointRegistry checkpoints = mock(CheckpointRegistry.class);
		when(checkpoints.verifyCheckpoint(anyLong(), any(Hash.class))).thenReturn(true);
		when(provider.openVerificationHasher(anyLong(), any())).thenReturn(hasher);
		when(hasher.hash(any(byte[].class))).thenReturn(new byte[32]);
		BlockValidator validator = new BlockValidator(provider, mock(DifficultyCalculator.class), checkpoints,
				mock(TxValidator.class), new ValidatorMiningPolicyService());
		BlockHeader header = header();
		Hash batchSeed = Hash.fromHexString(
				"0x00000000000000000000000000000000000000000000000000000000000000aa");

		assertThatCode(() -> validator.validateHeader(header, Map.of(0L, batchSeed))).doesNotThrowAnyException();

		ArgumentCaptor<byte[]> input = ArgumentCaptor.forClass(byte[].class);
		verify(hasher).hash(input.capture());
		assertThat(input.getValue()).containsExactly(BlockHeaderUtil.powInput(header));
		ArgumentCaptor<Function<Long, Optional<byte[]>>> resolver = ArgumentCaptor.forClass(Function.class);
		verify(provider).openVerificationHasher(eq(header.getHeight()), resolver.capture());
		assertThat(resolver.getValue().apply(0L)).hasValueSatisfying(
				seed -> assertThat(seed).containsExactly(batchSeed.toArray()));
		assertThat(resolver.getValue().apply(1L)).isEmpty();
		verify(hasher).close();
	}

	@Test
	@SuppressWarnings("unchecked")
	void preparedValidationPreservesCanonicalInputSeedContextAndTargetParity() {
		ProofOfWorkProvider provider = mock(ProofOfWorkProvider.class);
		CheckpointRegistry checkpoints = mock(CheckpointRegistry.class);
		when(checkpoints.verifyCheckpoint(anyLong(), any(Hash.class))).thenReturn(true);
		ProofOfWorkVerificationContext context = new ProofOfWorkVerificationContext(Bytes.of(9));
		when(provider.verificationContext(anyLong(), any())).thenReturn(context);
		BlockValidator validator = new BlockValidator(provider, mock(DifficultyCalculator.class), checkpoints,
				mock(TxValidator.class), new ValidatorMiningPolicyService());
		BlockHeader header = header(BigInteger.TWO);
		Hash batchSeed = Hash.fromHexString(
				"0x00000000000000000000000000000000000000000000000000000000000000bb");
		byte[] targetHash = toHash(DifficultyUtil.calculateTargetFromDifficulty(BigInteger.TWO));

		BlockValidator.PreparedHeaderValidation prepared = validator.prepareHeader(
				header, Map.of(0L, batchSeed));
		try (ProofOfWorkVerificationSession session = new ProofOfWorkVerificationSession(
				context,
				ProofOfWorkVerificationMode.RANDOMX_LIGHT,
				input -> targetHash,
				() -> { })) {
			assertThatCode(() -> validator.validatePreparedHeader(prepared, session)).doesNotThrowAnyException();
		}

		assertThat(prepared.hash()).isEqualTo(header.getHash());
		assertThat(prepared.powInput()).containsExactly(BlockHeaderUtil.powInput(header));
		ArgumentCaptor<Function<Long, Optional<byte[]>>> resolver = ArgumentCaptor.forClass(Function.class);
		verify(provider).verificationContext(eq(header.getHeight()), resolver.capture());
		assertThat(resolver.getValue().apply(0L)).hasValueSatisfying(
				seed -> assertThat(seed).containsExactly(batchSeed.toArray()));
	}

	@Test
	void rejectsMalformedProviderHashThroughSharedBoundary() {
		ProofOfWorkProvider provider = mock(ProofOfWorkProvider.class);
		ProofOfWorkHasher hasher = new ProofOfWorkHasher(input -> new byte[31], () -> { });
		when(provider.openVerificationHasher(anyLong(), any())).thenReturn(hasher);
		BlockValidator validator = validator(provider);

		assertThatThrownBy(() -> validator.validateHeader(header()))
				.isInstanceOf(GEValidationException.class)
				.hasRootCauseMessage("Proof-of-work provider returned 31 bytes; expected exactly 32");
	}

	@Test
	void acceptsHashExactlyEqualToCalculatedTarget() {
		ProofOfWorkProvider provider = mock(ProofOfWorkProvider.class);
		BigInteger difficulty = BigInteger.TWO;
		byte[] targetHash = toHash(DifficultyUtil.calculateTargetFromDifficulty(difficulty));
		ProofOfWorkHasher hasher = new ProofOfWorkHasher(input -> targetHash, () -> { });
		when(provider.openVerificationHasher(anyLong(), any())).thenReturn(hasher);
		BlockValidator validator = validator(provider);

		assertThatCode(() -> validator.validateHeader(header(difficulty))).doesNotThrowAnyException();
	}

	@Test
	void bodyValidationRequiresOpaqueProofForTheExactHeader() {
		ProofOfWorkProvider provider = mock(ProofOfWorkProvider.class);
		when(provider.openVerificationHasher(anyLong(), any()))
				.thenAnswer(invocation -> new ProofOfWorkHasher(input -> new byte[32], () -> { }));
		BlockValidator validator = validator(provider);
		BlockHeader validatedHeaderValue = header();
		StatelessValidatedHeader validatedHeader = validator.validateHeader(validatedHeaderValue);
		List<Tx> sourceTransactions = new ArrayList<>();
		Block block = BlockImpl.builder().header(validatedHeaderValue).txs(sourceTransactions).build();

		StatelessValidatedBlock validatedBlock = validator.validateBlockBody(block, validatedHeader);

		assertThat(validatedBlock.matches(block)).isFalse();
		assertThat(validatedBlock.matches(validatedBlock.block())).isTrue();
		sourceTransactions.add(mock(Tx.class));
		assertThat(validatedBlock.block().getTxs()).isEmpty();
		assertThatThrownBy(() -> validatedBlock.block().getTxs().add(mock(Tx.class)))
				.isInstanceOf(UnsupportedOperationException.class);
		Block blockWithDifferentHeaderInstance = BlockImpl.builder().header(header()).txs(List.of()).build();
		assertThatThrownBy(() -> validator.validateBlockBody(blockWithDifferentHeaderInstance, validatedHeader))
				.isInstanceOf(BlockValidationException.class)
				.extracting(exception -> ((BlockValidationException) exception).getCategory())
				.isEqualTo(BlockValidationException.Category.STATELESS);
	}

	@Test
	void orphanBufferKeepsValidatedSnapshotAfterCallerMutatesSourceBody() {
		ProofOfWorkProvider provider = mock(ProofOfWorkProvider.class);
		when(provider.openVerificationHasher(anyLong(), any()))
				.thenAnswer(invocation -> new ProofOfWorkHasher(input -> new byte[32], () -> { }));
		BlockValidator validator = validator(provider);
		BlockHeader sourceHeader = header();
		List<Tx> sourceTransactions = new ArrayList<>();
		Block source = BlockImpl.builder().header(sourceHeader).txs(sourceTransactions).build();
		StatelessValidatedBlock validated = validator.validateBlockBody(
				source, validator.validateHeader(sourceHeader));
		BlockOrphanBufferService buffer = new BlockOrphanBufferService(
				new SimpleMeterRegistry(), mock(ThreadPoolTaskScheduler.class));

		buffer.addOrphan(validated, Address.ZERO, Instant.parse("2026-01-01T00:00:13Z"));
		sourceTransactions.add(mock(Tx.class));

		assertThat(buffer.getAndRemoveChildren(Hash.ZERO))
				.singleElement()
				.satisfies(orphan -> {
					assertThat(orphan.getBlock()).isSameAs(validated.block());
					assertThat(orphan.getBlock().getTxs()).isEmpty();
				});
	}

	private BlockValidator validator(ProofOfWorkProvider provider) {
		CheckpointRegistry checkpoints = mock(CheckpointRegistry.class);
		when(checkpoints.verifyCheckpoint(anyLong(), any(Hash.class))).thenReturn(true);
		return new BlockValidator(provider, mock(DifficultyCalculator.class), checkpoints,
				mock(TxValidator.class), new ValidatorMiningPolicyService());
	}

	private byte[] toHash(BigInteger value) {
		byte[] hash = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
		byte[] encoded = value.toByteArray();
		int sourceOffset = Math.max(0, encoded.length - hash.length);
		int length = encoded.length - sourceOffset;
		System.arraycopy(encoded, sourceOffset, hash, hash.length - length, length);
		return hash;
	}

	private BlockHeader header() {
		return header(BigInteger.ONE);
	}

	private BlockHeader header(BigInteger difficulty) {
		BlockHeaderImpl unsigned = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(12L)
				.timestamp(Instant.parse("2026-01-01T00:00:12Z"))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(difficulty)
				.coinbase(KEY.getAddress())
				.nonce(7L)
				.build();
		return unsigned.toBuilder().signature(KEY.sign(BlockHeaderUtil.hashForSigning(unsigned))).build();
	}

	private BlockHeader mockHeaderWithForeignIdentity(long height) {
		BlockHeader signed = header();
		BlockHeader result = mock(BlockHeader.class);
		when(result.getVersion()).thenReturn(BlockVersion.V1);
		when(result.getHeight()).thenReturn(height);
		when(result.getTimestamp()).thenReturn(signed.getTimestamp());
		when(result.getPreviousHash()).thenReturn(Hash.ZERO);
		when(result.getTxRootHash()).thenReturn(Hash.ZERO);
		when(result.getStateRootHash()).thenReturn(Hash.ZERO);
		when(result.getDifficulty()).thenReturn(BigInteger.ONE);
		when(result.getCoinbase()).thenReturn(KEY.getAddress());
		when(result.getNonce()).thenReturn(7L);
		when(result.getSignature()).thenReturn(signed.getSignature());
		when(result.getIdentity()).thenReturn(OTHER_KEY.getAddress());
		when(result.getHash()).thenReturn(Hash.ZERO);
		when(result.getSize()).thenReturn(100);
		return result;
	}
}
