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
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.impl.AccountNonceStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolStoreRocksDbReorgIntegrationTest {

	private static final Instant BLOCK_TIME = Instant.parse("2026-01-01T00:00:00Z");

	@TempDir
	Path databaseDirectory;

	@Test
	void persistedHeadConnectThenDisconnectResynchronizesNonceDownAndReaddsTransaction() throws Exception {
		try (PersistentWorldStateTestSupport persistence = new PersistentWorldStateTestSupport(databaseDirectory)) {
			Hash baseRoot = persistedStateAtNonce(persistence, 0);
			Hash connectedRoot = persistedStateAtNonce(persistence, 1);
			Block baseBlock = block(10, baseRoot, List.of());
			MempoolEntry mined = transfer(1, ALICE, 1, 10);
			MempoolEntry descendant = transfer(2, ALICE, 2, 20);
			Block connectedBlock = block(11, connectedRoot, List.of(mined.getTx()));

			AtomicReference<StoredBlock> latest = new AtomicReference<>(stored(baseBlock));
			ChainQuery chainQuery = mock(ChainQuery.class);
			when(chainQuery.getLatestStoredBlockOrThrow()).thenAnswer(invocation -> latest.get());
			ChainHeadStateCache cache = new ChainHeadStateCache(persistence.factory(), chainQuery);
			cache.init();
			MempoolStore store = new MempoolStore(new SimpleMeterRegistry(), properties(100), cache,
					mock(ApplicationEventPublisher.class));
			store.addTransactions(List.of(mined, descendant), Map.of(ALICE, 0L),
					MempoolTxAddEvent.AddReason.SYNC);

			latest.set(stored(connectedBlock));
			cache.onBlockConnected(connectedEvent(connectedBlock));
			store.processNewBlock(connectedBlock.getTxs());

			assertThat(store.getTxByHash(mined.getHash())).isEmpty();
			assertThat(executable(store)).containsExactly(descendant);

			latest.set(stored(baseBlock));
			cache.onBlockConnected(connectedEvent(baseBlock));
			store.addTransactionsBack(connectedBlock.getTxs(), connectedBlock);

			assertThat(store.getAllTxs()).containsExactlyInAnyOrder(mined, descendant);
			assertThat(executable(store)).containsExactlyInAnyOrder(mined, descendant);
			assertThat(store.getNextAvailableNonce(ALICE, 0)).isEqualTo(3);
		}
	}

	@Test
	void persistedMultiBlockReorgRestoresTransactionsInReverseDisconnectOrder() throws Exception {
		try (PersistentWorldStateTestSupport persistence = new PersistentWorldStateTestSupport(databaseDirectory)) {
			Hash rootAtZero = persistedStateAtNonce(persistence, 0);
			Hash rootAtOne = persistedStateAtNonce(persistence, 1);
			Hash rootAtTwo = persistedStateAtNonce(persistence, 2);
			MempoolEntry first = transfer(20, ALICE, 1, 10);
			MempoolEntry second = transfer(21, ALICE, 2, 20);
			Block base = block(20, rootAtZero, List.of());
			Block firstBlock = block(21, rootAtOne, List.of(first.getTx()));
			Block secondBlock = block(22, rootAtTwo, List.of(second.getTx()));

			AtomicReference<StoredBlock> latest = new AtomicReference<>(stored(base));
			ChainQuery chainQuery = mock(ChainQuery.class);
			when(chainQuery.getLatestStoredBlockOrThrow()).thenAnswer(invocation -> latest.get());
			ChainHeadStateCache cache = new ChainHeadStateCache(persistence.factory(), chainQuery);
			cache.init();
			MempoolStore store = new MempoolStore(new SimpleMeterRegistry(), properties(100), cache,
					mock(ApplicationEventPublisher.class));
			store.addTransactions(List.of(first, second), Map.of(ALICE, 0L), MempoolTxAddEvent.AddReason.SYNC);

			latest.set(stored(firstBlock));
			cache.onBlockConnected(connectedEvent(firstBlock));
			store.processNewBlock(firstBlock.getTxs());
			latest.set(stored(secondBlock));
			cache.onBlockConnected(connectedEvent(secondBlock));
			store.processNewBlock(secondBlock.getTxs());
			assertThat(store.getAllTxs()).isEmpty();

			latest.set(stored(firstBlock));
			cache.onBlockConnected(connectedEvent(firstBlock));
			store.addTransactionsBack(secondBlock.getTxs(), secondBlock);
			assertThat(executable(store)).extracting(MempoolEntry::getHash).containsExactly(second.getHash());

			latest.set(stored(base));
			cache.onBlockConnected(connectedEvent(base));
			store.addTransactionsBack(firstBlock.getTxs(), firstBlock);

			assertThat(executable(store)).extracting(MempoolEntry::getHash)
					.containsExactlyInAnyOrder(first.getHash(), second.getHash());
			assertThat(store.getNextAvailableNonce(ALICE, 0)).isEqualTo(3);
		}
	}

	private Hash persistedStateAtNonce(PersistentWorldStateTestSupport persistence, long nonce) throws Exception {
		WorldState state = persistence.createEmpty(false);
		AccountNonceStateImpl nonceState = (AccountNonceStateImpl) AccountNonceStateImpl.ZERO;
		for (long current = -1; current < nonce; current++) {
			nonceState = nonceState.increaseNonce(1, BLOCK_TIME);
		}
		state.setNonce(ALICE, nonceState);
		return persistence.persist(state);
	}

	private Block block(long height, Hash stateRoot, List<Tx> transactions) {
		return BlockImpl.builder()
				.header(BlockHeaderImpl.builder()
						.version(BlockVersion.V1)
						.height(height)
						.timestamp(BLOCK_TIME.plusSeconds(height))
						.previousHash(Hash.ZERO)
						.txRootHash(TxRootUtil.txRootHash(transactions))
						.stateRootHash(stateRoot)
						.difficulty(BigInteger.ONE)
						.coinbase(Address.ZERO)
						.nonce(0)
						.signature(Signature.ZERO)
						.build())
				.txs(transactions)
				.build();
	}

	private StoredBlock stored(Block block) {
		StoredBlock stored = mock(StoredBlock.class);
		when(stored.getBlock()).thenReturn(block);
		return stored;
	}

	private BlockConnectedEvent connectedEvent(Block block) {
		return new BlockConnectedEvent(
				this, ConnectedSource.REORG, block,
				null, null, null, null, null,
				null, null, null, null, null, null,
				Wei.ZERO, Wei.ZERO, null, null, List.of(), null, null);
	}

	private List<MempoolEntry> executable(MempoolStore store) {
		List<MempoolEntry> entries = new ArrayList<>();
		store.getExecutableTransactionsIterator().forEachRemaining(entries::add);
		return entries;
	}
}
