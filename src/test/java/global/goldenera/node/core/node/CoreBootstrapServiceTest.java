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
package global.goldenera.node.core.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.node.core.blockchain.events.CoreDbReadyEvent;
import global.goldenera.node.core.blockchain.events.CoreReadyEvent;
import global.goldenera.node.core.blockchain.genesis.GenesisInitializer;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.mempool.MempoolStartupRecoveryCoordinator;
import global.goldenera.node.core.node.readiness.CoreReadinessFailureReason;
import global.goldenera.node.core.node.readiness.CoreReadinessStage;
import global.goldenera.node.core.node.readiness.CoreMilestonePublisher;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadinessTracker;
import global.goldenera.node.core.p2p.manager.NodeConnectionManager;
import global.goldenera.node.core.p2p.netty.NettyP2PServer;
import global.goldenera.node.core.p2p.netty.P2PServerBindException;
import global.goldenera.node.core.p2p.services.DirectoryService;
import global.goldenera.node.core.processing.MiningEconomicsActivationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.BlockSyncManagerService;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerSyncService;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerSyncStartupListener;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;

class CoreBootstrapServiceTest {

	@Test
	void startsP2pOnlyAfterIdentityGenesisAndHeadAreReady() throws Exception {
		Fixture fixture = new Fixture();

		fixture.service.onApplicationReady(mock(ApplicationReadyEvent.class));

		InOrder order = inOrder(fixture.identity, fixture.genesis, fixture.headState,
				fixture.activation, fixture.mempoolRecovery, fixture.p2p, fixture.connections, fixture.sync,
				fixture.directory, fixture.milestones);
		order.verify(fixture.identity).identity();
		order.verify(fixture.genesis).checkAndInitGenesisBlock();
		order.verify(fixture.headState).init();
		order.verify(fixture.activation).assertHeadReady(isNull(), eq(42L));
		order.verify(fixture.mempoolRecovery).recover();
		order.verify(fixture.milestones).publish(any(CoreDbReadyEvent.class));
		order.verify(fixture.p2p).start();
		order.verify(fixture.connections).start();
		order.verify(fixture.sync).start();
		order.verify(fixture.directory).start(19000);
		order.verify(fixture.milestones).publish(any(CoreReadyEvent.class));
		assertThat(fixture.readiness.status().stage()).isEqualTo(CoreReadinessStage.CORE_READY);
		assertThat(fixture.readiness.isReady()).isTrue();
	}

	@Test
	void bindFailureFailsStartupAndPreventsConnectionAndSyncLoops() {
		Fixture fixture = new Fixture();
		when(fixture.p2p.start()).thenThrow(new P2PServerBindException("occupied", null));

		assertThatThrownBy(() -> fixture.service.onApplicationReady(mock(ApplicationReadyEvent.class)))
				.isInstanceOf(CoreBootstrapException.class)
				.hasMessageContaining("P2P listener");

		assertThat(fixture.readiness.status().stage()).isEqualTo(CoreReadinessStage.FAILED);
		assertThat(fixture.readiness.status().failureReason()).isEqualTo(CoreReadinessFailureReason.P2P_BIND);
		verify(fixture.connections, never()).start();
		verify(fixture.sync, never()).start();
		verify(fixture.directory, never()).start(anyInt());
	}

	@Test
	void identityFailureIsTypedAndPreventsGenesisAndP2p() throws Exception {
		Fixture fixture = new Fixture();
		when(fixture.identity.identity()).thenThrow(new IllegalStateException("identity mismatch"));

		assertThatThrownBy(() -> fixture.service.onApplicationReady(mock(ApplicationReadyEvent.class)))
				.isInstanceOf(CoreBootstrapException.class)
				.hasMessageContaining("chain identity");

		assertThat(fixture.readiness.status().failureReason())
				.isEqualTo(CoreReadinessFailureReason.CHAIN_IDENTITY);
		verify(fixture.genesis, never()).checkAndInitGenesisBlock();
		verify(fixture.p2p, never()).start();
	}

	@Test
	void genesisFailureIsTypedAndPreventsP2pAndReadyEvent() throws Exception {
		Fixture fixture = new Fixture();
		doThrow(new IllegalStateException("genesis mismatch"))
				.when(fixture.genesis).checkAndInitGenesisBlock();

		assertThatThrownBy(() -> fixture.service.onApplicationReady(mock(ApplicationReadyEvent.class)))
				.isInstanceOf(CoreBootstrapException.class)
				.hasMessageContaining("database initialization");

		assertThat(fixture.readiness.status().failureReason())
				.isEqualTo(CoreReadinessFailureReason.GENESIS_HEAD);
		verify(fixture.p2p, never()).start();
		verify(fixture.milestones, never()).publish(any(CoreReadyEvent.class));
	}

