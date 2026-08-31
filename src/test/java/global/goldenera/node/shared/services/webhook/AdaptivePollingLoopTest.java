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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class AdaptivePollingLoopTest {

	private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

	@Test
	void defersAndCoalescesSignalsUntilStartupCompletes() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		Executor executor = mock(Executor.class);
		AdaptivePollingLoop loop = loop(scheduler, executor, () -> false);

		loop.wake();
		loop.wake();
		verifyNoInteractions(executor, scheduler);

		loop.start();
		loop.start();
		loop.wake();

		verify(executor).execute(any(Runnable.class));
		verifyNoInteractions(scheduler);
	}

	@Test
	void backsOffAfterIdleIterationsAndCapsRecoveryDelay() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		List<Instant> deadlines = new ArrayList<>();
		AtomicReference<Runnable> scheduled = new AtomicReference<>();
		when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
			scheduled.set(invocation.getArgument(0));
			deadlines.add(invocation.getArgument(1));
			return mock(ScheduledFuture.class);
		});
		AdaptivePollingLoop loop = loop(scheduler, Runnable::run, () -> false);

		loop.start();
		for (int iteration = 0; iteration < 5; iteration++) {
			scheduled.get().run();
		}

		assertThat(deadlines).containsExactly(
				NOW.plusMillis(100),
				NOW.plusMillis(200),
				NOW.plusMillis(400),
				NOW.plusMillis(800),
				NOW.plusSeconds(1),
				NOW.plusSeconds(1));
	}

	@Test
	void drainsImmediatelyWhileIterationReportsBacklog() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		AtomicInteger iterations = new AtomicInteger();
		AdaptivePollingLoop loop = loop(scheduler, Runnable::run, () -> iterations.incrementAndGet() < 3);

		loop.start();

		assertThat(iterations).hasValue(3);
		verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
	}

	@Test
	void localWakeCancelsRecoveryTimerAndDoesNotDuplicateQueuedWork() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		ScheduledFuture<?> recovery = mock(ScheduledFuture.class);
		AtomicReference<Runnable> scheduledCallback = new AtomicReference<>();
		when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
			scheduledCallback.set(invocation.getArgument(0));
			return recovery;
		});
		AtomicReference<Runnable> queued = new AtomicReference<>();
		AtomicInteger submissions = new AtomicInteger();
		Executor executor = command -> {
			submissions.incrementAndGet();
			if (!queued.compareAndSet(null, command)) {
				throw new AssertionError("duplicate task submitted");
			}
		};
		AdaptivePollingLoop loop = loop(scheduler, executor, () -> false);
		loop.start();
		queued.getAndSet(null).run();
		Runnable staleRecovery = scheduledCallback.get();

		loop.wake();
		loop.wake();
		staleRecovery.run();

		verify(recovery).cancel(false);
		assertThat(queued.get()).isNotNull();
		assertThat(submissions).hasValue(2);
	}

	@Test
	void iterationFailureSchedulesMinimumRecoveryAndContinues() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		AtomicReference<Runnable> recovery = new AtomicReference<>();
		AtomicReference<Instant> deadline = new AtomicReference<>();
		when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
			recovery.set(invocation.getArgument(0));
			deadline.set(invocation.getArgument(1));
			return mock(ScheduledFuture.class);
		});
		AtomicInteger iterations = new AtomicInteger();
		AdaptivePollingLoop loop = loop(scheduler, Runnable::run, () -> {
			if (iterations.incrementAndGet() == 1) {
				throw new IllegalStateException("temporary failure");
			}
			return false;
		});

		loop.start();
		assertThat(deadline.get()).isEqualTo(NOW.plusMillis(100));
		recovery.get().run();

		assertThat(iterations).hasValue(2);
	}

	@Test
	void executorRejectionSchedulesRecoveryWithoutLosingRequestedWork() {
		TaskScheduler scheduler = mock(TaskScheduler.class);
		AtomicReference<Runnable> recovery = new AtomicReference<>();
		when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
			recovery.set(invocation.getArgument(0));
			return mock(ScheduledFuture.class);
		});
		AtomicInteger submissions = new AtomicInteger();
		AtomicReference<Runnable> accepted = new AtomicReference<>();
		Executor executor = command -> {
			if (submissions.incrementAndGet() == 1) {
				throw new RejectedExecutionException("busy");
			}
			accepted.set(command);
		};
		AdaptivePollingLoop loop = loop(scheduler, executor, () -> false);

		loop.start();
		recovery.get().run();

		assertThat(submissions).hasValue(2);
		assertThat(accepted.get()).isNotNull();
	}

	private AdaptivePollingLoop loop(TaskScheduler scheduler, Executor executor, BooleanSupplier task) {
		return new AdaptivePollingLoop(
				scheduler,
				executor,
				task,
				Duration.ofMillis(100),
				Duration.ofSeconds(1),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}
}
