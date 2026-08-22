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

import static lombok.AccessLevel.PRIVATE;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.events.CoreDbReadyEvent;
import global.goldenera.node.core.blockchain.events.CoreReadyEvent;
import global.goldenera.node.core.blockchain.genesis.GenesisInitializer;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.readiness.CoreReadinessFailureReason;
import global.goldenera.node.core.node.readiness.CoreMilestonePublisher;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadinessTracker;
import global.goldenera.node.core.p2p.manager.NodeConnectionManager;
import global.goldenera.node.core.p2p.netty.NettyP2PServer;
import global.goldenera.node.core.p2p.services.DirectoryService;
import global.goldenera.node.core.processing.MiningEconomicsActivationService;
import global.goldenera.node.core.storage.chainidentity.AuthoritativeChainIdentityProvider;
import global.goldenera.node.core.sync.BlockSyncManagerService;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@ConditionalOnProperty(
		prefix = "ge.snapshot.operator", name = "suppress-runtime", havingValue = "false", matchIfMissing = true)
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class CoreBootstrapService {

	CoreMilestonePublisher milestonePublisher;
	DirectoryService p2pClientService;
	ChainHeadStateCache chainHeadStateCache;
	BlockSyncManagerService syncManagerService;
	GenesisInitializer blockGenesisService;
	NodeConnectionManager nodeConnectionManager;
	NettyP2PServer p2pServer;
	ChainQuery chainQuery;
	MiningEconomicsActivationService miningEconomicsActivationService;
	AuthoritativeChainIdentityProvider chainIdentityProvider;
	CoreRuntimeReadinessTracker readiness;

	@EventListener
	public void onApplicationReady(ApplicationReadyEvent event) {
		log.info("CORE: Starting core initialization...");
		verifyChainIdentity();
		initializationDb();

		milestonePublisher.publish(new CoreDbReadyEvent(this));

		int boundPort = initializeRuntime();
		readiness.coreReady();
		startDirectory(boundPort);
		log.info("CORE: Core initialization successful. Publishing CoreReadyEvent.");
		milestonePublisher.publish(new CoreReadyEvent(this));
	}

	private void verifyChainIdentity() {
		try {
			chainIdentityProvider.identity();
			readiness.chainIdentityBound();
		} catch (Exception e) {
			readiness.failed(CoreReadinessFailureReason.CHAIN_IDENTITY);
			log.error("CORE IDENTITY: Authoritative chain identity verification failed: {}", e.getMessage());
			throw new CoreBootstrapException("Core chain identity initialization failed", e);
		}
	}

	private void initializationDb() {
		try {
			blockGenesisService.checkAndInitGenesisBlock();
			chainHeadStateCache.init();
			miningEconomicsActivationService.assertHeadReady(
					chainHeadStateCache.getHeadState(), chainQuery.getLatestStoredBlockOrThrow().getHeight());
			readiness.genesisHeadReady();
		} catch (Exception e) {
			readiness.failed(CoreReadinessFailureReason.GENESIS_HEAD);
			log.error("CORE DB: Core initialization failed: {}", e.getMessage());
			throw new CoreBootstrapException("Core database initialization failed", e);
		}
	}

	private int initializeRuntime() {
		int boundPort;
		try {
			boundPort = p2pServer.start();
			readiness.p2pListenerBound();
		} catch (Exception e) {
			readiness.failed(CoreReadinessFailureReason.P2P_BIND);
			log.error("CORE P2P: Listener bind failed: {}", e.getMessage());
			throw new CoreBootstrapException("Core P2P listener initialization failed", e);
		}
		try {
			nodeConnectionManager.start();
			syncManagerService.start();
		} catch (Exception e) {
			readiness.failed(CoreReadinessFailureReason.CORE_RUNTIME);
			log.error("CORE: Core initialization failed: {}", e.getMessage());
			throw new CoreBootstrapException("Core runtime initialization failed", e);
		}
		return boundPort;
	}

	private void startDirectory(int boundPort) {
		try {
			p2pClientService.start(boundPort);
		} catch (Exception e) {
			log.warn("CORE DIRECTORY: Optional directory lifecycle failed: {}", e.getMessage());
		}
	}

}
