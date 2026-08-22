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
package global.goldenera.node.explorer.snapshot;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import global.goldenera.node.explorer.services.indexer.business.ExIndexerQueueService;
import global.goldenera.node.explorer.services.indexer.business.ExplorerIndexingExecutionGate;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;

/** Owns the optional rebuild worker and defers startup until singleton initialization is complete. */
public final class ExplorerArchiveRebuildLauncher
		implements ExplorerArchiveRebuildTrigger, SmartInitializingSingleton, DisposableBean {

	private static final String REBUILD_DETAIL =
			"Explorer is rebuilding from the verified local canonical archive";

	private final Object lifecycleMonitor = new Object();
	private final GeneralProperties generalProperties;
	private final ExplorerRuntimeReadiness readiness;
	private final ObjectProvider<ExplorerArchiveReplayEngine> replayEngineProvider;
	private final ObjectProvider<ExplorerIndexingExecutionGate> executionGateProvider;
	private final ObjectProvider<ExIndexerQueueService> queueServiceProvider;

	private boolean singletonInitializationComplete;
	private boolean deferredRequest;
	private ExplorerArchiveRebuildService rebuildService;

	public ExplorerArchiveRebuildLauncher(
			GeneralProperties generalProperties,
			ExplorerRuntimeReadiness readiness,
			ObjectProvider<ExplorerArchiveReplayEngine> replayEngineProvider,
			ObjectProvider<ExplorerIndexingExecutionGate> executionGateProvider,
			ObjectProvider<ExIndexerQueueService> queueServiceProvider) {
		this.generalProperties = generalProperties;
		this.readiness = readiness;
		this.replayEngineProvider = replayEngineProvider;
		this.executionGateProvider = executionGateProvider;
		this.queueServiceProvider = queueServiceProvider;
	}

	@Override
	public void request() {
		synchronized (lifecycleMonitor) {
			if (!generalProperties.isExplorerEnable()) {
				return;
			}
			if (!singletonInitializationComplete) {
				deferredRequest = true;
				readiness.rebuilding(REBUILD_DETAIL);
				return;
			}
		}
		startRebuild();
	}

	@Override
	public void afterSingletonsInstantiated() {
		boolean launch;
		synchronized (lifecycleMonitor) {
			singletonInitializationComplete = true;
			launch = deferredRequest;
			deferredRequest = false;
		}
		if (launch) {
			startRebuild();
		}
	}

	@Override
	public void destroy() {
		ExplorerArchiveRebuildService service;
		synchronized (lifecycleMonitor) {
			service = rebuildService;
			rebuildService = null;
		}
		if (service != null) {
			service.destroy();
		}
	}

	boolean hasCreatedWorker() {
		synchronized (lifecycleMonitor) {
			return rebuildService != null;
		}
	}

	private void startRebuild() {
		worker().start();
	}

	private ExplorerArchiveRebuildService worker() {
		synchronized (lifecycleMonitor) {
			if (rebuildService == null) {
				rebuildService = new ExplorerArchiveRebuildService(
						generalProperties,
						readiness,
						replayEngineProvider.getObject(),
						executionGateProvider.getObject(),
						queueServiceProvider.getIfAvailable());
			}
			return rebuildService;
		}
	}
}
