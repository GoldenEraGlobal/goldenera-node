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

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.events.BlockConnectionBatchCompletedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.explorer.snapshot.ExplorerArchiveRebuildTrigger;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ExIndexerNodeListener {

	GeneralProperties generalProperties;
	ExplorerRuntimeReadiness explorerReadiness;
	ExIndexerQueueService queueService;
	ExplorerArchiveRebuildTrigger archiveRebuildTrigger;

	@EventListener
	public void onBlockConnected(BlockConnectedEvent event) {
		if (!generalProperties.isExplorerEnable()) {
			return;
		}
		if (event.isBatchMember()) {
			return;
		}
		if (!explorerReadiness.isReady()) {
			queueService.recordSkippedBatch(List.of(event));
			return;
		}
		if (queueService.pushConnect(event) == ExIndexerQueueService.BatchAdmission.REBUILD_REQUIRED) {
			archiveRebuildTrigger.request();
		}
	}

	@EventListener
	public void onBlockConnectionBatchCompleted(BlockConnectionBatchCompletedEvent event) {
		if (!generalProperties.isExplorerEnable()) {
			return;
		}
		if (event.getConnectedSource() == ConnectedSource.SYNC) {
			if (!explorerReadiness.isReady()) {
				queueService.recordSkippedBatch(event.getBlockEvents());
				return;
			}
			if (queueService.pushConnectBatch(event.getBlockEvents())
					== ExIndexerQueueService.BatchAdmission.REBUILD_REQUIRED) {
				archiveRebuildTrigger.request();
			}
		}
	}

	@EventListener
	public void onBlockDisconnected(BlockDisconnectedEvent event) {
		if (!generalProperties.isExplorerEnable()) {
			return;
		}
		if (!explorerReadiness.isReady()) {
			queueService.recordSkippedDisconnect();
			return;
		}
		if (queueService.pushDisconnect(event) == ExIndexerQueueService.BatchAdmission.REBUILD_REQUIRED) {
			archiveRebuildTrigger.request();
		}
	}
}
