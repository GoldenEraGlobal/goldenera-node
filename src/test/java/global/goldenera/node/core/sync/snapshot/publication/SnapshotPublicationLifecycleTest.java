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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SnapshotPublicationLifecycleTest {

	@Test
	void duplicateHeadSignalsCoalesceIntoOneBackgroundEvaluation() {
		SnapshotPublicationCoordinator coordinator = mock(SnapshotPublicationCoordinator.class);
		when(coordinator.attempt()).thenReturn(new SnapshotPublicationCoordinator.AttemptResult(
				SnapshotPublicationCoordinator.Outcome.CADENCE_NOT_REACHED, Duration.ZERO, 10));
		CapturingExecutor executor = new CapturingExecutor();
		SnapshotPublicationLifecycle lifecycle = new SnapshotPublicationLifecycle(
				coordinator, executor, duration -> { });

		lifecycle.signal();
		lifecycle.signal();
		executor.runNext();

		verify(coordinator, times(1)).attempt();
	}

	private static final class CapturingExecutor extends AbstractExecutorService {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		private boolean shutdown;

		@Override
		public void shutdown() {
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown = true;
			return List.copyOf(tasks);
		}

		@Override
		public boolean isShutdown() {
			return shutdown;
		}

		@Override
		public boolean isTerminated() {
			return shutdown && tasks.isEmpty();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return isTerminated();
		}

		@Override
		public void execute(Runnable command) {
			tasks.add(command);
		}

		private void runNext() {
			tasks.remove().run();
		}
	}
}
