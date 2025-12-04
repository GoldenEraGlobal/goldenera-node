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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

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
public class ExIndexerCoordinateService {

	private static final int MAX_RETRIES = 5;
	private static final long BASE_DELAY_MS = 1000;
	private static final long MAX_DELAY_MS = 10000;

	final GeneralProperties generalProperties;
	final MeterRegistry registry;
	final ExIndexerQueueService queueService;
	final ExIndexerService indexer;

	final Thread worker = new Thread(this::processQueue, "Explorer-Coordinator");
	final AtomicBoolean running = new AtomicBoolean(true);
	final AtomicBoolean panicMode = new AtomicBoolean(false);

	@PostConstruct
	public void start() {
		if (!generalProperties.isExplorerEnable()) {
			return;
		}
		worker.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception in Explorer Coordinator", e));
		worker.start();
	}

	@PreDestroy
	public void stop() {
		if (!generalProperties.isExplorerEnable()) {
			return;
		}
		running.set(false);
		worker.interrupt();
		try {
			worker.join(5000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Explorer Coordinator forced stop.");
		}
	}

	private void processQueue() {
		log.info("Explorer Coordinator started.");
		while (running.get()) {
			if (panicMode.get()) {
				log.error("Explorer is in PANIC MODE due to previous fatal errors. Processing suspended.");
				sleep(5000);
				continue;
			}

			try {
				// Blocking call, waits for a task
				ExIndexerTask task = queueService.take();

				if (task == null)
					continue; // Should not happen with blocking take, but safety check

				boolean success = processTaskWithRetryStrategy(task);
				if (!success) {
					triggerPanicMode(task);
				}

				logQueueStatus();

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.info("Explorer Coordinator interrupted, stopping.");
				break;
			} catch (Exception e) {
				log.error("Unexpected error in Explorer Coordinator loop", e);
			}
		}
		log.info("Explorer Coordinator stopped.");
	}

	private boolean processTaskWithRetryStrategy(ExIndexerTask task) {
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
		if (task instanceof ExIndexerTask.ConnectTask ct) {
			indexer.handleBlockConnected(ct.getEvent());
		} else if (task instanceof ExIndexerTask.DisconnectTask dt) {
			indexer.handleBlockDisconnected(dt.getEvent());
		} else {
			throw new IllegalArgumentException("Unknown task type: " + task.getClass().getName());
		}
	}

	private void triggerPanicMode(ExIndexerTask task) {
		panicMode.set(true);
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