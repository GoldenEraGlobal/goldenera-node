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
package global.goldenera.node.core.mempool;

import static global.goldenera.node.core.mempool.MempoolTestFixtures.ALICE;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.BOB;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.address;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.governance;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.properties;
import static global.goldenera.node.core.mempool.MempoolTestFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MempoolRestartRecoveryTest {

	@Test
	void processRestartStartsEmptyAndPeerSyncRebuildsNonceAndGovernanceIndexes() {
		Address target = address(900);
		TxBipAuthorityAddPayload payload = mock(TxBipAuthorityAddPayload.class);
		when(payload.getAddress()).thenReturn(target);
		MempoolEntry first = transfer(1, ALICE, 1, 10);
		MempoolEntry second = transfer(2, ALICE, 2, 20);
		MempoolEntry authorityChange = governance(3, BOB, 1, 30, payload);
		MempoolStore beforeCrash = store();
		beforeCrash.addTransactions(List.of(first, second, authorityChange), Map.of(ALICE, 0L, BOB, 0L),
				MempoolTxAddEvent.AddReason.SYNC);
		assertThat(beforeCrash.getCount()).isEqualTo(3);

		MempoolStore afterRestart = store();
		assertThat(afterRestart.getCount()).isZero();
		assertThat(afterRestart.isAuthorityAddPending(target)).isFalse();

		afterRestart.addTransactions(beforeCrash.getAllTxs(), Map.of(ALICE, 0L, BOB, 0L),
				MempoolTxAddEvent.AddReason.SYNC);

		assertThat(afterRestart.getAllTxHashes()).containsExactlyInAnyOrderElementsOf(beforeCrash.getAllTxHashes());
		assertThat(afterRestart.getNextAvailableNonce(ALICE, 0)).isEqualTo(3);
		assertThat(afterRestart.isAuthorityAddPending(target)).isTrue();
		assertThat(afterRestart.getExecutableTransactionsIterator()).toIterable().hasSize(3);
	}

	private MempoolStore store() {
		return new MempoolStore(new SimpleMeterRegistry(), properties(100),
				mock(ChainHeadStateCache.class), mock(ApplicationEventPublisher.class));
	}
}
