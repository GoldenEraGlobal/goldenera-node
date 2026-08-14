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
package global.goldenera.node.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.reorg.BlockReorgs;
import global.goldenera.node.core.blockchain.reorg.ChainSwitchService;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.BlockValidationException;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.StatelessValidatedBlock;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BlockIngestionServiceValidationGateTest {

	private static final Instant RECEIVED_AT = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void statelessRejectionHappensBeforeParentLookupOrOrphanBuffering() {
		Fixture fixture = fixture();
		doThrow(validationFailure(BlockValidationException.Category.STATELESS))
				.when(fixture.validator).validateFullBlock(fixture.block);

		BlockIngestionOutcome outcome = fixture.service.processBlock(
				fixture.block, ConnectedSource.BROADCAST, Address.ZERO, RECEIVED_AT);

		assertThat(outcome.code()).isEqualTo(BlockIngestionOutcome.Code.REJECTED_STATELESS);
		verify(fixture.chainQuery, never()).getStoredBlockByHash(fixture.parentHash);
		verify(fixture.orphanBuffer, never()).addOrphan(any(), any(), any());
	}

	@Test
	void onlyStatelesslyValidBlockCanEnterOrphanBuffer() {
		Fixture fixture = fixture();
		when(fixture.chainQuery.getStoredBlockByHash(fixture.parentHash)).thenReturn(Optional.empty());
		StoredBlock localBest = mock(StoredBlock.class);
		when(localBest.getHeight()).thenReturn(10L);
		when(fixture.chainQuery.getLatestStoredBlockOrThrow()).thenReturn(localBest);
		when(fixture.block.getHeight()).thenReturn(10L);

		BlockIngestionOutcome outcome = fixture.service.processBlock(
				fixture.block, ConnectedSource.BROADCAST, Address.ZERO, RECEIVED_AT);

		assertThat(outcome.code()).isEqualTo(BlockIngestionOutcome.Code.ORPHAN_BUFFERED);
		verify(fixture.validator).validateFullBlock(fixture.block);
		verify(fixture.orphanBuffer).addOrphan(fixture.validatedBlock, Address.ZERO, RECEIVED_AT);
	}

	@Test
	void consensusPolicyFailureHasStableOutcomeAndDoesNotExecuteOrPersist() {
		Fixture fixture = fixture();
		Block parentBlock = mock(Block.class);
		BlockHeader parentHeader = mock(BlockHeader.class);
		when(parentBlock.getHeader()).thenReturn(parentHeader);
		StoredBlock parent = mock(StoredBlock.class);
		when(parent.getBlock()).thenReturn(parentBlock);
		when(fixture.chainQuery.getStoredBlockByHash(fixture.parentHash)).thenReturn(Optional.of(parent));
		WorldState worldState = mock(WorldState.class);
		when(worldState.getParams()).thenReturn(mock(NetworkParamsState.class));
		when(fixture.worldStateFactory.createForValidation(any())).thenReturn(worldState);
		doThrow(validationFailure(BlockValidationException.Category.CONSENSUS_POLICY))
				.when(fixture.validator).validateHeaderContext(fixture.header, parentHeader, worldState);

		BlockIngestionOutcome outcome = fixture.service.processBlock(
				fixture.block, ConnectedSource.BROADCAST, Address.ZERO, RECEIVED_AT);

		assertThat(outcome.code()).isEqualTo(BlockIngestionOutcome.Code.REJECTED_CONSENSUS_POLICY);
		verify(fixture.stateProcessor, never()).executeTransactions(any(), any(), any(), any());
		verify(fixture.transitions, never()).connectValidatedBlock(any(), any(), any(), any(), any(), any());
	}

	@Test
	void processBlockHasNoBooleanValidationBypassOverload() {
		assertThatNoException().isThrownBy(() -> {
			Method[] ingestionMethods = BlockIngestionService.class.getDeclaredMethods();
			assertThat(Arrays.stream(ingestionMethods)
					.filter(method -> method.getName().equals("processBlock"))
					.map(Method::getParameterTypes))
					.allSatisfy(types -> assertThat(types).doesNotContain(boolean.class));
			Method[] validatorMethods = BlockValidator.class.getDeclaredMethods();
			assertThat(Arrays.stream(validatorMethods)
					.filter(method -> method.getName().equals("validateFullBlock"))
					.map(Method::getParameterTypes))
					.allSatisfy(types -> assertThat(types).doesNotContain(boolean.class));
		});
	}

	@Test
	void rawPersistenceAndReorgMethodsAreNotPublicSeams() {
		assertThat(Arrays.stream(BlockReorgs.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.flatMap(method -> Arrays.stream(method.getParameterTypes())))
				.doesNotContain(List.class);
		assertThat(Arrays.stream(ChainSwitchService.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("executeAtomicReorgSwap"))
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.flatMap(method -> Arrays.stream(method.getParameterTypes())))
				.doesNotContain(List.class);
		assertThat(Arrays.stream(BlockStateTransitions.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("connectBlock")))
				.allSatisfy(method -> assertThat(Modifier.isPrivate(method.getModifiers())).isTrue());
		assertThat(Arrays.stream(BlockStateTransitions.class.getDeclaredMethods())
				.filter(method -> method.getName().toLowerCase().contains("genesis"))
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.flatMap(method -> Arrays.stream(method.getParameterTypes())))
				.doesNotContain(Block.class, WorldState.class);
	}

	@Test
	void syncPersistenceMarkerRejectsMismatchedValidationProof() {
		StoredBlock storedBlock = mock(StoredBlock.class);
		Block block = mock(Block.class);
		when(storedBlock.getBlock()).thenReturn(block);
		StatelessValidatedBlock validation = mock(StatelessValidatedBlock.class);
		when(validation.matches(block)).thenReturn(false);

		assertThatThrownBy(() -> new ValidatedSyncBlock(storedBlock, validation))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("does not match");
	}

	@Test
	void acceptedDoesNotCollapseAlreadyExistingIntoNewAcceptance() {
		assertThat(BlockIngestionOutcome.of(BlockIngestionOutcome.Code.ACCEPTED).accepted()).isTrue();
		assertThat(BlockIngestionOutcome.of(BlockIngestionOutcome.Code.ALREADY_EXISTS).accepted()).isFalse();
	}

	private Fixture fixture() {
		Hash blockHash = Hash.fromHexString(
				"0x0000000000000000000000000000000000000000000000000000000000000001");
		Hash parentHash = Hash.fromHexString(
				"0x0000000000000000000000000000000000000000000000000000000000000002");
		Block block = mock(Block.class);
		BlockHeader header = mock(BlockHeader.class);
		when(block.getHash()).thenReturn(blockHash);
		when(block.getHeader()).thenReturn(header);
		when(header.getPreviousHash()).thenReturn(parentHash);

		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getStoredBlockByHash(blockHash)).thenReturn(Optional.empty());
		BlockValidator validator = mock(BlockValidator.class);
		StatelessValidatedBlock validatedBlock = mock(StatelessValidatedBlock.class);
		when(validatedBlock.block()).thenReturn(block);
		when(validator.validateFullBlock(block)).thenReturn(validatedBlock);
		StateProcessor stateProcessor = mock(StateProcessor.class);
		WorldStateFactory worldStateFactory = mock(WorldStateFactory.class);
		BlockStateTransitions transitions = mock(BlockStateTransitions.class);
		BlockOrphanBufferService orphanBuffer = mock(BlockOrphanBufferService.class);
		BlockIngestionService service = new BlockIngestionService(
				new ReentrantLock(), new SimpleMeterRegistry(), chainQuery, validator,
				stateProcessor, worldStateFactory, transitions, orphanBuffer);
		return new Fixture(service, block, header, validatedBlock, blockHash, parentHash, chainQuery,
				validator, stateProcessor, worldStateFactory, transitions, orphanBuffer);
	}

	private BlockValidationException validationFailure(BlockValidationException.Category category) {
		return new BlockValidationException(category, "invalid", new IllegalArgumentException("invalid"));
	}

	private record Fixture(
			BlockIngestionService service,
			Block block,
			BlockHeader header,
			StatelessValidatedBlock validatedBlock,
			Hash blockHash,
			Hash parentHash,
			ChainQuery chainQuery,
			BlockValidator validator,
			StateProcessor stateProcessor,
			WorldStateFactory worldStateFactory,
			BlockStateTransitions transitions,
			BlockOrphanBufferService orphanBuffer) {
	}
}
