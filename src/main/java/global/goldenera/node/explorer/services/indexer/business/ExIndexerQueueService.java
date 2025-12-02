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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ExIndexerQueueService {

	MeterRegistry registry;
	Deque<ExIndexerTask> queue = new ArrayDeque<>();
	ReentrantLock lock = new ReentrantLock();
	Condition notEmpty = lock.newCondition();

	@PostConstruct
	public void initMetrics() {
		registry.gaugeCollectionSize("explorer.queue.size", Tags.empty(), queue);
	}

	public void pushConnect(BlockConnectedEvent event) {
		lock.lock();
		try {
			ExIndexerTask lastTask = queue.peekLast();
			if (lastTask != null
					&& lastTask.getType() == ExIndexerTask.Type.DISCONNECT
					&& lastTask.getHash().equals(event.getBlock().getHash())) {

				queue.removeLast();
				log.info("Optimization: Skipped flickering (Disconnect->Connect) for block #{} in queue",
						event.getBlock().getHeight());
				return;
			}

			queue.addLast(new ExIndexerTask.ConnectTask(event));
			notEmpty.signal();
		} finally {
			lock.unlock();
		}
	}

	public void pushDisconnect(BlockDisconnectedEvent event) {
		lock.lock();
		try {
			ExIndexerTask lastTask = queue.peekLast();
			if (lastTask != null
					&& lastTask.getType() == ExIndexerTask.Type.CONNECT
					&& lastTask.getHash().equals(event.getBlock().getHash())) {

				queue.removeLast();
				log.info("Optimization: Skipped indexing/reverting block #{} (cancelled in queue)",
						event.getBlock().getHeight());
				return;
			}

			queue.addLast(new ExIndexerTask.DisconnectTask(event));
			notEmpty.signal();
		} finally {
			lock.unlock();
		}
	}

	public ExIndexerTask take() throws InterruptedException {
		lock.lock();
		try {
			while (queue.isEmpty()) {
				notEmpty.await();
			}
			return queue.pollFirst();
		} finally {
			lock.unlock();
		}
	}

	public int size() {
		lock.lock();
		try {
			return queue.size();
		} finally {
			lock.unlock();
		}
	}
}