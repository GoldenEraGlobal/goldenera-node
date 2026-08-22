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

	private static final int MAX_QUEUE_CAPACITY = 10000;

	GeneralProperties generalProperties;
	ExplorerRuntimeReadiness explorerReadiness;
	MeterRegistry registry;
	Deque<ExIndexerTask> queue = new ArrayDeque<>(MAX_QUEUE_CAPACITY);
	AtomicLong catchUpLagBlocks = new AtomicLong();

	ReentrantLock lock = new ReentrantLock(true);
	Condition notEmpty = lock.newCondition();
	Condition notFull = lock.newCondition();

	@PostConstruct
	public void initMetrics() {
		if (!enabled()) {
			return;
		}
		registry.gaugeCollectionSize("explorer.queue.size", Tags.empty(), queue);
		registry.gauge("explorer.catchup.lag.blocks", catchUpLagBlocks);
	}

	public void pushConnect(BlockConnectedEvent event) {
		if (!enabled()) {
			return;
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
				notFull.signalAll();
				log.debug("Optimization: Skipped flickering (Disconnect->Connect) for block #{}",
						event.getBlock().getHeight());
				return;
			}

			awaitCapacity(1);

			queue.addLast(new ExIndexerTask.ConnectTask(event));
			catchUpLagBlocks.incrementAndGet();
			notEmpty.signalAll();
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
			if (events.size() > MAX_QUEUE_CAPACITY
					|| queue.size() + events.size() > MAX_QUEUE_CAPACITY) {
				int discarded = queue.size();
				queue.clear();
				catchUpLagBlocks.addAndGet(events.size());
				notFull.signalAll();
				explorerReadiness.rebuilding(
						"Explorer live queue overflowed and is rebuilding from the local canonical archive");
				registry.counter("explorer.queue.overflow_to_rebuild").increment();
				log.warn(
						"Explorer queue cannot atomically admit {} committed sync blocks ({} queued); "
								+ "switching to nonblocking local archive rebuild",
						events.size(), discarded);
				return BatchAdmission.REBUILD_REQUIRED;
			}
			events.forEach(event -> queue.addLast(new ExIndexerTask.ConnectTask(event)));
			catchUpLagBlocks.addAndGet(events.size());
			notEmpty.signalAll();
			return BatchAdmission.ENQUEUED;
		} finally {
			lock.unlock();
		}
	}

	public void pushDisconnect(BlockDisconnectedEvent event) {
		if (!enabled()) {
			return;
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
				notFull.signalAll();
				log.debug("Optimization: Skipped indexing/reverting block #{} (cancelled in queue)",
						event.getBlock().getHeight());
				return;
			}

			awaitCapacity(1);

			queue.addLast(new ExIndexerTask.DisconnectTask(event));
			catchUpLagBlocks.incrementAndGet();
			notEmpty.signalAll();
		} finally {
			lock.unlock();
		}
	}

	private void awaitCapacity(int requiredCapacity) {
		boolean blocked = false;
		long blockedSince = 0L;
		while (queue.size() + requiredCapacity > MAX_QUEUE_CAPACITY) {
			if (!blocked) {
				registry.counter("explorer.queue.blocked").increment();
				blocked = true;
				blockedSince = System.nanoTime();
			}
			notFull.awaitUninterruptibly();
		}
		if (blocked) {
			registry.timer("explorer.queue.blocked.duration")
					.record(System.nanoTime() - blockedSince, TimeUnit.NANOSECONDS);
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
		catchUpLagBlocks.updateAndGet(current -> Math.max(0L, current - 1L));
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
			notFull.signalAll();
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
			if (!batch.isEmpty()) {
				notFull.signalAll();
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
			if (task != null) {
				notFull.signalAll();
			}
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
