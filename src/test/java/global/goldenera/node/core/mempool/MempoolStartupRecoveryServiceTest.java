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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolStartupRecoveryService.RecoveryResult;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleJournalWriter;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolAdmissionReason;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolRecoveryPage;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolBoundedScanner;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolStatus;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolTransaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStartupRecoveryServiceTest {

	@Test
	void restoresValidActiveRecordWithoutEmittingDuplicatePending() throws Exception {
		Tx tx = transfer(1, 1L, 100L, Instant.parse("2026-08-29T10:00:00Z"));
		Fixture fixture = fixture(List.of(record(tx, Instant.now(), null)));
		when(fixture.validator.revalidateAgainstChain(any()))
				.thenReturn(MempoolValidator.MempoolValidationResult.valid(0L));

		RecoveryResult result = fixture.recovery.recover();

		assertThat(result).isEqualTo(new RecoveryResult(1, 1, 0));
		assertThat(fixture.store.getTxByHash(tx.getHash())).isPresent();
		verify(fixture.lifecycleWriter, never()).commitBeforeWake(any(), anyList(), anyList());
		verify(fixture.publisher, never()).publishEvent(any());
	}

	@Test
	void tombstonesStaleRecordInsteadOfRestoringIt() throws Exception {
		Tx tx = transfer(2, 1L, 100L, Instant.parse("2026-08-29T10:00:01Z"));
		Fixture fixture = fixture(List.of(record(tx, Instant.now(), null)));
		when(fixture.validator.revalidateAgainstChain(any()))
				.thenReturn(MempoolValidator.MempoolValidationResult.stale(1L, "already mined nonce"));

		RecoveryResult result = fixture.recovery.recover();

		assertThat(result).isEqualTo(new RecoveryResult(1, 0, 1));
		assertThat(fixture.store.getCount()).isZero();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxRemoveEvent>> removals = ArgumentCaptor.forClass(List.class);
		verify(fixture.lifecycleWriter).commitBeforeWake(any(), removals.capture(), eq(List.of()));
		assertThat(removals.getValue()).singleElement()
				.extracting(MempoolTxRemoveEvent::getReason)
				.isEqualTo(MempoolTxRemoveEvent.RemoveReason.STALE_NONCE);
	}

	@Test
	void tombstonesTransactionThatNoLongerHasSufficientChainBalance() throws Exception {
		Tx tx = transfer(6, 1L, 100L, Instant.parse("2026-08-29T10:00:05Z"));
		Fixture fixture = fixture(List.of(record(tx, Instant.now(), null)));
		when(fixture.validator.revalidateAgainstChain(any()))
				.thenReturn(MempoolValidator.MempoolValidationResult.stateInvalid("insufficient balance"));

		RecoveryResult result = fixture.recovery.recover();

		assertThat(result).isEqualTo(new RecoveryResult(1, 0, 1));
		assertThat(fixture.store.getTxByHash(tx.getHash())).isEmpty();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxRemoveEvent>> removals = ArgumentCaptor.forClass(List.class);
		verify(fixture.lifecycleWriter).commitBeforeWake(any(), removals.capture(), eq(List.of()));
		assertThat(removals.getValue()).singleElement()
				.extracting(MempoolTxRemoveEvent::getReason)
				.isEqualTo(MempoolTxRemoveEvent.RemoveReason.INVALID);
	}

	@Test
	void recoveryKeepsOnlyHigherFeeReplacementForSameSenderAndNonce() throws Exception {
		Instant firstSeen = Instant.now();
		Tx original = transfer(3, 1L, 100L, Instant.parse("2026-08-29T10:00:02Z"));
		Tx replacement = transfer(3, 1L, 110L, Instant.parse("2026-08-29T10:00:03Z"));
		Fixture fixture = fixture(List.of(
				record(original, firstSeen, null),
				record(replacement, firstSeen.plusMillis(1), original.getHash())));
		when(fixture.validator.revalidateAgainstChain(any()))
				.thenReturn(MempoolValidator.MempoolValidationResult.valid(0L));

		RecoveryResult result = fixture.recovery.recover();

		assertThat(result).isEqualTo(new RecoveryResult(2, 1, 1));
		assertThat(fixture.store.getTxByHash(original.getHash())).isEmpty();
		assertThat(fixture.store.getTxByHash(replacement.getHash())).isPresent();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxRemoveEvent>> removals = ArgumentCaptor.forClass(List.class);
		verify(fixture.lifecycleWriter).commitBeforeWake(any(), removals.capture(), eq(List.of()));
		assertThat(removals.getValue()).singleElement()
				.extracting(MempoolTxRemoveEvent::getReason)
				.isEqualTo(MempoolTxRemoveEvent.RemoveReason.RBF);
	}

	@Test
	void corruptPersistentScanFailsRecoveryBeforePeerStartupCanProceed() {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		when(persistentStore.scanActive(null, 64))
				.thenThrow(new IllegalStateException("corrupt persisted transaction"));
		Fixture fixture = fixture(persistentStore);

		assertThatThrownBy(fixture.recovery::recover)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("corrupt");
		assertThat(fixture.store.getCount()).isZero();
	}

	@Test
	void oversizedPersistentRecordCountFailsBeforeRamReset() {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		List<StoredMempoolTransaction> records = IntStream.range(0, 4)
				.mapToObj(index -> {
					StoredMempoolTransaction record = mock(StoredMempoolTransaction.class);
					when(record.rawSignedTx()).thenReturn(new byte[] { 1 });
					return record;
				})
				.toList();
		when(persistentStore.scanActive(null, 64))
				.thenReturn(new MempoolRecoveryPage(records, null, false));
		MempoolProperties properties = MempoolTestFixtures.properties(2);
		Fixture fixture = fixture(persistentStore, properties);

		assertThatThrownBy(fixture.recovery::recover)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("record count");
		assertThat(fixture.store.getCount()).isZero();
	}

	@Test
	void streamingScannerDoesNotRejectAValidRecordBecauseOfAnArbitraryAggregateByteCeiling() {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		StoredMempoolTransaction record = mock(StoredMempoolTransaction.class);
		Hash hash = Hash.ZERO;
		when(record.txHash()).thenReturn(hash);
		when(record.firstSeenTime()).thenReturn(Instant.now());
		when(record.rawSignedTx()).thenReturn(new byte[StoredMempoolTransaction.MAX_RAW_TX_BYTES]);
		when(persistentStore.scanActive(null, 64))
				.thenReturn(new MempoolRecoveryPage(List.of(record), hash, false));
		when(persistentStore.findActive(hash)).thenReturn(Optional.of(record));
		MempoolProperties properties = MempoolTestFixtures.properties(100);

		List<StoredMempoolTransaction> observed = new ArrayList<>();
		assertThat(PersistentMempoolBoundedScanner.scanOrdered(persistentStore, properties, observed::add)).isOne();
		assertThat(observed).containsExactly(record);
	}

	@Test
	void streamingScannerTraversesSixHundredLogicalMegabytesWithoutAnAggregateRawCopy() {
		byte[] sharedLogicalMegabyte = new byte[1024 * 1024];
		List<StoredMempoolTransaction> records = IntStream.range(0, 600)
				.mapToObj(index -> {
					StoredMempoolTransaction record = mock(StoredMempoolTransaction.class);
					when(record.txHash()).thenReturn(Hash.fromHexString("0x" + "%064x".formatted(index + 1)));
					when(record.firstSeenTime()).thenReturn(Instant.ofEpochMilli(index));
					when(record.rawSignedTx()).thenReturn(sharedLogicalMegabyte);
					return record;
				})
				.toList();
		Map<Hash, StoredMempoolTransaction> byHash = records.stream().collect(
				Collectors.toMap(StoredMempoolTransaction::txHash, Function.identity()));
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		when(persistentStore.findActive(any())).thenAnswer(invocation -> Optional.ofNullable(
				byHash.get(invocation.<Hash>getArgument(0))));
		when(persistentStore.scanActive(nullable(Hash.class), eq(64))).thenAnswer(invocation -> {
			Hash cursor = invocation.getArgument(0);
			int start = cursor == null ? 0 : records.indexOf(byHash.get(cursor)) + 1;
			int end = Math.min(records.size(), start + 64);
			List<StoredMempoolTransaction> page = records.subList(start, end);
			return new MempoolRecoveryPage(
					page, page.isEmpty() ? null : page.getLast().txHash(), end < records.size());
		});

		int[] observed = { 0 };
		assertThat(PersistentMempoolBoundedScanner.scanOrdered(
				persistentStore, MempoolTestFixtures.properties(1_000), ignored -> observed[0]++))
				.isEqualTo(600);
		assertThat(observed[0]).isEqualTo(600);
	}

	@Test
	void minedRecordIsDeletedWithoutRevalidation() throws Exception {
		Tx tx = transfer(4, 1L, 100L, Instant.parse("2026-08-29T10:00:04Z"));
		Fixture fixture = fixture(List.of(record(tx, Instant.now(), null)));
		when(fixture.chainQuery.getTransactionBlock(tx.getHash()))
				.thenReturn(Optional.of(mock(StoredBlock.class)));

		fixture.recovery.recover();

		verify(fixture.validator, never()).revalidateAgainstChain(any());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxRemoveEvent>> removals = ArgumentCaptor.forClass(List.class);
		verify(fixture.lifecycleWriter).commitBeforeWake(any(), removals.capture(), eq(List.of()));
		assertThat(removals.getValue()).singleElement()
				.extracting(MempoolTxRemoveEvent::getReason)
				.isEqualTo(MempoolTxRemoveEvent.RemoveReason.MINED);
	}

	@Test
	void recoverySplitsTombstonesBelowWorstCaseMutationByteCeiling() throws Exception {
		List<StoredMempoolTransaction> records = new ArrayList<>();
		for (int index = 0; index < 33; index++) {
			Tx tx = transfer(index + 100, 1L, 100L, Instant.parse("2026-08-29T10:01:00Z"));
			records.add(record(tx, Instant.now(), null));
		}
		Fixture fixture = fixture(records);
		when(fixture.validator.revalidateAgainstChain(any()))
				.thenReturn(MempoolValidator.MempoolValidationResult.stale(1L, "stale"));

		RecoveryResult result = fixture.recovery.recover();

		assertThat(result).isEqualTo(new RecoveryResult(33, 0, 33));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MempoolTxRemoveEvent>> removals = ArgumentCaptor.forClass(List.class);
		verify(fixture.lifecycleWriter, times(2)).commitBeforeWake(any(), removals.capture(), eq(List.of()));
		assertThat(removals.getAllValues()).extracting(List::size).containsExactly(32, 1);
	}

	private Fixture fixture(List<StoredMempoolTransaction> records) {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		when(persistentStore.scanActive(null, 64))
				.thenReturn(new MempoolRecoveryPage(records, records.isEmpty() ? null : records.getLast().txHash(), false));
		for (StoredMempoolTransaction record : records) {
			when(persistentStore.findActive(record.txHash())).thenReturn(Optional.of(record));
		}
		return fixture(persistentStore);
	}

	private Fixture fixture(PersistentMempoolStore persistentStore) {
		MempoolProperties properties = MempoolTestFixtures.properties(100);
		return fixture(persistentStore, properties);
	}

	private Fixture fixture(
			PersistentMempoolStore persistentStore,
			MempoolProperties properties) {
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		MempoolLifecycleJournalWriter lifecycleWriter = mock(MempoolLifecycleJournalWriter.class);
		MempoolStore store = new MempoolStore(
				new SimpleMeterRegistry(), properties, mock(ChainHeadStateCache.class), publisher,
				MempoolLifecycleJournalWriter.disabled(), persistentStore);
		MempoolValidator validator = mock(MempoolValidator.class);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getTransactionBlock(any())).thenReturn(Optional.empty());
		MempoolStartupRecoveryService recovery = new MempoolStartupRecoveryService(
				persistentStore, lifecycleWriter, store, validator, properties, chainQuery);
		return new Fixture(recovery, store, validator, lifecycleWriter, chainQuery, publisher);
	}

	private StoredMempoolTransaction record(Tx tx, Instant firstSeen, Hash replaces) {
		return new StoredMempoolTransaction(
				StoredMempoolTransaction.CURRENT_VERSION,
				StoredMempoolStatus.ACTIVE,
				tx.getHash(),
				TxEncoder.INSTANCE.encode(tx, true).toArray(),
				firstSeen,
				10L,
				MempoolAdmissionReason.NEW,
				replaces);
	}

	private Tx transfer(int privateKey, long nonce, long fee, Instant timestamp) throws Exception {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.timestamp(timestamp)
				.recipient(Address.fromHexString("0x" + "42".repeat(20)))
				.amount(Wei.valueOf(BigInteger.ONE))
				.fee(Wei.valueOf(fee))
				.nonce(nonce)
				.sign(PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", privateKey))));
	}

	private record Fixture(
			MempoolStartupRecoveryService recovery,
			MempoolStore store,
			MempoolValidator validator,
			MempoolLifecycleJournalWriter lifecycleWriter,
			ChainQuery chainQuery,
			ApplicationEventPublisher publisher) {
	}
}
