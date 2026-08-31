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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerReadinessState;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.exceptions.GEFailedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerSyncServiceTest {

	@Test
	void catchesUpLargeGapInBoundedTransactionsWithoutArchiveReplay() {
		ExIndexerStartupRecoveryService recovery = mock(ExIndexerStartupRecoveryService.class);
		when(recovery.reconcileCanonicalHead()).thenReturn(-1L, 129L);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(129L));
		StoredBlock storedBlock = mock(StoredBlock.class);
		when(chainQuery.getStoredBlockByHeight(anyLong())).thenReturn(Optional.of(storedBlock));
		ExIndexerEventReconstructionService reconstruction = mock(ExIndexerEventReconstructionService.class);
		when(reconstruction.reconstructEvent(storedBlock)).thenReturn(mock(BlockConnectedEvent.class));
		ExIndexerService indexer = mock(ExIndexerService.class);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ExIndexerSyncService service = new ExIndexerSyncService(
				recovery, chainQuery, reconstruction, indexer,
				new ExplorerIndexingExecutionGate(), readiness, registry);

		service.syncExplorerOnStartup();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BlockConnectedEvent>> batches = ArgumentCaptor.forClass(List.class);
		verify(indexer, org.mockito.Mockito.times(3)).handleBlockConnectedBatch(batches.capture());
		assertThat(batches.getAllValues()).extracting(List::size).containsExactly(64, 64, 2);
		assertThat(readiness.isReady()).isTrue();
		assertThat(registry.counter("explorer.startup.catchup.blocks").count()).isEqualTo(130.0);
	}

	@Test
	void missingCanonicalBlockFailsClosedInsteadOfCommittingPartialStartupSync() {
		ExIndexerStartupRecoveryService recovery = mock(ExIndexerStartupRecoveryService.class);
		when(recovery.reconcileCanonicalHead()).thenReturn(-1L);
		ChainQuery chainQuery = mock(ChainQuery.class);
		when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(0L));
		when(chainQuery.getStoredBlockByHeight(0L)).thenReturn(Optional.empty());
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		ExIndexerSyncService service = new ExIndexerSyncService(
				recovery, chainQuery, mock(ExIndexerEventReconstructionService.class),
				mock(ExIndexerService.class), new ExplorerIndexingExecutionGate(), readiness,
				new SimpleMeterRegistry());

		assertThatThrownBy(service::syncExplorerOnStartup)
				.isInstanceOf(GEFailedException.class)
				.hasMessageContaining("Missing canonical core block 0");
		assertThat(readiness.status().state()).isEqualTo(ExplorerReadinessState.STORAGE_CORRUPT);
	}
}
