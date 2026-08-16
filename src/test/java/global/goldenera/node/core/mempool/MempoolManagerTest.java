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
package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolManager.MempoolAddResult;
import global.goldenera.node.core.mempool.MempoolManager.MempoolReasonCode;
import global.goldenera.node.core.mempool.MempoolValidator.MempoolValidationResult;
import global.goldenera.node.core.mempool.MempoolValidator.ValidationStatus;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolManagerTest {

	MempoolStore store;
	MempoolValidator validator;
	MempoolManager manager;
	ChainHeadStateCache chainHead;

	@BeforeEach
	void setUp() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MempoolProperties properties = MempoolTestFixtures.properties(100);
		chainHead = mock(ChainHeadStateCache.class);
		WorldState worldState = mock(WorldState.class);
		AccountBalanceState confirmedBalance = mock(AccountBalanceState.class);
		when(confirmedBalance.getBalance()).thenReturn(Wei.valueOf(Long.MAX_VALUE));
		when(worldState.getBalance(any(Address.class), any(Address.class))).thenReturn(confirmedBalance);
		when(chainHead.getHeadState()).thenReturn(worldState);
		store = new MempoolStore(registry, properties, chainHead,
				mock(ApplicationEventPublisher.class));
		validator = mock(MempoolValidator.class);
		manager = new MempoolManager(registry, store, validator, properties, chainHead,
				Runnable::run,
				mock(ThreadPoolTaskScheduler.class));
	}

	@Test
	void validGovernanceLikeTransactionSurvivesPeriodicRevalidation() {
		MempoolEntry vote = MempoolTestFixtures.vote(1, ALICE, 1, 10, MempoolTestFixtures.hash(50));
		store.addTransaction(vote, 0, MempoolTxAddEvent.AddReason.NEW);
		when(validator.revalidateAgainstChain(vote)).thenReturn(MempoolValidationResult.valid(0));

		manager.revalidateMempool();

		assertThat(store.getTxByHash(vote.getHash())).contains(vote);
	}

	@Test
	void transientValidationFailureKeepsTransaction() {
		MempoolEntry entry = transfer(1, ALICE, 1, 10);
		store.addTransaction(entry, 0, MempoolTxAddEvent.AddReason.NEW);
		when(validator.revalidateAgainstChain(entry)).thenReturn(MempoolValidationResult.transientError("db down"));

		manager.revalidateMempool();

		assertThat(store.getTxByHash(entry.getHash())).contains(entry);
		verify(validator).revalidateAgainstChain(entry);
	}

	@Test
	void permanentInvalidAndStaleTransactionsAreRemoved() {
		MempoolEntry invalid = transfer(1, ALICE, 1, 10);
		MempoolEntry stale = transfer(2, ALICE, 2, 20);
		store.addTransactions(List.of(invalid, stale), Map.of(ALICE, 0L),
				MempoolTxAddEvent.AddReason.SYNC);
		when(validator.revalidateAgainstChain(invalid)).thenReturn(MempoolValidationResult.stateInvalid("spent"));
		when(validator.revalidateAgainstChain(stale)).thenReturn(MempoolValidationResult.stale(2, "mined"));

		manager.revalidateMempool();

		assertThat(store.getCount()).isZero();
	}

	@Test
	void revalidationResyncPromotesFutureAfterMissedBlockEvent() {
		MempoolEntry future = transfer(2, ALICE, 2, 20);
		store.addTransaction(future, 0, MempoolTxAddEvent.AddReason.NEW);
		assertThat(store.getExecutableTransactionsIterator()).isExhausted();
		when(validator.revalidateAgainstChain(future)).thenReturn(MempoolValidationResult.valid(1));

		manager.revalidateMempool();

		List<MempoolEntry> executable = new ArrayList<>();
		store.getExecutableTransactionsIterator().forEachRemaining(executable::add);
		assertThat(executable).containsExactly(future);
	}

	@Test
	void unexpectedValidatorExceptionDoesNotEvictTransaction() {
		MempoolEntry entry = transfer(1, ALICE, 1, 10);
		store.addTransaction(entry, 0, MempoolTxAddEvent.AddReason.NEW);
		when(validator.revalidateAgainstChain(entry)).thenThrow(new IllegalStateException("temporary"));

		manager.revalidateMempool();

		assertThat(store.getTxByHash(entry.getHash())).contains(entry);
	}

	@Test
	void disconnectedBlockHandlerDelegatesThroughConfiguredExecutor() {
		MempoolStore mockedStore = mock(MempoolStore.class);
		MempoolManager eventManager = new MempoolManager(new SimpleMeterRegistry(), mockedStore, validator,
				MempoolTestFixtures.properties(100), mock(ChainHeadStateCache.class),
				Runnable::run,
				mock(ThreadPoolTaskScheduler.class));
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(12L);
		List<Tx> disconnectedTxs = List.of(transfer(9, ALICE, 1, 10).getTx());
		when(block.getTxs()).thenReturn(disconnectedTxs);

		eventManager.onBlockDisconnected(new BlockDisconnectedEvent(this, block));

		verify(mockedStore).addTransactionsBack(block.getTxs(), block);
	}

	@Test
	void connectedBlockRetriesTransientMempoolFailureAndRecordsMetrics() {
		SimpleMeterRegistry eventRegistry = new SimpleMeterRegistry();
		MempoolStore mockedStore = mock(MempoolStore.class);
		MempoolManager eventManager = new MempoolManager(eventRegistry, mockedStore, validator,
				MempoolTestFixtures.properties(100), mock(ChainHeadStateCache.class),
				Runnable::run, mock(ThreadPoolTaskScheduler.class));
		Block block = mock(Block.class);
		List<Tx> blockTxs = List.of();
		when(block.getHeight()).thenReturn(12L);
		when(block.getTxs()).thenReturn(blockTxs);
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		when(event.getBlock()).thenReturn(block);
		doThrow(new IllegalStateException("rocks temporarily unavailable"))
				.doThrow(new IllegalStateException("rocks temporarily unavailable"))
				.doNothing()
				.when(mockedStore).processNewBlock(blockTxs);

		eventManager.onBlockConnected(event);

		verify(mockedStore, times(3)).processNewBlock(blockTxs);
		assertThat(eventRegistry.counter("blockchain.mempool.event.retries_total", "type", "connect").count())
				.isEqualTo(2);
		assertThat(eventRegistry.counter("blockchain.mempool.event.processed_total", "type", "connect").count())
				.isEqualTo(1);
		assertThat(eventRegistry.timer("blockchain.mempool.event.processing_time", "type", "connect").count())
				.isEqualTo(1);
	}

	@Test
	void atomicInsufficientFundsStorageResultMapsToRejectedState() {
		MempoolEntry existing = nativeTransfer(20, ALICE, 1, 60);
		store.addTransaction(existing, 0, MempoolTxAddEvent.AddReason.NEW);
		MempoolEntry candidate = nativeTransfer(21, ALICE, 2, 60);
		MempoolStore.AdmissionConstraints constraints = new MempoolStore.AdmissionConstraints(
				Wei.valueOf(100), Map.of(), null);
		when(validator.validateAgainstChainAndMempool(any(MempoolEntry.class),
				eq(MempoolTxAddEvent.AddReason.NEW), eq(false)))
				.thenReturn(MempoolValidationResult.valid(0, constraints));

		assertThat(manager.addTx(candidate.getTx()).status()).isEqualTo(MempoolAddResult.REJECTED_STATE);
	}

	@Test
	void atomicTokenSupplyStorageResultMapsToRejectedState() {
		Address token = MempoolTestFixtures.address(80);
		MempoolEntry existing = tokenMint(30, ALICE, 1, token, 60);
		store.addTransaction(existing, 0, MempoolTxAddEvent.AddReason.NEW);
		MempoolEntry candidate = tokenMint(31, MempoolTestFixtures.BOB, 1, token, 60);
		MempoolStore.AdmissionConstraints constraints = new MempoolStore.AdmissionConstraints(
				Wei.valueOf(100), Map.of(),
				new MempoolStore.MintSupplyConstraint(token, BigInteger.valueOf(100)));
		when(validator.validateAgainstChainAndMempool(any(MempoolEntry.class),
				eq(MempoolTxAddEvent.AddReason.NEW), eq(false)))
				.thenReturn(MempoolValidationResult.valid(0, constraints));

		assertThat(manager.addTx(candidate.getTx()).status()).isEqualTo(MempoolAddResult.REJECTED_STATE);
	}

	@Test
	void revalidationEvictsOnlyUnaffordableNonceSuffixAfterBalanceDrop() {
		MempoolEntry one = nativeTransfer(40, ALICE, 1, 40);
		MempoolEntry two = nativeTransfer(41, ALICE, 2, 40);
		MempoolEntry three = nativeTransfer(42, ALICE, 3, 40);
		store.addTransactions(List.of(one, two, three), Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);
		for (MempoolEntry entry : List.of(one, two, three)) {
			when(validator.revalidateAgainstChain(entry)).thenReturn(MempoolValidationResult.valid(0));
		}
		WorldState worldState = mock(WorldState.class);
		AccountBalanceState balance = mock(AccountBalanceState.class);
		when(balance.getBalance()).thenReturn(Wei.valueOf(90));
		when(balance.getSpendableBalance()).thenReturn(Wei.valueOf(90));
		when(worldState.getBalance(ALICE, Address.NATIVE_TOKEN)).thenReturn(balance);
		when(chainHead.getHeadState()).thenReturn(worldState);

		manager.revalidateMempool();

		assertThat(store.getAllTxs()).containsExactlyInAnyOrder(one, two);
		assertThat(store.getTxByHash(three.getHash())).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({
			"VALID,REJECTED_OTHER",
			"STALE,STALE",
			"FEE_TOO_LOW,REJECTED_FEE",
			"GOVERNANCE_DUPLICATE,REJECTED_DUPLICATE",
			"STATE_INVALID,REJECTED_STATE",
			"STATELESS_INVALID,REJECTED_OTHER",
			"TRANSIENT_ERROR,REJECTED_OTHER"
	})
	void validationStatusMapsToStablePublicResult(ValidationStatus status, MempoolAddResult expected) {
		assertThat(MempoolAddResult.fromValidation(status)).isEqualTo(expected);
	}

	@Test
	void detailedGovernanceReasonSurvivesThePublicMempoolResult() {
		MempoolEntry candidate = transfer(99, ALICE, 1, 10);
		when(validator.validateAgainstChainAndMempool(any(MempoolEntry.class),
				eq(MempoolTxAddEvent.AddReason.NEW), eq(false)))
				.thenReturn(MempoolValidationResult.stateInvalid(
						MempoolReasonCode.LAST_UNLIMITED_REQUIRED, "not exposed as a contract"));

		var result = manager.addTx(candidate.getTx());

		assertThat(result.status()).isEqualTo(MempoolAddResult.REJECTED_STATE);
		assertThat(result.reasonCode()).isEqualTo(MempoolReasonCode.LAST_UNLIMITED_REQUIRED);
	}

	private MempoolEntry nativeTransfer(int id, Address sender, long nonce, long fee) {
		MempoolEntry entry = transfer(id, sender, nonce, fee);
		when(entry.getTx().getTokenAddress()).thenReturn(Address.NATIVE_TOKEN);
		when(entry.getTx().getAmount()).thenReturn(Wei.ZERO);
		return entry;
	}

	private MempoolEntry tokenMint(int id, Address sender, long nonce, Address token, long amount) {
		TxBipTokenMintPayload payload = mock(TxBipTokenMintPayload.class);
		when(payload.getTokenAddress()).thenReturn(token);
		when(payload.getAmount()).thenReturn(Wei.valueOf(amount));
		return MempoolTestFixtures.governance(id, sender, nonce, 1, payload);
	}

}
