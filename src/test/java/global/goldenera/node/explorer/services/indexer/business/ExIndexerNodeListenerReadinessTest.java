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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockConnectionBatchCompletedEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveRebuildService;
import global.goldenera.node.shared.properties.GeneralProperties;

class ExIndexerNodeListenerReadinessTest {

	@Test
	void doesNotEnqueueCoreEventsUntilExplorerStorageIsReady() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		ExIndexerQueueService queue = mock(ExIndexerQueueService.class);
		ExplorerArchiveRebuildService rebuild = mock(ExplorerArchiveRebuildService.class);
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		ExIndexerNodeListener listener = new ExIndexerNodeListener(properties, readiness, queue, rebuild);
		when(readiness.isReady()).thenReturn(false);

		listener.onBlockConnected(event);

		verify(queue, never()).pushConnect(event);
		when(readiness.isReady()).thenReturn(true);
		listener.onBlockConnected(event);
		verify(queue).pushConnect(event);
	}

	@Test
	void enqueuesSyncBlocksOnlyAtTheirCommittedBatchBoundary() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		when(readiness.isReady()).thenReturn(true);
		ExIndexerQueueService queue = mock(ExIndexerQueueService.class);
		ExplorerArchiveRebuildService rebuild = mock(ExplorerArchiveRebuildService.class);
		BlockConnectedEvent first = mock(BlockConnectedEvent.class);
		BlockConnectedEvent tip = mock(BlockConnectedEvent.class);
		when(first.getConnectedSource()).thenReturn(ConnectedSource.SYNC);
		when(tip.getConnectedSource()).thenReturn(ConnectedSource.SYNC);
		when(first.isBatchMember()).thenReturn(true);
		when(tip.isBatchMember()).thenReturn(true);
		ExIndexerNodeListener listener = new ExIndexerNodeListener(properties, readiness, queue, rebuild);

		listener.onBlockConnected(first);
		listener.onBlockConnected(tip);
		verify(queue, never()).pushConnect(first);
		verify(queue, never()).pushConnect(tip);

		List<BlockConnectedEvent> events = List.of(first, tip);
		listener.onBlockConnectionBatchCompleted(new BlockConnectionBatchCompletedEvent(
				this, ConnectedSource.SYNC, events));

		verify(queue).pushConnectBatch(events);
	}

	@Test
	void queueOverflowStartsOneBackgroundRebuildWithoutBlockingThePublisher() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		when(readiness.isReady()).thenReturn(true);
		ExIndexerQueueService queue = mock(ExIndexerQueueService.class);
		ExplorerArchiveRebuildService rebuild = mock(ExplorerArchiveRebuildService.class);
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		List<BlockConnectedEvent> events = List.of(event);
		when(queue.pushConnectBatch(events))
				.thenReturn(ExIndexerQueueService.BatchAdmission.REBUILD_REQUIRED);
		ExIndexerNodeListener listener = new ExIndexerNodeListener(properties, readiness, queue, rebuild);

		listener.onBlockConnectionBatchCompleted(new BlockConnectionBatchCompletedEvent(
				this, ConnectedSource.SYNC, events));

		verify(rebuild).start();
	}

	@Test
	void disabledExplorerDoesNoQueueOrRebuildWork() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(false);
		ExplorerRuntimeReadiness readiness = mock(ExplorerRuntimeReadiness.class);
		ExIndexerQueueService queue = mock(ExIndexerQueueService.class);
		ExplorerArchiveRebuildService rebuild = mock(ExplorerArchiveRebuildService.class);
		ExIndexerNodeListener listener = new ExIndexerNodeListener(properties, readiness, queue, rebuild);
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);

		listener.onBlockConnected(event);
		listener.onBlockConnectionBatchCompleted(new BlockConnectionBatchCompletedEvent(
				this, ConnectedSource.SYNC, List.of(event)));

		verify(queue, never()).pushConnect(event);
		verify(queue, never()).pushConnectBatch(List.of(event));
		verify(rebuild, never()).start();
	}
}
