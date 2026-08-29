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

import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.builder.TxBuilder;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.PrivateKey;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.serialization.tx.TxEncoder;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.journal.MempoolLifecycleJournalWriter;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolAdmissionReason;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolRecoveryPage;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolStatus;
import global.goldenera.node.core.storage.blockchain.mempool.StoredMempoolTransaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolCommitFailureExactRestoreTest {

	@Test
	void failedCommitRestoresExactlyThePersistedActiveHashSet() throws Exception {
		Tx persistedTx = transfer(1, 1L, 100L, Instant.parse("2026-08-29T10:10:00Z"));
		StoredMempoolTransaction persisted = record(persistedTx, Instant.now());
		Fixture fixture = fixture(List.of(persisted));
		MempoolEntry persistedEntry = entry(persistedTx, persisted);
		assertThat(fixture.store.restoreTransaction(persistedEntry, 0L, null).result().isSuccess()).isTrue();
		Tx rejectedTx = transfer(2, 1L, 100L, Instant.parse("2026-08-29T10:10:01Z"));

		assertThatThrownBy(() -> fixture.store.addTransaction(
				entry(rejectedTx, record(rejectedTx, Instant.now())),
				0L,
				MempoolTxAddEvent.AddReason.NEW))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("commit unavailable");

		assertThat(fixture.store.getAllTxHashes()).containsExactly(persistedTx.getHash());
		verify(fixture.publisher, never()).publishEvent(any());
	}

	@Test
	void conflictingPersistedRecordsFailClosedWithoutSilentlyDroppingEitherHash() throws Exception {
		Tx first = transfer(3, 1L, 100L, Instant.parse("2026-08-29T10:10:02Z"));
		Tx conflicting = transfer(3, 1L, 110L, Instant.parse("2026-08-29T10:10:03Z"));
		Fixture fixture = fixture(List.of(record(first, Instant.now()), record(conflicting, Instant.now().plusMillis(1))));
		Tx tentative = transfer(4, 1L, 100L, Instant.parse("2026-08-29T10:10:04Z"));

		assertThatThrownBy(() -> fixture.store.addTransaction(
				entry(tentative, record(tentative, Instant.now())),
				0L,
				MempoolTxAddEvent.AddReason.NEW))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("could not be restored")
				.hasStackTraceContaining(
						"Persistent mempool contains two active transactions for sender/nonce");

		assertThatThrownBy(fixture.store::getAllTxHashes)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("terminally unavailable");
		assertThatThrownBy(() -> fixture.store.getTxByHash(tentative.getHash()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("terminally unavailable");
	}

	private Fixture fixture(List<StoredMempoolTransaction> records) {
		PersistentMempoolStore persistentStore = mock(PersistentMempoolStore.class);
		when(persistentStore.scanActive(null, 64)).thenReturn(new MempoolRecoveryPage(
				records, records.getLast().txHash(), false));
		for (StoredMempoolTransaction record : records) {
			when(persistentStore.findActive(record.txHash())).thenReturn(Optional.of(record));
		}
		MempoolLifecycleJournalWriter writer = mock(MempoolLifecycleJournalWriter.class);
		doThrow(new IllegalStateException("commit unavailable"))
				.when(writer).commitBeforeWake(any(), anyList(), anyList());
		ChainHeadStateCache chainHead = mock(ChainHeadStateCache.class);
		WorldState worldState = mock(WorldState.class);
		AccountNonceState nonce = mock(AccountNonceState.class);
		when(nonce.getNonce()).thenReturn(0L);
		when(worldState.getNonce(any(Address.class))).thenReturn(nonce);
		when(chainHead.getHeadState()).thenReturn(worldState);
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		MempoolStore store = new MempoolStore(
				new SimpleMeterRegistry(), properties(100), chainHead, publisher, writer, persistentStore);
		return new Fixture(store, publisher);
	}

	private MempoolEntry entry(Tx tx, StoredMempoolTransaction stored) {
		return new MempoolEntry(tx, stored.firstSeenTime(), stored.firstSeenHeight(), null);
	}

	private StoredMempoolTransaction record(Tx tx, Instant firstSeen) {
		return new StoredMempoolTransaction(
				StoredMempoolTransaction.CURRENT_VERSION,
				StoredMempoolStatus.ACTIVE,
				tx.getHash(),
				TxEncoder.INSTANCE.encode(tx, true).toArray(),
				firstSeen,
				10L,
				MempoolAdmissionReason.NEW,
				null);
	}

	private Tx transfer(int privateKey, long nonce, long fee, Instant timestamp) throws Exception {
		return TxBuilder.create()
				.type(TxType.TRANSFER)
				.network(Network.TESTNET)
				.timestamp(timestamp)
				.recipient(Address.fromHexString("0x" + "24".repeat(20)))
				.amount(Wei.valueOf(BigInteger.ONE))
				.fee(Wei.valueOf(fee))
				.nonce(nonce)
				.sign(PrivateKey.wrap(Bytes32.fromHexString(String.format("0x%064x", privateKey))));
	}

	private record Fixture(MempoolStore store, ApplicationEventPublisher publisher) {
	}
}
