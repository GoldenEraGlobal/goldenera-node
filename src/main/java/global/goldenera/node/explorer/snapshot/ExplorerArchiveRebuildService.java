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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.DisposableBean;

import global.goldenera.node.explorer.storage.chainidentity.ExplorerReadinessState;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.explorer.services.indexer.business.ExIndexerQueueService;
import global.goldenera.node.explorer.services.indexer.business.ExplorerIndexingExecutionGate;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.extern.slf4j.Slf4j;

/** Runs at most one bounded local-archive explorer rebuild in the background. */
@Slf4j
public final class ExplorerArchiveRebuildService implements DisposableBean {
	private static final int MAX_CANONICAL_RESTARTS = 16;

	private final GeneralProperties generalProperties;
	private final ExplorerRuntimeReadiness readiness;
	private final ExplorerArchiveReplayEngine replayEngine;
	private final ExecutorService executor;
	private final ExplorerIndexingExecutionGate executionGate;
	private final ExIndexerQueueService queueService;
	private final AtomicBoolean running = new AtomicBoolean();

	public ExplorerArchiveRebuildService(
			GeneralProperties generalProperties,
			ExplorerRuntimeReadiness readiness,
			ExplorerArchiveReplayEngine replayEngine,
			ExplorerIndexingExecutionGate executionGate,
			ExIndexerQueueService queueService) {
		this(generalProperties, readiness, replayEngine,
				Executors.newSingleThreadExecutor(runnable -> {
					Thread thread = new Thread(runnable, "explorer-archive-rebuild");
					thread.setDaemon(true);
					thread.setPriority(Thread.MIN_PRIORITY);
					return thread;
				}), executionGate, queueService);
	}

	ExplorerArchiveRebuildService(
			GeneralProperties generalProperties,
			ExplorerRuntimeReadiness readiness,
			ExplorerArchiveReplayEngine replayEngine,
			ExecutorService executor) {
		this(generalProperties, readiness, replayEngine, executor,
				new ExplorerIndexingExecutionGate(), null);
	}

	ExplorerArchiveRebuildService(
			GeneralProperties generalProperties,
			ExplorerRuntimeReadiness readiness,
			ExplorerArchiveReplayEngine replayEngine,
			ExecutorService executor,
			ExplorerIndexingExecutionGate executionGate,
			ExIndexerQueueService queueService) {
		this.generalProperties = generalProperties;
		this.readiness = readiness;
		this.replayEngine = replayEngine;
		this.executor = executor;
		this.executionGate = executionGate;
		this.queueService = queueService;
	}

	public void start() {
		if (!generalProperties.isExplorerEnable() || !running.compareAndSet(false, true)) {
			return;
		}
		readiness.rebuilding("Explorer is rebuilding from the verified local canonical archive");
		log.info("EXPLORER SNAPSHOT: REBUILDING from verified local canonical archive");
		executor.execute(() -> {
			try {
				executionGate.run(this::rebuildAcrossCanonicalChanges);
				if (queueService != null) {
					queueService.markRebuildComplete();
				}
				readiness.ready();
				log.info("EXPLORER SNAPSHOT: local canonical archive rebuild READY");
			} catch (Exception e) {
				readiness.failed(ExplorerReadinessState.STORAGE_CORRUPT,
						"Explorer archive rebuild failed: " + rootMessage(e));
				log.error("EXPLORER SNAPSHOT: local canonical archive rebuild failed", e);
			} finally {
				running.set(false);
			}
		});
	}

	private void rebuildAcrossCanonicalChanges() {
		for (int attempt = 1; attempt <= MAX_CANONICAL_RESTARTS; attempt++) {
			try {
				replayEngine.rebuildToCanonicalHead();
				return;
			} catch (ExplorerCanonicalArchiveChangedException e) {
				if (attempt == MAX_CANONICAL_RESTARTS) {
					throw e;
				}
				log.info("EXPLORER SNAPSHOT: canonical archive changed during rebuild; retrying ({}/{})",
						attempt, MAX_CANONICAL_RESTARTS);
				Thread.yield();
			}
		}
	}

	public boolean isRunning() {
		return running.get();
	}

	@Override
	public void destroy() {
		executor.shutdownNow();
	}

	private String rootMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}
}
