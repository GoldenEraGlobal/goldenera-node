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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.node.explorer.services.indexer.business.ExIndexerQueueService;
import global.goldenera.node.explorer.services.indexer.business.ExplorerIndexingExecutionGate;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerReadinessState;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;

class ExplorerArchiveRebuildLauncherTest {

	private ExplorerArchiveRebuildLauncher launcher;

	@AfterEach
	void tearDown() {
		if (launcher != null) {
			launcher.destroy();
		}
	}

	@Test
	void missingSnapshotDefersExactlyOneRebuildUntilInitializationCompletes() {
		ExplorerArchiveReplayEngine replayEngine = mock(ExplorerArchiveReplayEngine.class);
		Providers providers = providers(replayEngine);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		launcher = launcher(readiness, providers, true);

		launcher.request();
		launcher.request();

		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.REBUILDING);
		assertThat(launcher.hasCreatedWorker()).isFalse();
		verifyNoInteractions(providers.replayEngineProvider(), providers.executionGateProvider(),
				providers.queueServiceProvider());

		launcher.afterSingletonsInstantiated();

		await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
			verify(replayEngine, times(1)).rebuildToCanonicalHead();
			assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.READY);
		});
		assertThat(launcher.hasCreatedWorker()).isTrue();
	}

	@Test
	void successfulSnapshotInitializationNeverCreatesRebuildWorker() {
		ExplorerArchiveReplayEngine replayEngine = mock(ExplorerArchiveReplayEngine.class);
		Providers providers = providers(replayEngine);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		launcher = launcher(readiness, providers, true);

		readiness.ready();
		launcher.afterSingletonsInstantiated();

		assertThat(launcher.hasCreatedWorker()).isFalse();
		verifyNoInteractions(providers.replayEngineProvider(), providers.executionGateProvider(),
				providers.queueServiceProvider());
		verify(replayEngine, never()).rebuildToCanonicalHead();
		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.READY);
	}

	@Test
	void rebuildFailureIsReportedAfterDeferredLaunchWithoutDeadlock() {
		ExplorerArchiveReplayEngine replayEngine = mock(ExplorerArchiveReplayEngine.class);
		doThrow(new ExplorerSnapshotException("corrupt local archive"))
				.when(replayEngine).rebuildToCanonicalHead();
		Providers providers = providers(replayEngine);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		launcher = launcher(readiness, providers, true);

		launcher.request();
		launcher.afterSingletonsInstantiated();

		await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
			assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.STORAGE_CORRUPT);
			assertThat(readiness.status().detail()).contains("corrupt local archive");
		});
	}

	@Test
	void disabledExplorerNeverResolvesOrStartsRebuildDependencies() {
		ExplorerArchiveReplayEngine replayEngine = mock(ExplorerArchiveReplayEngine.class);
		Providers providers = providers(replayEngine);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		launcher = launcher(readiness, providers, false);

		launcher.request();
		launcher.afterSingletonsInstantiated();

		assertThat(launcher.hasCreatedWorker()).isFalse();
		verifyNoInteractions(providers.replayEngineProvider(), providers.executionGateProvider(),
				providers.queueServiceProvider());
		verifyNoInteractions(replayEngine);
		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.STARTING);
	}

	private ExplorerArchiveRebuildLauncher launcher(
			ExplorerRuntimeReadiness readiness, Providers providers, boolean explorerEnabled) {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(explorerEnabled);
		return new ExplorerArchiveRebuildLauncher(
				properties,
				readiness,
				providers.replayEngineProvider(),
				providers.executionGateProvider(),
				providers.queueServiceProvider());
	}

	@SuppressWarnings("unchecked")
	private Providers providers(ExplorerArchiveReplayEngine replayEngine) {
		ObjectProvider<ExplorerArchiveReplayEngine> replayProvider = mock(ObjectProvider.class);
		ObjectProvider<ExplorerIndexingExecutionGate> gateProvider = mock(ObjectProvider.class);
		ObjectProvider<ExIndexerQueueService> queueProvider = mock(ObjectProvider.class);
		when(replayProvider.getObject()).thenReturn(replayEngine);
		when(gateProvider.getObject()).thenReturn(new ExplorerIndexingExecutionGate());
		when(queueProvider.getIfAvailable()).thenReturn(null);
		return new Providers(replayProvider, gateProvider, queueProvider);
	}

	private record Providers(
			ObjectProvider<ExplorerArchiveReplayEngine> replayEngineProvider,
			ObjectProvider<ExplorerIndexingExecutionGate> executionGateProvider,
			ObjectProvider<ExIndexerQueueService> queueServiceProvider) {
	}
}
