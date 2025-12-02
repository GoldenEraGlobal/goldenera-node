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

import org.springframework.stereotype.Service;

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

	final ExIndexerQueueService queueService;
	final ExIndexerService indexer;

	final Thread worker = new Thread(this::processQueue, "Explorer-Coordinator");
	volatile boolean running = true;

	@PostConstruct
	public void start() {
		worker.start();
	}

	@PreDestroy
	public void stop() {
		running = false;
		worker.interrupt();
	}

	private void processQueue() {
		log.info("Explorer Coordinator started.");
		while (running) {
			try {
				ExIndexerTask task = queueService.take();
				processTask(task);
				if (queueService.size() > 1000) {
					log.warn("Explorer Queue is lagging! Pending blocks: {}", queueService.size());
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.error("Unexpected error in Explorer Coordinator loop", e);
			}
		}
	}

	private void processTask(ExIndexerTask task) {
		try {
			if (task instanceof ExIndexerTask.ConnectTask ct) {
				indexer.handleBlockConnected(ct.getEvent());
			} else if (task instanceof ExIndexerTask.DisconnectTask dt) {
				indexer.handleBlockDisconnected(dt.getEvent());
			}
		} catch (Exception e) {
			log.error("Failed to process task for block #{}", task.getHeight(), e);
		}
	}
}