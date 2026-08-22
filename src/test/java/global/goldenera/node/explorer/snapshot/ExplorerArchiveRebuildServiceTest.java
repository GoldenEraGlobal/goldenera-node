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
package global.goldenera.node.explorer.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.node.explorer.storage.chainidentity.ExplorerReadinessState;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;

class ExplorerArchiveRebuildServiceTest {

	@Test
	void missingSnapshotFallbackRebuildsInBackgroundAndMarksReady() {
		GeneralProperties properties = properties(true);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExplorerArchiveReplayEngine engine = mock(ExplorerArchiveReplayEngine.class);
		ExecutorService executor = directExecutor();
		ExplorerArchiveRebuildService service = new ExplorerArchiveRebuildService(
				properties, readiness, engine, executor);

		service.start();

		verify(engine).rebuildToCanonicalHead();
		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.READY);
		verify(executor, never()).shutdownNow();
	}

	@Test
	void missingSnapshotTransitionsToRebuildingBeforeLocalWorkerRuns() {
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExplorerArchiveReplayEngine engine = mock(ExplorerArchiveReplayEngine.class);
		ExecutorService executor = mock(ExecutorService.class);
		ExplorerArchiveRebuildService service = new ExplorerArchiveRebuildService(
				properties(true), readiness, engine, executor);

		service.start();

		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.REBUILDING);
		verify(engine, never()).rebuildToCanonicalHead();
		ArgumentCaptor<Runnable> worker = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).execute(worker.capture());
		worker.getValue().run();
		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.READY);
	}

	@Test
	void stateRootMismatchFailsClosedWhileCoreLifecycleRemainsUnaffected() {
		GeneralProperties properties = properties(true);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExplorerArchiveReplayEngine engine = mock(ExplorerArchiveReplayEngine.class);
		doThrow(new ExplorerSnapshotException("state root mismatch at block 17"))
				.when(engine).rebuildToCanonicalHead();
		ExplorerArchiveRebuildService service = new ExplorerArchiveRebuildService(
				properties, readiness, engine, directExecutor());

		service.start();

		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.STORAGE_CORRUPT);
		assertThat(readiness.status().detail()).contains("state root mismatch at block 17");
	}

	@Test
	void restartCanResumeByInvokingReplayEngineAgain() {
		ExplorerArchiveReplayEngine engine = mock(ExplorerArchiveReplayEngine.class);
		ExplorerRuntimeReadiness firstReadiness = new ExplorerRuntimeReadiness();
		ExplorerRuntimeReadiness restartedReadiness = new ExplorerRuntimeReadiness();
		new ExplorerArchiveRebuildService(properties(true), firstReadiness, engine, directExecutor()).start();
		new ExplorerArchiveRebuildService(properties(true), restartedReadiness, engine, directExecutor()).start();

		verify(engine, times(2)).rebuildToCanonicalHead();
		assertThat(restartedReadiness.status().state()).isEqualTo(ExplorerReadinessState.READY);
	}

	@Test
	void canonicalChangeRetriesInsideTheSameRebuildAndFinishesReady() {
		ExplorerArchiveReplayEngine engine = mock(ExplorerArchiveReplayEngine.class);
		doThrow(new ExplorerCanonicalArchiveChangedException("reorg"))
				.doNothing()
				.when(engine).rebuildToCanonicalHead();
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExplorerArchiveRebuildService service = new ExplorerArchiveRebuildService(
				properties(true), readiness, engine, directExecutor());

		service.start();

		verify(engine, times(2)).rebuildToCanonicalHead();
		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.READY);
	}

	@Test
	void disabledExplorerDoesNoFallbackWork() {
		ExplorerArchiveReplayEngine engine = mock(ExplorerArchiveReplayEngine.class);
		ExecutorService executor = mock(ExecutorService.class);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExplorerArchiveRebuildService service = new ExplorerArchiveRebuildService(
				properties(false), readiness, engine, executor);

		service.start();

		verify(engine, never()).rebuildToCanonicalHead();
		verify(executor, never()).execute(any());
		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.STARTING);
	}

	private ExecutorService directExecutor() {
		ExecutorService executor = mock(ExecutorService.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any());
		return executor;
	}

	private GeneralProperties properties(boolean enabled) {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(enabled);
		return properties;
	}
}