	@Test
	void mempoolRecoveryFailureIsTypedAndPreventsP2pAndPeerSync() throws Exception {
		Fixture fixture = new Fixture();
		doThrow(new IllegalStateException("corrupt persisted mempool"))
				.when(fixture.mempoolRecovery).recover();

		assertThatThrownBy(() -> fixture.service.onApplicationReady(mock(ApplicationReadyEvent.class)))
				.isInstanceOf(CoreBootstrapException.class)
				.hasMessageContaining("mempool recovery");

		assertThat(fixture.readiness.status().failureReason())
				.isEqualTo(CoreReadinessFailureReason.MEMPOOL_RECOVERY);
		verify(fixture.p2p, never()).start();
		verify(fixture.connections, never()).start();
		verify(fixture.sync, never()).start();
		verify(fixture.milestones, never()).publish(any(CoreDbReadyEvent.class));
	}

	@Test
	void slowFailingExplorerAndThrowingCoreReadyObserversCannotBlockOrFailCore() throws Exception {
		CountDownLatch explorerEntered = new CountDownLatch(1);
		CountDownLatch releaseExplorer = new CountDownLatch(1);
		CountDownLatch coreReadyObserverCalled = new CountDownLatch(1);
		GeneralProperties generalProperties = new GeneralProperties();
		generalProperties.setExplorerEnable(true);
		ExplorerRuntimeReadiness explorerReadiness = mock(ExplorerRuntimeReadiness.class);
		when(explorerReadiness.isReady()).thenReturn(true);
		ExIndexerSyncService explorerWorker = mock(ExIndexerSyncService.class);
		doAnswer(invocation -> {
			explorerEntered.countDown();
			releaseExplorer.await(2, TimeUnit.SECONDS);
			throw new IllegalStateException("postgres unavailable");
		}).when(explorerWorker).syncExplorerOnStartup();
		@SuppressWarnings("unchecked")
		ObjectProvider<ExIndexerSyncService> workerProvider = mock(ObjectProvider.class);
		when(workerProvider.getObject()).thenReturn(explorerWorker);
		ExIndexerSyncStartupListener realExplorerListener = new ExIndexerSyncStartupListener(
				generalProperties, explorerReadiness, workerProvider);
		ApplicationEventPublisher observers = event -> {
			if (event instanceof CoreDbReadyEvent dbReadyEvent) {
				realExplorerListener.onCoreReady(dbReadyEvent);
			}
			if (event instanceof CoreReadyEvent) {
				coreReadyObserverCalled.countDown();
				throw new IllegalStateException("webhook SQL failure");
			}
		};
		ThreadPoolExecutor executor = new ThreadPoolExecutor(
				1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
				new ThreadPoolExecutor.AbortPolicy());
		try {
			Fixture fixture = new Fixture(new CoreMilestonePublisher(observers, executor));

			fixture.service.onApplicationReady(mock(ApplicationReadyEvent.class));

			assertThat(explorerEntered.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(fixture.readiness.isReady()).isTrue();
			verify(fixture.p2p).start();
			verify(fixture.directory).start(19000);
			releaseExplorer.countDown();
			assertThat(coreReadyObserverCalled.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(fixture.readiness.status().stage()).isEqualTo(CoreReadinessStage.CORE_READY);
		} finally {
			releaseExplorer.countDown();
			executor.shutdownNow();
		}
	}

	private static final class Fixture {

		private final CoreMilestonePublisher milestones;
		private final DirectoryService directory = mock(DirectoryService.class);
		private final ChainHeadStateCache headState = mock(ChainHeadStateCache.class);
		private final BlockSyncManagerService sync = mock(BlockSyncManagerService.class);
		private final GenesisInitializer genesis = mock(GenesisInitializer.class);
		private final NodeConnectionManager connections = mock(NodeConnectionManager.class);
		private final NettyP2PServer p2p = mock(NettyP2PServer.class);
		private final ChainQuery chain = mock(ChainQuery.class);
		private final MiningEconomicsActivationService activation = mock(MiningEconomicsActivationService.class);
		private final AuthoritativeChainIdentityProvider identity = mock(AuthoritativeChainIdentityProvider.class);
		private final CoreRuntimeReadinessTracker readiness = new CoreRuntimeReadinessTracker();
		private final MempoolStartupRecoveryCoordinator mempoolRecovery = mock(MempoolStartupRecoveryCoordinator.class);
		private final CoreBootstrapService service;

		private Fixture() {
			this(mock(CoreMilestonePublisher.class));
		}

		private Fixture(CoreMilestonePublisher milestones) {
			this.milestones = milestones;
			StoredBlock head = mock(StoredBlock.class);
			when(head.getHeight()).thenReturn(42L);
			when(chain.getLatestStoredBlockOrThrow()).thenReturn(head);
			when(identity.identity()).thenReturn(new StoredChainIdentity(
					1, 0, "mainnet", "0x" + "01".repeat(32), null));
			when(p2p.start()).thenReturn(19000);
			doAnswer(invocation -> {
				assertThat(readiness.isReady()).isTrue();
				return true;
			}).when(directory).start(anyInt());
			service = new CoreBootstrapService(
					milestones,
					directory,
					headState,
					sync,
					genesis,
					connections,
					p2p,
					chain,
					activation,
					identity,
					readiness,
					mempoolRecovery);
		}
	}
}
