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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.explorer.repositories.ExBlockHeaderRepository;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;

class ExIndexerStartupRecoveryServiceTest {

	@Test
	void emptyBlockTableClearsStaleStatusBeforeGenesisCatchUp() {
		ExBlockHeaderRepository headers = mock(ExBlockHeaderRepository.class);
		when(headers.findLatest()).thenReturn(Optional.empty());
		ExIndexerStatusCoreService status = mock(ExIndexerStatusCoreService.class);
		ExIndexerStartupRecoveryService service = new ExIndexerStartupRecoveryService(
				headers, status, mock(ExIndexerRevertService.class), mock(ChainQuery.class));

		long height = service.reconcileCanonicalHead();

		assertThat(height).isEqualTo(-1L);
		verify(status).clearStatus();
	}
}
