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
package global.goldenera.node.core.sync.snapshot.publication;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectionBatchCompletedEvent;
import global.goldenera.node.core.blockchain.events.BlockReorgEvent;
import global.goldenera.node.core.blockchain.events.CoreReadyEvent;
import lombok.extern.slf4j.Slf4j;

/** Coalesces canonical-head signals onto one bounded low-priority background worker. */
@Slf4j
public final class SnapshotPublicationLifecycle implements DisposableBean {

	private final SnapshotPublicationCoordinator coordinator;
	private final ExecutorService executor;
	private final Sleeper sleeper;
	private final AtomicBoolean dirty = new AtomicBoolean();
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile boolean stopping;

	public SnapshotPublicationLifecycle(
			SnapshotPublicationCoordinator coordinator, ExecutorService executor) {
		this(coordinator, executor, duration -> Thread.sleep(duration.toMillis()));
	}

	SnapshotPublicationLifecycle(
			SnapshotPublicationCoordinator coordinator, ExecutorService executor, Sleeper sleeper) {
		this.coordinator = coordinator;
		this.executor = executor;
		this.sleeper = sleeper;
	}

	@EventListener(CoreReadyEvent.class)
	public void onStartup() {
		signal();
	}

	@EventListener
	public void onBatch(BlockConnectionBatchCompletedEvent event) {
		signal();
	}

	@EventListener
	public void onHead(BlockConnectedEvent event) {
		signal();
	}

	@EventListener
	public void onReorg(BlockReorgEvent event) {
		signal();
	}

	/** Ensures idle publishing nodes maintain captures and eventually reach the daily cadence. */
	@Scheduled(fixedDelay = 30_000)
	public void onPeriodicEvaluation() {
		signal();
	}

	void signal() {
		if (stopping) {
			return;
		}
		dirty.set(true);
		startWorker();
	}

	private void startWorker() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			executor.execute(this::runLoop);
		} catch (RejectedExecutionException e) {
			running.set(false);
		}
	}

	private void runLoop() {
		try {
			while (!stopping && dirty.getAndSet(false)) {
				SnapshotPublicationCoordinator.AttemptResult result = coordinator.attempt();
				logResult(result);
				if (result.outcome() == SnapshotPublicationCoordinator.Outcome.RETRY_REQUIRED
						|| result.outcome() == SnapshotPublicationCoordinator.Outcome.BACKING_OFF
						|| result.outcome() == SnapshotPublicationCoordinator.Outcome.LOCKED) {
					Duration delay = result.retryAfter();
					if (!delay.isZero() && !delay.isNegative()) {
						sleeper.sleep(delay);
					}
					dirty.set(true);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			running.set(false);
			if (dirty.get() && !stopping) {
				startWorker();
			}
		}
	}

	private void logResult(SnapshotPublicationCoordinator.AttemptResult result) {
		switch (result.outcome()) {
			case PUBLISHED -> log.info("SNAPSHOT PUBLISHER: Published verified snapshot at height {}",
					result.observedHeight());
			case RETRY_REQUIRED -> log.warn(
					"SNAPSHOT PUBLISHER: Artifact unavailable; core/P2P remains live and generation retries in {}",
					result.retryAfter());
			case HEAD_BELOW_SAFETY_LAG -> log.debug(
					"SNAPSHOT PUBLISHER: Canonical head {} is below the mandatory safety lag",
					result.observedHeight());
			case LOCKED, BACKING_OFF, CADENCE_NOT_REACHED, DISABLED -> log.debug(
					"SNAPSHOT PUBLISHER: No generation required ({})", result.outcome());
		}
	}

	@Override
	public void destroy() {
		stopping = true;
		executor.shutdownNow();
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}
}
