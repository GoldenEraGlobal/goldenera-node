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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.MempoolTxRemoveEvent;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerMempoolCoreService;
import global.goldenera.node.explorer.storage.chainidentity.ExplorerRuntimeReadiness;
import global.goldenera.node.shared.properties.GeneralProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerMempoolServiceTest {

	@Test
	void failedFlushRequeuesRemovedActionForNextAttempt() {
		GeneralProperties properties = new GeneralProperties();
		properties.setExplorerEnable(true);
		ExplorerRuntimeReadiness readiness = new ExplorerRuntimeReadiness();
		readiness.ready();
		ExIndexerMempoolCoreService core = mock(ExIndexerMempoolCoreService.class);
		ExIndexerMempoolService service = new ExIndexerMempoolService(
				properties, readiness, new SimpleMeterRegistry(), core, mock(ThreadPoolTaskScheduler.class));
		Hash hash = Hash.hash(Bytes.of(1));
		Tx tx = mock(Tx.class);
		when(tx.getHash()).thenReturn(hash);
		MempoolEntry entry = mock(MempoolEntry.class);
		when(entry.getTx()).thenReturn(tx);
		MempoolTxRemoveEvent event = mock(MempoolTxRemoveEvent.class);
		when(event.getEntry()).thenReturn(entry);
		service.onMempoolRemove(event);
		doThrow(new IllegalStateException("database unavailable"))
				.when(core).applyBatch(anyList(), anyList());

		assertThatThrownBy(service::flushBuffer).isInstanceOf(IllegalStateException.class);
		doNothing().when(core).applyBatch(anyList(), anyList());
		service.flushBuffer();

		verify(core, times(2)).applyBatch(anyList(), anyList());
	}
}
