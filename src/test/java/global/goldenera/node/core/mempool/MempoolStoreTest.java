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
import static global.goldenera.node.core.mempool.MempoolTestFixtures.BOB;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.CAROL;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.governance;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.hash;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.vote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.MempoolStore.StorageAddResult;
import global.goldenera.node.core.mempool.MempoolStore.AdmissionConstraints;
import global.goldenera.node.core.mempool.MempoolStore.MintSupplyConstraint;
import global.goldenera.node.core.mempool.MempoolStore.SenderBalances;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStoreTest {

	SimpleMeterRegistry registry;
	ApplicationEventPublisher publisher;
	ChainHeadStateCache chainHead;
	List<Object> events;
	MempoolStore store;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		publisher = mock(ApplicationEventPublisher.class);
		chainHead = mock(ChainHeadStateCache.class);
		events = new ArrayList<>();
		doAnswer(invocation -> {
			events.add(invocation.getArgument(0));
			return null;
		}).when(publisher).publishEvent(any(ApplicationEvent.class));
		store = new MempoolStore(registry, properties(100), chainHead, publisher);
		store.initMetrics();
	}

	@Test
	void rbfRemovesOldTransactionFromEveryIndexAndPublishesOneRemove() {
		MempoolEntry oldEntry = transfer(1, ALICE, 1, 100);
		MempoolEntry replacement = transfer(2, ALICE, 1, 110);
		assertThat(store.addTransaction(oldEntry, 0, MempoolTxAddEvent.AddReason.NEW)).isEqualTo(
				StorageAddResult.ADDED_EXECUTABLE);

		assertThat(store.addTransaction(replacement, 0, MempoolTxAddEvent.AddReason.NEW)).isEqualTo(
				StorageAddResult.ADDED_EXECUTABLE);

		assertThat(store.getTxByHash(oldEntry.getHash())).isEmpty();
		assertThat(store.getTxByHash(replacement.getHash())).containsSame(replacement);
		assertThat(executable()).containsExactly(replacement);
		assertThat(removeEvents(MempoolTxRemoveEvent.RemoveReason.RBF)).extracting(MempoolTxRemoveEvent::getEntry)
				.containsExactly(oldEntry);
		assertInvariants();
	}

	@Test
	void rbfThresholdUsesExactCeiling() {
		MempoolEntry oldEntry = transfer(1, ALICE, 1, 101);
		MempoolEntry belowCeiling = transfer(2, ALICE, 1, 111);
		MempoolEntry exactCeiling = transfer(3, ALICE, 1, 112);
		store.addTransaction(oldEntry, 0, MempoolTxAddEvent.AddReason.NEW);

		assertThat(store.addTransaction(belowCeiling, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.FAILED_FEE_TOO_LOW);
		assertThat(store.addTransaction(exactCeiling, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.ADDED_EXECUTABLE);
		assertThat(store.getTxByHash(oldEntry.getHash())).isEmpty();
		assertInvariants();
	}

	@Test
	void batchRbfSuppressesTransientEventsAndReportsReplaced() {
		MempoolEntry oldEntry = transfer(1, ALICE, 1, 100);
		MempoolEntry replacement = transfer(2, ALICE, 1, 110);

		Map<Hash, StorageAddResult> results = store.addTransactions(
				List.of(oldEntry, replacement), Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

		assertThat(results).containsEntry(oldEntry.getHash(), StorageAddResult.REPLACED)
				.containsEntry(replacement.getHash(), StorageAddResult.ADDED_EXECUTABLE);
		assertThat(events).filteredOn(MempoolTxAddEvent.class::isInstance)
				.extracting(event -> ((MempoolTxAddEvent) event).getEntry())
				.containsExactly(replacement);
		assertThat(removeEvents(MempoolTxRemoveEvent.RemoveReason.RBF)).isEmpty();
		assertInvariants();
	}

	@Test
	void removingMiddleNonceDemotesDescendantsAndMissingNonceHealsQueue() {
		MempoolEntry one = transfer(1, ALICE, 1, 10);
		MempoolEntry two = transfer(2, ALICE, 2, 20);
		MempoolEntry three = transfer(3, ALICE, 3, 30);
		store.addTransactions(List.of(one, two, three), Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

		store.removeTransaction(two.getHash());

		assertThat(executable()).containsExactly(one);
		assertThat(store.getNextAvailableNonce(ALICE, 0)).isEqualTo(2);
		MempoolEntry healedTwo = transfer(4, ALICE, 2, 25);
		assertThat(store.addTransaction(healedTwo, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.ADDED_EXECUTABLE);
		assertThat(executable()).containsExactlyInAnyOrder(one, healedTwo, three);
		assertInvariants();
	}

	@Test
	void pruningMiddleNonceAlsoRepairsExecutableIndex() {
		MempoolEntry one = transfer(1, ALICE, 1, 10);
		MempoolEntry two = transfer(2, ALICE, 2, 20);
		MempoolEntry three = transfer(3, ALICE, 3, 30);
		Instant now = Instant.now();
		one.setFirstSeenTime(now);
		two.setFirstSeenTime(now.minusSeconds(100));
		three.setFirstSeenTime(now);
		store.addTransactions(List.of(one, two, three), Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

		assertThat(store.pruneExpiredTransactions(now.minusSeconds(50))).containsExactly(two);
		assertThat(executable()).containsExactly(one);
		assertThat(store.getTxByHash(three.getHash())).contains(three);
		assertInvariants();
	}

	@Test
	void senderResyncCanMoveNonceBothDirections() {
		MempoolEntry six = transfer(6, ALICE, 6, 10);
		MempoolEntry seven = transfer(7, ALICE, 7, 20);
		store.addTransactions(List.of(six, seven), Map.of(ALICE, 5L), MempoolTxAddEvent.AddReason.SYNC);
		store.resynchronizeSender(ALICE, 6);
		assertThat(store.getTxByHash(six.getHash())).isEmpty();
		assertThat(executable()).containsExactly(seven);

		store.resynchronizeSender(ALICE, 5);
		assertThat(executable()).isEmpty();
		MempoolEntry restoredSix = transfer(8, ALICE, 6, 15);
		store.addTransaction(restoredSix, 5, MempoolTxAddEvent.AddReason.REORG);
		assertThat(executable()).containsExactlyInAnyOrder(restoredSix, seven);
		assertInvariants();
	}

	@Test
	void capacityHoldsExactlyConfiguredMaximumIncludingOne() {
		store = new MempoolStore(registry, properties(1), chainHead, publisher);
		MempoolEntry only = transfer(1, ALICE, 1, 10);
		assertThat(store.addTransaction(only, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.getCount()).isOne();
		assertThat(store.isFull()).isTrue();
		assertThat(store.getTxByHash(only.getHash())).contains(only);
		assertInvariants();
	}

	@Test
	void capacityEvictsWholeLowFeeDependencySuffixWithoutStrandingDescendants() {
		store = new MempoolStore(registry, properties(3), chainHead, publisher);
		MempoolEntry lowParent = transfer(1, ALICE, 1, 1);
		MempoolEntry lowChild = transfer(2, ALICE, 2, 2);
		MempoolEntry highB = transfer(3, BOB, 1, 100);
		MempoolEntry highC = transfer(4, CAROL, 1, 100);
		store.addTransactions(List.of(lowParent, lowChild, highB), Map.of(ALICE, 0L, BOB, 0L),
				MempoolTxAddEvent.AddReason.SYNC);

		store.addTransaction(highC, 0, MempoolTxAddEvent.AddReason.NEW);

		assertThat(store.getTxByHash(lowParent.getHash())).isEmpty();
		assertThat(store.getTxByHash(lowChild.getHash())).isEmpty();
		assertThat(store.getAllTxs()).containsExactlyInAnyOrder(highB, highC);
		assertInvariants();
	}

	@Test
	void packageFeeRateLetsChildPayForParentAndEvictsCheaperIndependentTx() {
		store = new MempoolStore(registry, properties(3), chainHead, publisher);
		MempoolEntry parent = transfer(1, ALICE, 1, 1);
		MempoolEntry child = transfer(2, ALICE, 2, 100);
		MempoolEntry cheapIndependent = transfer(3, BOB, 1, 10);
		MempoolEntry newEntry = transfer(4, CAROL, 1, 11);
		store.addTransactions(List.of(parent, child, cheapIndependent), Map.of(ALICE, 0L, BOB, 0L),
				MempoolTxAddEvent.AddReason.SYNC);

		store.addTransaction(newEntry, 0, MempoolTxAddEvent.AddReason.NEW);

		assertThat(store.getTxByHash(cheapIndependent.getHash())).isEmpty();
		assertThat(store.getAllTxs()).containsExactlyInAnyOrder(parent, child, newEntry);
		assertInvariants();
	}

	@Test
	void singleAndBatchAdmissionProduceSameClassification() {
		MempoolStore single = new MempoolStore(registry, properties(10), chainHead, publisher);
		MempoolStore batch = new MempoolStore(new SimpleMeterRegistry(), properties(10), chainHead, publisher);
		List<MempoolEntry> entries = List.of(transfer(1, ALICE, 3, 30), transfer(2, ALICE, 1, 10),
				transfer(3, ALICE, 2, 20));
		entries.forEach(entry -> single.addTransaction(entry, 0, MempoolTxAddEvent.AddReason.NEW));
		batch.addTransactions(entries, Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

		assertThat(single.getAllTxHashes()).containsExactlyInAnyOrderElementsOf(batch.getAllTxHashes());
		assertThat(toList(single.getExecutableTransactionsIterator()))
				.containsExactlyInAnyOrderElementsOf(toList(batch.getExecutableTransactionsIterator()));
	}

	@Test
	void feeStatisticsAndFutureMetricReflectOnlyTrueExecutableAndFutureEntries() {
		MempoolEntry one = transfer(1, ALICE, 1, 100);
		MempoolEntry future = transfer(2, ALICE, 3, 1000);
		store.addTransactions(List.of(one, future), Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

		assertThat(store.getFeeStatistics().txCount()).isOne();
		assertThat(store.getFeeStatistics().medianFeePerByte()).isEqualTo(1.0);
		assertThat(registry.get("blockchain.mempool.future_tx_count").gauge().value()).isEqualTo(1.0);
	}

	@Test
	void duplicateHashAdmissionIsAtomicUnderConcurrency() throws Exception {
		MempoolEntry entry = transfer(1, ALICE, 1, 10);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<StorageAddResult> first = executor.submit(() -> {
				start.await();
				return store.addTransaction(entry, 0, MempoolTxAddEvent.AddReason.NEW);
			});
			Future<StorageAddResult> second = executor.submit(() -> {
				start.await();
				return store.addTransaction(entry, 0, MempoolTxAddEvent.AddReason.NEW);
			});
			start.countDown();
			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(
					StorageAddResult.ADDED_EXECUTABLE, StorageAddResult.DUPLICATE_HASH);
		}
		assertThat(store.getCount()).isOne();
		assertInvariants();
	}

	@Test
	void clearPublishesEveryRemovalAndListenerFailureCannotInterruptCleanup() {
		MempoolEntry one = transfer(1, ALICE, 1, 10);
		MempoolEntry two = transfer(2, BOB, 1, 20);
		store.addTransactions(List.of(one, two), Map.of(ALICE, 0L, BOB, 0L), MempoolTxAddEvent.AddReason.SYNC);
		doThrow(new IllegalStateException("listener failed")).when(publisher)
				.publishEvent(any(MempoolTxRemoveEvent.class));

		assertThatCode(store::clear).doesNotThrowAnyException();
		assertThat(store.getCount()).isZero();
		assertThat(executable()).isEmpty();
		verify(publisher, atLeast(2)).publishEvent(any(MempoolTxRemoveEvent.class));
	}

	@Test
	void governanceReservationsAreOwnedAndRemovedForEveryTrackedType() {
		Address target = MempoolTestFixtures.address(50);
		TxBipAuthorityAddPayload authorityAdd = mock(TxBipAuthorityAddPayload.class);
		when(authorityAdd.getAddress()).thenReturn(target);
		assertReservation(governance(1, ALICE, 1, 10, authorityAdd), () -> store.isAuthorityAddPending(target));

		TxBipAuthorityRemovePayload authorityRemove = mock(TxBipAuthorityRemovePayload.class);
		when(authorityRemove.getAddress()).thenReturn(target);
		assertReservation(governance(2, ALICE, 1, 10, authorityRemove), () -> store.isAuthorityRemovePending(target));

		TxBipValidatorAddPayload validatorAdd = mock(TxBipValidatorAddPayload.class);
		when(validatorAdd.getAddress()).thenReturn(target);
		assertReservation(governance(3, ALICE, 1, 10, validatorAdd), () -> store.isValidatorAddPending(target));

		TxBipValidatorRemovePayload validatorRemove = mock(TxBipValidatorRemovePayload.class);
		when(validatorRemove.getAddress()).thenReturn(target);
		assertReservation(governance(4, ALICE, 1, 10, validatorRemove), () -> store.isValidatorRemovePending(target));

		TxBipValidatorMiningPolicySetPayload validatorPolicy = mock(TxBipValidatorMiningPolicySetPayload.class);
		when(validatorPolicy.getValidatorAddress()).thenReturn(target);
		assertReservation(governance(9, ALICE, 1, 10, validatorPolicy),
				() -> store.isValidatorMiningPolicyChangePending(target));

		TxBipAddressAliasAddPayload aliasAdd = mock(TxBipAddressAliasAddPayload.class);
		when(aliasAdd.getAlias()).thenReturn("alice");
		assertReservation(governance(5, ALICE, 1, 10, aliasAdd), () -> store.isAddressAliasAddPending("alice"));

		TxBipAddressAliasRemovePayload aliasRemove = mock(TxBipAddressAliasRemovePayload.class);
		when(aliasRemove.getAlias()).thenReturn("alice");
		assertReservation(governance(6, ALICE, 1, 10, aliasRemove), () -> store.isAddressAliasRemovePending("alice"));

		TxBipNetworkParamsSetPayload params = mock(TxBipNetworkParamsSetPayload.class);
		assertReservation(governance(7, ALICE, 1, 10, params), store::isNetworkParamsChangePending);

		MempoolEntry vote = vote(8, ALICE, 1, 10, hash(80));
		assertReservation(vote, () -> store.isBipVotePending(hash(80), ALICE));
	}

	@Test
	void storageAtomicallyRejectsGovernanceConflictButAllowsSameNonceRbf() {
		TxBipAuthorityAddPayload payload = mock(TxBipAuthorityAddPayload.class);
		when(payload.getAddress()).thenReturn(BOB);
		MempoolEntry first = governance(1, ALICE, 1, 100, payload);
		MempoolEntry conflict = governance(2, CAROL, 1, 200, payload);
		MempoolEntry replacement = governance(3, ALICE, 1, 110, payload);

		assertThat(store.addTransaction(first, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.addTransaction(conflict, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.GOVERNANCE_CONFLICT);
		assertThat(store.addTransaction(replacement, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.getTxByHash(first.getHash())).isEmpty();
		assertThat(store.isAuthorityAddPending(BOB)).isTrue();
		assertInvariants();
	}

	@Test
	void secondVoteBySameAuthorityForSameBipIsRejectedAtStorageBoundary() {
		MempoolEntry first = vote(1, ALICE, 1, 10, hash(90));
		MempoolEntry second = vote(2, ALICE, 2, 20, hash(90));

		assertThat(store.addTransaction(first, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.addTransaction(second, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.GOVERNANCE_CONFLICT);
		assertThat(store.getAllTxs()).containsExactly(first);
	}

	@Test
	void duplicateHashInsideBatchDoesNotOverwriteFirstEntry() {
		MempoolEntry first = transfer(1, ALICE, 1, 10);
		MempoolEntry duplicate = transfer(2, BOB, 1, 20);
		Hash duplicateHash = first.getHash();
		when(duplicate.getTx().getHash()).thenReturn(duplicateHash);

		Map<Hash, StorageAddResult> result = store.addTransactions(List.of(first, duplicate),
				Map.of(ALICE, 0L, BOB, 0L), MempoolTxAddEvent.AddReason.SYNC);

		assertThat(result.get(first.getHash())).isEqualTo(StorageAddResult.DUPLICATE_HASH);
		assertThat(store.getTxByHash(first.getHash())).containsSame(first);
		assertThat(store.getTxsBySender(BOB)).isEmpty();
		assertInvariants();
	}

	@Test
	void opposingGovernanceOperationsShareCanonicalConflictKeys() {
		Address target = MempoolTestFixtures.address(50);
		TxBipAuthorityAddPayload authorityAdd = mock(TxBipAuthorityAddPayload.class);
		when(authorityAdd.getAddress()).thenReturn(target);
		TxBipAuthorityRemovePayload authorityRemove = mock(TxBipAuthorityRemovePayload.class);
		when(authorityRemove.getAddress()).thenReturn(target);
		assertCanonicalConflict(governance(101, ALICE, 1, 10, authorityAdd),
				governance(102, BOB, 1, 10, authorityRemove));

		TxBipValidatorAddPayload validatorAdd = mock(TxBipValidatorAddPayload.class);
		when(validatorAdd.getAddress()).thenReturn(target);
		TxBipValidatorRemovePayload validatorRemove = mock(TxBipValidatorRemovePayload.class);
		when(validatorRemove.getAddress()).thenReturn(target);
		assertCanonicalConflict(governance(103, ALICE, 1, 10, validatorAdd),
				governance(104, BOB, 1, 10, validatorRemove));

		TxBipAddressAliasAddPayload aliasAdd = mock(TxBipAddressAliasAddPayload.class);
		when(aliasAdd.getAlias()).thenReturn("canonical");
		TxBipAddressAliasRemovePayload aliasRemove = mock(TxBipAddressAliasRemovePayload.class);
		when(aliasRemove.getAlias()).thenReturn("canonical");
		assertCanonicalConflict(governance(105, ALICE, 1, 10, aliasAdd),
				governance(106, BOB, 1, 10, aliasRemove));
	}

	@Test
	void networkParamsConflictIsGlobalAcrossAuthorities() {
		TxBipNetworkParamsSetPayload firstPayload = mock(TxBipNetworkParamsSetPayload.class);
		TxBipNetworkParamsSetPayload secondPayload = mock(TxBipNetworkParamsSetPayload.class);

		assertCanonicalConflict(governance(110, ALICE, 1, 10, firstPayload),
				governance(111, BOB, 1, 10, secondPayload));
		assertThat(store.isNetworkParamsChangePending()).isTrue();
	}

	@Test
	void tokenUpdatesConflictPerTokenButDifferentTokensCanCoexist() {
		Address token = MempoolTestFixtures.address(60);
		Address otherToken = MempoolTestFixtures.address(61);
		TxBipTokenUpdatePayload firstPayload = mock(TxBipTokenUpdatePayload.class);
		when(firstPayload.getTokenAddress()).thenReturn(token);
		TxBipTokenUpdatePayload conflictPayload = mock(TxBipTokenUpdatePayload.class);
		when(conflictPayload.getTokenAddress()).thenReturn(token);
		TxBipTokenUpdatePayload independentPayload = mock(TxBipTokenUpdatePayload.class);
		when(independentPayload.getTokenAddress()).thenReturn(otherToken);

		assertThat(store.addTransaction(governance(120, ALICE, 1, 10, firstPayload), 0,
				MempoolTxAddEvent.AddReason.NEW)).matches(StorageAddResult::isSuccess);
		assertThat(store.addTransaction(governance(121, BOB, 1, 10, conflictPayload), 0,
				MempoolTxAddEvent.AddReason.NEW)).isEqualTo(StorageAddResult.GOVERNANCE_CONFLICT);
		assertThat(store.addTransaction(governance(122, CAROL, 1, 10, independentPayload), 0,
				MempoolTxAddEvent.AddReason.NEW)).matches(StorageAddResult::isSuccess);
	}

	@Test
	void concurrentNativeAdmissionsCannotOverReserveConfirmedBalance() throws Exception {
		MempoolEntry first = nativeTransfer(130, ALICE, 1, 60, 0);
		MempoolEntry second = nativeTransfer(131, ALICE, 2, 60, 0);
		AdmissionConstraints constraints = new AdmissionConstraints(Wei.valueOf(100), Map.of(), null);

		assertExactlyOneConcurrentAdmission(first, second, constraints, StorageAddResult.INSUFFICIENT_FUNDS);
		assertThat(store.nativeReservation(ALICE, nativeTransfer(132, ALICE, 3, 0, 0).getTx(), Wei.valueOf(100))
				.reserved()).isEqualTo(Wei.valueOf(60));
	}

	@Test
	void concurrentTokenAdmissionsCannotOverReserveConfirmedBalance() throws Exception {
		Address token = MempoolTestFixtures.address(70);
		MempoolEntry first = tokenTransfer(140, ALICE, 1, token, 60, 1);
		MempoolEntry second = tokenTransfer(141, ALICE, 2, token, 60, 1);
		AdmissionConstraints constraints = new AdmissionConstraints(
				Wei.valueOf(10), Map.of(token, Wei.valueOf(100)), null);

		assertExactlyOneConcurrentAdmission(first, second, constraints, StorageAddResult.INSUFFICIENT_FUNDS);
		assertThat(store.tokenReservation(ALICE, token,
				tokenTransfer(142, ALICE, 3, token, 0, 0).getTx(), Wei.valueOf(100)).reserved())
				.isEqualTo(Wei.valueOf(60));
	}

	@Test
	void concurrentMintsCannotExceedPendingMaxSupplyAllowance() throws Exception {
		Address token = MempoolTestFixtures.address(80);
		MempoolEntry first = tokenMint(150, ALICE, 1, token, 60);
		MempoolEntry second = tokenMint(151, BOB, 1, token, 60);
		AdmissionConstraints constraints = new AdmissionConstraints(
				Wei.valueOf(100), Map.of(), new MintSupplyConstraint(token, BigInteger.valueOf(100)));

		assertExactlyOneConcurrentAdmission(first, second, constraints, StorageAddResult.TOKEN_SUPPLY_CONFLICT);
		assertThat(store.getPendingTokenMintAmount(token, null)).isEqualTo(BigInteger.valueOf(60));
	}

	@Test
	void balanceDropKeepsAffordableLowestNoncePrefixAndEvictsEntireSuffix() {
		MempoolEntry one = nativeTransfer(160, ALICE, 1, 40, 0);
		MempoolEntry two = nativeTransfer(161, ALICE, 2, 40, 0);
		MempoolEntry three = nativeTransfer(162, ALICE, 3, 40, 0);
		store.addTransactions(List.of(one, two, three), Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

		store.reconcileSenderBalances(Map.of(ALICE, new SenderBalances(Wei.valueOf(90), Map.of())));

		assertThat(store.getAllTxs()).containsExactlyInAnyOrder(one, two);
		assertThat(store.getTxByHash(three.getHash())).isEmpty();
		assertThat(removeEvents(MempoolTxRemoveEvent.RemoveReason.INSUFFICIENT_FUNDS))
				.extracting(MempoolTxRemoveEvent::getEntry).containsExactly(three);
		assertInvariants();
	}

	private void assertCanonicalConflict(MempoolEntry first, MempoolEntry conflict) {
		store.clear();
		assertThat(store.addTransaction(first, 0, MempoolTxAddEvent.AddReason.NEW))
				.matches(StorageAddResult::isSuccess);
		assertThat(store.addTransaction(conflict, 0, MempoolTxAddEvent.AddReason.NEW))
				.isEqualTo(StorageAddResult.GOVERNANCE_CONFLICT);
		assertThat(store.getAllTxs()).containsExactly(first);
	}

	private void assertExactlyOneConcurrentAdmission(MempoolEntry first, MempoolEntry second,
			AdmissionConstraints constraints, StorageAddResult rejection) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<StorageAddResult> firstResult = executor.submit(() -> {
				start.await();
				return store.addTransaction(first, 0, MempoolTxAddEvent.AddReason.NEW, constraints);
			});
			Future<StorageAddResult> secondResult = executor.submit(() -> {
				start.await();
				return store.addTransaction(second, 0, MempoolTxAddEvent.AddReason.NEW, constraints);
			});
			start.countDown();
			List<StorageAddResult> results = List.of(firstResult.get(), secondResult.get());
			assertThat(results).filteredOn(StorageAddResult::isSuccess).hasSize(1);
			assertThat(results).contains(rejection);
		}
		assertThat(store.getCount()).isOne();
		assertInvariants();
	}

	private MempoolEntry nativeTransfer(int id, Address sender, long nonce, long amount, long fee) {
		return tokenTransfer(id, sender, nonce, Address.NATIVE_TOKEN, amount, fee);
	}

	private MempoolEntry tokenTransfer(int id, Address sender, long nonce, Address token, long amount, long fee) {
		MempoolEntry entry = transfer(id, sender, nonce, fee);
		when(entry.getTx().getTokenAddress()).thenReturn(token);
		when(entry.getTx().getAmount()).thenReturn(Wei.valueOf(amount));
		return entry;
	}

	private MempoolEntry tokenMint(int id, Address sender, long nonce, Address token, long amount) {
		TxBipTokenMintPayload payload = mock(TxBipTokenMintPayload.class);
		when(payload.getTokenAddress()).thenReturn(token);
		when(payload.getAmount()).thenReturn(Wei.valueOf(amount));
		return governance(id, sender, nonce, 1, payload);
	}

	private void assertReservation(MempoolEntry entry, BooleanSupplier pending) {
		store.clear();
		store.addTransaction(entry, 0, MempoolTxAddEvent.AddReason.NEW);
		assertThat(pending.getAsBoolean()).isTrue();
		store.removeTransaction(entry.getHash());
		assertThat(pending.getAsBoolean()).isFalse();
	}

	private List<MempoolTxRemoveEvent> removeEvents(MempoolTxRemoveEvent.RemoveReason reason) {
		return events.stream().filter(MempoolTxRemoveEvent.class::isInstance)
				.map(MempoolTxRemoveEvent.class::cast)
				.filter(event -> event.getReason() == reason)
				.toList();
	}

	private List<MempoolEntry> executable() {
		return toList(store.getExecutableTransactionsIterator());
	}

	private List<MempoolEntry> toList(Iterator<MempoolEntry> iterator) {
		List<MempoolEntry> entries = new ArrayList<>();
		iterator.forEachRemaining(entries::add);
		return entries;
	}

	private void assertInvariants() {
		Set<MempoolEntry> all = new HashSet<>(store.getAllTxs());
		assertThat(store.getCount()).isEqualTo(all.size());
		assertThat(executable()).allMatch(all::contains);
		assertThat(store.getAllTxHashes()).containsExactlyInAnyOrderElementsOf(
				all.stream().map(MempoolEntry::getHash).toList());
		for (Address sender : List.of(ALICE, BOB, CAROL)) {
			assertThat(store.getTxsBySender(sender)).allMatch(all::contains);
		}
	}

}
