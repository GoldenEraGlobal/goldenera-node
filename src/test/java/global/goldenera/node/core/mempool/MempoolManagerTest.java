package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolManager.MempoolAddResult;
import global.goldenera.node.core.mempool.MempoolValidator.MempoolValidationResult;
import global.goldenera.node.core.mempool.MempoolValidator.ValidationStatus;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolManagerTest {

	MempoolStore store;
	MempoolValidator validator;
	MempoolManager manager;

	@BeforeEach
	void setUp() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MempoolProperties properties = MempoolTestFixtures.properties(100);
		store = new MempoolStore(registry, properties, mock(ChainHeadStateCache.class),
				mock(ApplicationEventPublisher.class));
		validator = mock(MempoolValidator.class);
		manager = new MempoolManager(registry, store, validator, properties, mock(ThreadPoolTaskScheduler.class));
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
	void disconnectedBlockHandlerDelegatesSynchronously() {
		MempoolStore mockedStore = mock(MempoolStore.class);
		MempoolManager eventManager = new MempoolManager(new SimpleMeterRegistry(), mockedStore, validator,
				MempoolTestFixtures.properties(100), mock(ThreadPoolTaskScheduler.class));
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(12L);
		List<Tx> disconnectedTxs = List.of(transfer(9, ALICE, 1, 10).getTx());
		when(block.getTxs()).thenReturn(disconnectedTxs);

		eventManager.onBlockDisconnected(new BlockDisconnectedEvent(this, block));

		verify(mockedStore).addTransactionsBack(block.getTxs(), block);
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

}
