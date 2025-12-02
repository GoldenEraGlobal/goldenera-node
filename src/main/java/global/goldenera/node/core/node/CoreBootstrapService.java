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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.events.CoreDbReadyEvent;
import global.goldenera.node.core.blockchain.events.CoreReadyEvent;
import global.goldenera.node.core.blockchain.genesis.GenesisInitializer;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.p2p.manager.NodeConnectionManager;
import global.goldenera.node.core.p2p.services.DirectoryService;
import global.goldenera.node.core.sync.BlockSyncManagerService;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class CoreBootstrapService {

	ApplicationEventPublisher applicationEventPublisher;
	DirectoryService p2pClientService;
	ChainHeadStateCache chainHeadStateCache;
	BlockSyncManagerService syncManagerService;
	GenesisInitializer blockGenesisService;
	NodeConnectionManager nodeConnectionManager;

	@EventListener
	public void onApplicationReady(ApplicationReadyEvent event) {
		log.info("CORE: Starting core initialization...");
		if (!initializationDbSuccessful()) {
			log.error("CORE DB: Core initialization failed: Database initialization failed");
			System.exit(1);
		}

		applicationEventPublisher.publishEvent(new CoreDbReadyEvent(this));

		if (initializationSuccessful()) {
			log.info("CORE: Core initialization successful. Publishing CoreReadyEvent.");
			applicationEventPublisher.publishEvent(new CoreReadyEvent(this));
		} else {
			System.exit(1);
		}
	}

	private boolean initializationDbSuccessful() {
		try {
			blockGenesisService.checkAndInitGenesisBlock();
			chainHeadStateCache.init();
		} catch (Exception e) {
			e.printStackTrace();
			log.error("CORE DB: Core initialization failed: {}", e.getMessage());
			return false;
		}
		return true;
	}

	private boolean initializationSuccessful() {
		// Ignore errors from ping directory
		try {
			p2pClientService.pingDirectory();
		} catch (Exception e) {
		}
		try {
			nodeConnectionManager.init();
			syncManagerService.start();
		} catch (Exception e) {
			e.printStackTrace();
			log.error("CORE: Core initialization failed: {}", e.getMessage());
			return false;
		}
		return true;
	}

}
