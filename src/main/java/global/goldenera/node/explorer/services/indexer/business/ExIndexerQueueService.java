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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
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

	static final int MAX_QUEUE_CAPACITY = 64;

	GeneralProperties generalProperties;
	ExplorerRuntimeReadiness explorerReadiness;
	MeterRegistry registry;
	Deque<ExIndexerTask> queue = new ArrayDeque<>(MAX_QUEUE_CAPACITY);
	AtomicLong catchUpLagBlocks = new AtomicLong();

	ReentrantLock lock = new ReentrantLock(true);
	Condition notEmpty = lock.newCondition();

	@PostConstruct
	public void initMetrics() {
		if (!enabled()) {
			return;
		}
		registry.gaugeCollectionSize("explorer.queue.size", Tags.empty(), queue);
		registry.gauge("explorer.catchup.lag.blocks", catchUpLagBlocks);
	}

	public BatchAdmission pushConnect(BlockConnectedEvent event) {
		if (!enabled()) {
			return BatchAdmission.IGNORED;
		}
		lock.lock();
		try {
			// Optimization: Remove immediate Disconnect-Connect flicker
			ExIndexerTask lastTask = queue.peekLast();
			if (lastTask != null
					&& lastTask.getType() == ExIndexerTask.Type.DISCONNECT
					&& lastTask.getHash().equals(event.getBlock().getHash())) {

				queue.removeLast();
				markTaskProcessed();
				log.debug("Optimization: Skipped flickering (Disconnect->Connect) for block #{}",
						event.getBlock().getHeight());
				return BatchAdmission.ENQUEUED;
			}

			if (queue.size() >= MAX_QUEUE_CAPACITY) {
				return overflowToRebuild(1, "live connect");
			}

			queue.addLast(new ExIndexerTask.ConnectTask(event));
			catchUpLagBlocks.incrementAndGet();
			notEmpty.signalAll();
			return BatchAdmission.ENQUEUED;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Atomically enqueues a committed sync batch without waiting for explorer
	 * capacity. Overflow abandons the stale live queue and requests a canonical
	 * archive rebuild so the core sync publisher can return immediately.
	 */
	public BatchAdmission pushConnectBatch(List<BlockConnectedEvent> events) {
		if (!enabled() || events.isEmpty()) {
			return BatchAdmission.IGNORED;
		}
		lock.lock();
		try {
			if (queue.size() >= MAX_QUEUE_CAPACITY) {
				return overflowToRebuild(events.size(), "sync batch");
			}
			queue.addLast(new ExIndexerTask.ConnectBatchTask(events));
			catchUpLagBlocks.addAndGet(events.size());
			notEmpty.signalAll();
			return BatchAdmission.ENQUEUED;
		} finally {
			lock.unlock();
		}
	}

	public BatchAdmission pushDisconnect(BlockDisconnectedEvent event) {
		if (!enabled()) {
			return BatchAdmission.IGNORED;
		}
		lock.lock();
		try {
			// Optimization: Remove immediate Connect-Disconnect flicker
			ExIndexerTask lastTask = queue.peekLast();
			if (lastTask != null
					&& lastTask.getType() == ExIndexerTask.Type.CONNECT
					&& lastTask.getHash().equals(event.getBlock().getHash())) {

				queue.removeLast();
				markTaskProcessed();
				log.debug("Optimization: Skipped indexing/reverting block #{} (cancelled in queue)",
						event.getBlock().getHeight());
				return BatchAdmission.ENQUEUED;
			}

			if (queue.size() >= MAX_QUEUE_CAPACITY) {
				return overflowToRebuild(1, "live disconnect");
			}

			queue.addLast(new ExIndexerTask.DisconnectTask(event));
			catchUpLagBlocks.incrementAndGet();
			notEmpty.signalAll();
			return BatchAdmission.ENQUEUED;
		} finally {
			lock.unlock();
		}
	}

	private BatchAdmission overflowToRebuild(int incomingTasks, String source) {
		int discarded = queue.size();
		queue.clear();
		catchUpLagBlocks.addAndGet(incomingTasks);
		explorerReadiness.rebuilding(
				"Explorer live queue overflowed and is rebuilding from the local canonical archive");
		registry.counter("explorer.queue.overflow_to_rebuild", "source", source).increment();
		log.warn("Explorer queue rejected {} {} task(s) with {} queued; switching to canonical rebuild",
				incomingTasks, source, discarded);
		return BatchAdmission.REBUILD_REQUIRED;
	}

	public void discardForRebuild(String source) {
		lock.lock();
		try {
			overflowToRebuild(0, source);
		} finally {
			lock.unlock();
		}
	}

	public void recordSkippedBatch(List<BlockConnectedEvent> events) {
		if (generalProperties.isExplorerEnable() && !events.isEmpty()) {
			catchUpLagBlocks.addAndGet(events.size());
		}
	}

	public void recordSkippedDisconnect() {
		if (generalProperties.isExplorerEnable()) {
			catchUpLagBlocks.incrementAndGet();
		}
	}

	public void markTaskProcessed() {
		markTasksProcessed(1);
	}

	public void markTasksProcessed(int processedBlocks) {
		catchUpLagBlocks.updateAndGet(current -> Math.max(0L, current - processedBlocks));
	}

	public void markRebuildComplete() {
		catchUpLagBlocks.set(0L);
	}

	public ExIndexerTask take() throws InterruptedException {
		lock.lock();
		try {
			while (queue.isEmpty()) {
				notEmpty.await();
			}
			ExIndexerTask task = queue.pollFirst();
			return task;
		} finally {
			lock.unlock();
		}
	}

	private boolean enabled() {
		return generalProperties.isExplorerEnable() && explorerReadiness.isReady();
	}

	/**
	 * Drains up to maxElements from the queue for batch processing.
	 * Returns immediately with available elements (may be less than max or empty).
	 */
	public List<ExIndexerTask> drainBatch(int maxElements) {
		lock.lock();
		try {
			List<ExIndexerTask> batch = new ArrayList<>(Math.min(maxElements, queue.size()));
			while (!queue.isEmpty() && batch.size() < maxElements) {
				batch.add(queue.pollFirst());
			}
			return batch;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Takes a single element or waits up to the timeout.
	 */
	public ExIndexerTask poll(long timeoutMs) throws InterruptedException {
		lock.lock();
		try {
			if (queue.isEmpty()) {
				notEmpty.await(timeoutMs, TimeUnit.MILLISECONDS);
			}
			ExIndexerTask task = queue.pollFirst();
			return task;
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

	public enum BatchAdmission {
		ENQUEUED,
		REBUILD_REQUIRED,
		IGNORED
	}
}
