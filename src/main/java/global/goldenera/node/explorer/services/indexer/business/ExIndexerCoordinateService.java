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
package global.goldenera.node.explorer.services.indexer.business;

import static lombok.AccessLevel.PRIVATE;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerReadinessListener;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerReadinessStatus;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveRebuildTrigger;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE)
public class ExIndexerCoordinateService implements ExplorerReadinessListener {

	private static final int MAX_RETRIES = 5;
	private static final long BASE_DELAY_MS = 1000;
	private static final long MAX_DELAY_MS = 10000;

	final GeneralProperties generalProperties;
	final ExplorerRuntimeReadiness explorerReadiness;
	final MeterRegistry registry;
	final ExIndexerQueueService queueService;
	final ExIndexerService indexer;
	final ExplorerIndexingExecutionGate executionGate;
	final ExplorerArchiveRebuildTrigger archiveRebuildTrigger;

	final AtomicReference<WorkerControl> activeWorker = new AtomicReference<>();
	final AtomicBoolean panicMode = new AtomicBoolean(false);
	final AtomicBoolean shuttingDown = new AtomicBoolean();

	@PostConstruct
	public void start() {
		shuttingDown.set(false);
		explorerReadiness.registerListener(this);
	}

	@PreDestroy
	public void stop() {
		shuttingDown.set(true);
		explorerReadiness.unregisterListener(this);
		WorkerControl worker = activeWorker.getAndSet(null);
		if (worker == null) {
			return;
		}
		worker.running().set(false);
		worker.thread().interrupt();
		try {
			worker.thread().join(5000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Explorer Coordinator forced stop.");
		}
	}

	@Override
	public void onReadinessChanged(ExplorerReadinessStatus status) {
		if (status.ready()) {
			startWorkerIfNeeded();
		}
	}

	private void startWorkerIfNeeded() {
		if (shuttingDown.get() || !generalProperties.isExplorerEnable() || !explorerReadiness.isReady()) {
			return;
		}
		while (true) {
			WorkerControl current = activeWorker.get();
			if (current != null && current.running().get() && current.thread().isAlive()) {
				return;
			}
			WorkerControl replacement = new WorkerControl();
			replacement.thread().setUncaughtExceptionHandler(
					(t, failure) -> log.error("Uncaught exception in Explorer Coordinator", failure));
			if (activeWorker.compareAndSet(current, replacement)) {
				panicMode.set(false);
				replacement.thread().start();
				return;
			}
		}
	}

	private void processQueue(WorkerControl worker) {
		log.info("Explorer Coordinator started.");
		try {
			while (worker.running().get()) {
				if (panicMode.get()) {
					log.error("Explorer is in PANIC MODE due to previous fatal errors. Processing suspended.");
					sleep(5000);
					continue;
				}

				// Blocking call, waits for a task
				ExIndexerTask task = queueService.take();

				if (task == null)
					continue; // Should not happen with blocking take, but safety check

				if (!explorerReadiness.isReady()) {
					continue;
				}
				boolean success = processTaskWithRetryStrategy(task, worker.running());
				if (!success) {
					triggerPanicMode(task, worker.running());
				}

				logQueueStatus();

			}
		} catch (InterruptedException e) {
			if (!shuttingDown.get()) {
				log.warn("Explorer Coordinator exited after an unexpected interrupt");
			} else {
				log.info("Explorer Coordinator interrupted, stopping.");
			}
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			log.error("Unexpected error in Explorer Coordinator loop", e);
		} finally {
			activeWorker.compareAndSet(worker, null);
			log.info("Explorer Coordinator stopped.");
			if (!shuttingDown.get() && worker.running().get() && explorerReadiness.isReady()) {
				startWorkerIfNeeded();
			}
		}
	}

	private boolean processTaskWithRetryStrategy(ExIndexerTask task, AtomicBoolean running) {
		int attempt = 0;
		long currentDelay = BASE_DELAY_MS;

		while (attempt < MAX_RETRIES && running.get()) {
			try {
				processTask(task);
				return true;
			} catch (Exception e) {
				attempt++;
				registry.counter("explorer.coordinator.retry").increment();
				log.error("Failed to process block #{} (Hash: {}). Attempt {}/{}. Error: {}",
						task.getHeight(), task.getHash(), attempt, MAX_RETRIES, e.getMessage());

				if (attempt >= MAX_RETRIES) {
					log.error("Max retries exhausted for block #{}.", task.getHeight(), e);
					return false;
				}

				sleep(currentDelay);
				currentDelay = Math.min(currentDelay * 2, MAX_DELAY_MS);
			}
		}
		return false;
	}

	private void processTask(ExIndexerTask task) {
		executionGate.run(() -> {
			if (!explorerReadiness.isReady()) {
				return;
			}
			if (task instanceof ExIndexerTask.ConnectTask ct) {
				indexer.handleBlockConnected(ct.getEvent());
			} else if (task instanceof ExIndexerTask.ConnectBatchTask batchTask) {
				processConnectBatch(batchTask);
			} else if (task instanceof ExIndexerTask.DisconnectTask dt) {
				indexer.handleBlockDisconnected(dt.getEvent());
			} else {
				throw new IllegalArgumentException("Unknown task type: " + task.getClass().getName());
			}
			if (!(task instanceof ExIndexerTask.ConnectBatchTask)) {
				queueService.markTaskProcessed();
			}
		});
	}

	private void processConnectBatch(ExIndexerTask.ConnectBatchTask task) {
		List<BlockConnectedEvent> events = task.getEvents();
		for (int start = 0; start < events.size(); start += ExIndexerSyncService.CATCH_UP_BATCH_SIZE) {
			int end = Math.min(start + ExIndexerSyncService.CATCH_UP_BATCH_SIZE, events.size());
			indexer.handleBlockConnectedBatch(events.subList(start, end));
		}
		queueService.markTasksProcessed(events.size());
	}

	private void triggerPanicMode(ExIndexerTask task, AtomicBoolean running) {
		panicMode.set(true);
		running.set(false);
		queueService.discardForRebuild("coordinator panic");
		archiveRebuildTrigger.request();
		registry.counter("explorer.coordinator.panic").increment();
		log.error("################################################################");
		log.error("CRITICAL EXPLORER FAILURE: POISON BLOCK DETECTED");
		log.error("Block Height: {}", task.getHeight());
		log.error("Block Hash:   {}", task.getHash());
		log.error("Action:       {}", task.getType());
		log.error("The Explorer has stopped processing to prevent database corruption.");
		log.error("Manual intervention required. Check logs, fix the issue, and restart.");
		log.error("################################################################");
		throw new GEFailedException("Explorer entered PANIC MODE at block " + task.getHeight());
	}

	private final class WorkerControl {
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final Thread thread = new Thread(() -> processQueue(this), "Explorer-Coordinator");

		Thread thread() {
			return thread;
		}

		AtomicBoolean running() {
			return running;
		}
	}

	private void logQueueStatus() {
		int size = queueService.size();
		// Only log periodically to avoid log spam, no sleeping - let it process as fast
		// as possible
		if (size > 0 && size % 1000 == 0) {
			log.info("Explorer Queue status: {} pending blocks", size);
		}
	}

	private void sleep(long millis) {
		try {
			TimeUnit.MILLISECONDS.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
