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
package global.goldenera.node.core.blockchain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.config.CoreAsyncConfig;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolCanonicalJournalProjector;
import global.goldenera.node.core.mempool.MempoolCanonicalJournalWakeListener;
import global.goldenera.node.core.mempool.MempoolRecoveryGate;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.mempool.MempoolValidator;
import global.goldenera.node.core.mempool.MempoolValidator.MempoolValidationResult;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalCursor;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalEntry;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalHead;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalOperation;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalQuery;
import global.goldenera.node.core.storage.blockchain.journal.LifecycleJournalStream;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolCanonicalProjectionAdvance;
import global.goldenera.node.core.storage.blockchain.mempool.MempoolCanonicalProjectionCursor;
import global.goldenera.node.core.storage.blockchain.mempool.PersistentMempoolStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BlockConnectedEventOrderingIntegrationTest {

	@Test
	@Timeout(5)
	void dedicatedExecutorPreservesDisconnectThenConnectOrder() throws Exception {
		Tx disconnectedTx = mock(Tx.class);
		when(disconnectedTx.getHash()).thenReturn(Hash.fromHexString(
				"0x0000000000000000000000000000000000000000000000000000000000000041"));
		when(disconnectedTx.getFee()).thenReturn(Wei.ZERO);
		Block disconnected = mock(Block.class);
		when(disconnected.getHeight()).thenReturn(41L);
		when(disconnected.getTxs()).thenReturn(List.of(disconnectedTx));
		Block connected = mock(Block.class);
		when(connected.getHeight()).thenReturn(42L);
		when(connected.getTxs()).thenReturn(List.of());

		List<String> order = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch processed = new CountDownLatch(2);
		MempoolStore store = mock(MempoolStore.class);
		MempoolValidator validator = mock(MempoolValidator.class);
		doAnswer(invocation -> {
			order.add("disconnect");
			processed.countDown();
			return MempoolValidationResult.stateInvalid("disconnected tx not valid on the new head");
		}).when(validator).validateAgainstChainAndMempool(any(), any(), anyBoolean());
		doAnswer(invocation -> {
			order.add("connect");
			processed.countDown();
			return null;
		}).when(store).processNewBlock(anyList());

		SimpleMeterRegistry queueRegistry = new SimpleMeterRegistry();
		ThreadPoolTaskExecutor eventExecutor = new CoreAsyncConfig().mempoolEventExecutor(queueRegistry);
		MempoolManager manager = new MempoolManager(
				new SimpleMeterRegistry(), store, validator,
				new MempoolProperties(), mock(ChainHeadStateCache.class), eventExecutor,
				mock(ThreadPoolTaskScheduler.class));
		try {
			manager.onBlockDisconnected(new BlockDisconnectedEvent(this, disconnected));
			manager.onBlockConnected(connectedEvent(connected));

			assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(order).containsExactly("disconnect", "connect");
			assertThat(queueRegistry.find("blockchain.mempool.event_queue.size").gauge()).isNotNull();
			assertThat(queueRegistry.find("blockchain.mempool.event_queue.active").gauge()).isNotNull();
		} finally {
			eventExecutor.shutdown();
		}
	}

	@Test
	@Timeout(5)
	void springRefreshesChainHeadBeforeAsyncMempoolProcessingWithoutBlockingPublisher() throws Exception {
		Hash newRoot = Hash.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000042");
		BlockHeader header = mock(BlockHeader.class);
		when(header.getStateRootHash()).thenReturn(newRoot);
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		when(block.getHeight()).thenReturn(42L);
		when(block.getTxs()).thenReturn(List.of());
		Hash blockHash = Hash.fromHexString(
				"0x0000000000000000000000000000000000000000000000000000000000000043");
		Hash parentHash = Hash.fromHexString(
				"0x0000000000000000000000000000000000000000000000000000000000000041");
		when(block.getHash()).thenReturn(blockHash);

		WorldState refreshedState = mock(WorldState.class);
		when(refreshedState.getFinalStateRoot()).thenReturn(newRoot);
		WorldStateFactory factory = mock(WorldStateFactory.class);
		when(factory.createForValidation(newRoot)).thenReturn(refreshedState);
		StoredBlock storedBlock = mock(StoredBlock.class);
		when(storedBlock.getBlock()).thenReturn(block);
		when(storedBlock.getHeight()).thenReturn(42L);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestStoredBlockOrThrow()).thenReturn(storedBlock);
		when(chainQuery.getStoredBlockByHash(blockHash)).thenReturn(Optional.of(storedBlock));
		ChainHeadStateCache cache = new ChainHeadStateCache(factory, chainQuery);

		CountDownLatch processingStarted = new CountDownLatch(1);
		CountDownLatch allowCompletion = new CountDownLatch(1);
		CountDownLatch processingCompleted = new CountDownLatch(1);
		MempoolStore store = mock(MempoolStore.class);
		when(store.getCount()).thenReturn(0L);
		doAnswer(invocation -> {
			try {
				assertThat(cache.getHeadState()).isSameAs(refreshedState);
				processingStarted.countDown();
				assertThat(allowCompletion.await(3, TimeUnit.SECONDS)).isTrue();
			} finally {
				processingCompleted.countDown();
			}
			return null;
		}).when(store).processNewBlock(anyList());
		ThreadPoolTaskExecutor eventExecutor = new CoreAsyncConfig()
				.mempoolEventExecutor(new SimpleMeterRegistry());
		MempoolManager manager = new MempoolManager(
				new SimpleMeterRegistry(), store, mock(MempoolValidator.class),
				new MempoolProperties(), cache, eventExecutor, mock(ThreadPoolTaskScheduler.class));
		UUID epoch = UUID.randomUUID();
		LifecycleJournalEntry journalEntry = new LifecycleJournalEntry(
				LifecycleJournalEntry.CURRENT_VERSION, epoch, 1L, UUID.randomUUID(),
				LifecycleJournalStream.CANONICAL, LifecycleJournalOperation.CONNECT,
				null, 0, 1, 42L, blockHash, parentHash, Instant.now(),
				ConnectedSource.SYNC.getCode(), -1, null);
		LifecycleJournalQuery journal = mock(LifecycleJournalQuery.class);
		when(journal.head(LifecycleJournalStream.CANONICAL)).thenReturn(new LifecycleJournalHead(
				LifecycleJournalStream.CANONICAL, epoch, 1L, 1L, 42L, blockHash));
		when(journal.readAfter(
				any(LifecycleJournalStream.class), any(LifecycleJournalCursor.class), any(Integer.class)))
				.thenAnswer(invocation -> invocation.<LifecycleJournalCursor>getArgument(1).sequence() == 0L
						? List.of(journalEntry)
						: List.of());
		AtomicReference<MempoolCanonicalProjectionCursor> cursor = new AtomicReference<>(
				new MempoolCanonicalProjectionCursor(1, epoch, 0L));
		PersistentMempoolStore persistentMempool = mock(PersistentMempoolStore.class);
		when(persistentMempool.canonicalProjectionCursor()).thenAnswer(invocation -> Optional.of(cursor.get()));
		doAnswer(invocation -> {
			MempoolCanonicalProjectionAdvance advance = invocation.getArgument(0);
			invocation.<Runnable>getArgument(1).run();
			cursor.set(new MempoolCanonicalProjectionCursor(1, advance.epoch(), advance.newSequence()));
			return null;
		}).when(store).executeCanonicalPersistenceBatch(any(), any());
		MempoolRecoveryGate recoveryGate = mock(MempoolRecoveryGate.class);
		when(recoveryGate.isRecovered()).thenReturn(true);
		MempoolCanonicalJournalProjector projector = new MempoolCanonicalJournalProjector(
				journal, persistentMempool, chainQuery, store, manager, recoveryGate, eventExecutor);
		MempoolCanonicalJournalWakeListener wakeListener = new MempoolCanonicalJournalWakeListener(projector);

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(ChainHeadStateCache.class, () -> cache);
			context.registerBean(MempoolCanonicalJournalWakeListener.class, () -> wakeListener);
			context.refresh();
			context.publishEvent(new BlockConnectedEvent(
					this, ConnectedSource.SYNC, block,
					null, null, null, null, null,
					null, null, null, null, null, null,
					Wei.ZERO, Wei.ZERO, null, null, List.of(), null, null));

			assertThat(processingStarted.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(processingCompleted.getCount()).isEqualTo(1);
			allowCompletion.countDown();
			assertThat(processingCompleted.await(2, TimeUnit.SECONDS)).isTrue();
		} finally {
			allowCompletion.countDown();
			eventExecutor.shutdown();
		}
	}

	private BlockConnectedEvent connectedEvent(Block block) {
		return new BlockConnectedEvent(
				this, ConnectedSource.SYNC, block,
				null, null, null, null, null,
				null, null, null, null, null, null,
				Wei.ZERO, Wei.ZERO, null, null, List.of(), null, null);
	}
}
