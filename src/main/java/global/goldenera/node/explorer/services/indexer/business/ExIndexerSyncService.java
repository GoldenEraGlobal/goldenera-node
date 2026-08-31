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

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerReadinessState;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.exceptions.GEFailedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExIndexerSyncService {
	static final int CATCH_UP_BATCH_SIZE = 64;

	ExIndexerStartupRecoveryService recoveryService;
	ChainQuery chainQuery;
	ExIndexerEventReconstructionService eventReconstructionService;
	ExIndexerService indexerService;
	ExplorerIndexingExecutionGate executionGate;
	ExplorerRuntimeReadiness readiness;
	MeterRegistry registry;

	public void syncExplorerOnStartup() {
		executionGate.run(this::syncExplorerOnStartupUnderGate);
	}

	private void syncExplorerOnStartupUnderGate() {
		log.info("Checking Explorer synchronization status...");
		readiness.rebuilding("Explorer is catching up from the local canonical archive");
		Timer.Sample timer = Timer.start(registry);
		try {
			long explorerHeight = recoveryService.reconcileCanonicalHead();
			while (true) {
				long targetHeight = chainQuery.getLatestBlockHeight().orElse(-1L);
				if (explorerHeight < targetHeight) {
					log.info("Explorer catch-up from #{} to #{} in batches of {}",
							explorerHeight + 1, targetHeight, CATCH_UP_BATCH_SIZE);
					explorerHeight = catchUpRange(explorerHeight + 1, targetHeight);
				}

				explorerHeight = recoveryService.reconcileCanonicalHead();
				long currentCoreHeight = chainQuery.getLatestBlockHeight().orElse(-1L);
				if (explorerHeight == currentCoreHeight) {
					readiness.ready();
					log.info("Explorer startup catch-up complete at block #{}", explorerHeight);
					return;
				}
			}
		} catch (RuntimeException failure) {
			if (readiness.status().state() == ExplorerReadinessState.REBUILDING) {
				readiness.failed(ExplorerReadinessState.STORAGE_CORRUPT,
						"Explorer startup catch-up failed: " + rootMessage(failure));
			}
			throw failure;
		} finally {
			timer.stop(registry.timer("explorer.startup.catchup.time"));
		}
	}

	private long catchUpRange(long startHeight, long targetHeight) {
		List<BlockConnectedEvent> batch = new ArrayList<>(CATCH_UP_BATCH_SIZE);
		long indexedHeight = startHeight - 1;
		for (long height = startHeight; height <= targetHeight; height++) {
			StoredBlock storedBlock = chainQuery.getStoredBlockByHeight(height).orElse(null);
			if (storedBlock == null) {
				String detail = "Missing canonical core block " + height + " during Explorer startup catch-up";
				readiness.failed(ExplorerReadinessState.STORAGE_CORRUPT, detail);
				throw new GEFailedException(detail);
			}
			batch.add(eventReconstructionService.reconstructEvent(storedBlock));
			if (batch.size() == CATCH_UP_BATCH_SIZE || height == targetHeight) {
				indexerService.handleBlockConnectedBatch(List.copyOf(batch));
				registry.counter("explorer.startup.catchup.blocks").increment(batch.size());
				registry.counter("explorer.startup.catchup.batches").increment();
				indexedHeight = height;
				batch.clear();
			}
		}
		return indexedHeight;
	}

	private String rootMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}
}
