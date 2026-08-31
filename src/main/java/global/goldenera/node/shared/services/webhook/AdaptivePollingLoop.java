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
package global.goldenera.node.shared.services.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.springframework.scheduling.TaskScheduler;

import lombok.extern.slf4j.Slf4j;

/**
 * Coalesces local wake signals and retains a bounded polling fallback for work
 * produced by another process or left behind by a crash.
 */
@Slf4j
public final class AdaptivePollingLoop {

	private final TaskScheduler scheduler;
	private final Executor executor;
	private final BooleanSupplier iteration;
	private final long minimumDelayNanos;
	private final long maximumDelayNanos;
	private final Clock clock;
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean stopped = new AtomicBoolean();
	private final AtomicBoolean requested = new AtomicBoolean();
	private final AtomicBoolean submitted = new AtomicBoolean();
	private final AtomicLong idleDelayNanos;
	private final Object timerMonitor = new Object();

	private long timerGeneration;
	private ScheduledFuture<?> timer;

	public AdaptivePollingLoop(
			TaskScheduler scheduler,
			Executor executor,
			BooleanSupplier iteration,
			Duration minimumDelay,
			Duration maximumDelay) {
		this(scheduler, executor, iteration, minimumDelay, maximumDelay, Clock.systemUTC());
	}

	AdaptivePollingLoop(
			TaskScheduler scheduler,
			Executor executor,
			BooleanSupplier iteration,
			Duration minimumDelay,
			Duration maximumDelay,
			Clock clock) {
		this.scheduler = Objects.requireNonNull(scheduler);
		this.executor = Objects.requireNonNull(executor);
		this.iteration = Objects.requireNonNull(iteration);
		this.minimumDelayNanos = requirePositive(minimumDelay, "minimumDelay");
		this.maximumDelayNanos = requirePositive(maximumDelay, "maximumDelay");
		if (maximumDelayNanos < minimumDelayNanos) {
			throw new IllegalArgumentException("maximumDelay must not be shorter than minimumDelay");
		}
		this.clock = Objects.requireNonNull(clock);
		this.idleDelayNanos = new AtomicLong(minimumDelayNanos);
	}

	public void start() {
		if (!started.compareAndSet(false, true) || stopped.get()) {
			return;
		}
		wake();
	}

	public void wake() {
		if (stopped.get()) {
			return;
		}
		requested.set(true);
		idleDelayNanos.set(minimumDelayNanos);
		if (!started.get()) {
			return;
		}
		cancelTimer();
		submit();
	}

	public void stop() {
		if (!stopped.compareAndSet(false, true)) {
			return;
		}
		requested.set(false);
		cancelTimer();
	}

	private void submit() {
		if (stopped.get() || !submitted.compareAndSet(false, true)) {
			return;
		}
		try {
			executor.execute(this::runIteration);
		} catch (RuntimeException failure) {
			submitted.set(false);
			log.error("Cannot submit adaptive polling iteration", failure);
			try {
				scheduleRecovery(minimumDelayNanos, true);
			} catch (RuntimeException schedulingFailure) {
				failure.addSuppressed(schedulingFailure);
				log.error("Cannot schedule adaptive polling recovery", failure);
			}
		}
	}

	private void runIteration() {
		if (stopped.get()) {
			submitted.set(false);
			return;
		}
		boolean continueImmediately = false;
		boolean failed = false;
		requested.set(false);
		try {
			continueImmediately = iteration.getAsBoolean();
		} catch (RuntimeException failure) {
			failed = true;
			idleDelayNanos.set(minimumDelayNanos);
			log.error("Adaptive polling iteration failed", failure);
		} finally {
			submitted.set(false);
		}
		if (stopped.get()) {
			return;
		}
		if (continueImmediately) {
			idleDelayNanos.set(minimumDelayNanos);
			requested.set(true);
		}
		if (requested.get()) {
			submit();
			return;
		}
		long delay = failed ? minimumDelayNanos : idleDelayNanos.getAndUpdate(this::nextDelay);
		scheduleRecovery(delay);
	}

	private void scheduleRecovery(long delayNanos) {
		scheduleRecovery(delayNanos, false);
	}

	private void scheduleRecovery(long delayNanos, boolean afterRejectedSubmission) {
		synchronized (timerMonitor) {
			if (stopped.get() || !started.get() || submitted.get()
					|| (!afterRejectedSubmission && requested.get())) {
				return;
			}
			long generation = ++timerGeneration;
			Instant executionTime = clock.instant().plusNanos(delayNanos);
			timer = scheduler.schedule(() -> recover(generation), executionTime);
		}
	}

	private void recover(long generation) {
		synchronized (timerMonitor) {
			if (stopped.get() || generation != timerGeneration) {
				return;
			}
			timer = null;
		}
		requested.set(true);
		submit();
	}

	private void cancelTimer() {
		synchronized (timerMonitor) {
			timerGeneration++;
			if (timer != null) {
				timer.cancel(false);
				timer = null;
			}
		}
	}

	private long nextDelay(long current) {
		if (current >= maximumDelayNanos) {
			return maximumDelayNanos;
		}
		return current > maximumDelayNanos / 2L ? maximumDelayNanos : current * 2L;
	}

	private static long requirePositive(Duration duration, String name) {
		Objects.requireNonNull(duration, name);
		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return duration.toNanos();
	}
}
