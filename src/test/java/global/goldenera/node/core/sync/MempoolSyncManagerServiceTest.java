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
package global.goldenera.node.core.sync;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.MempoolStore;
import global.goldenera.node.core.p2p.events.P2PHandshakeCompletedEvent;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;

class MempoolSyncManagerServiceTest {

	@Test
	void unavailableMempoolDoesNotFailTheHandshake() {
		MempoolManager mempoolManager = mock(MempoolManager.class);
		when(mempoolManager.isFull()).thenThrow(new IllegalStateException("Mempool runtime is terminally unavailable"));
		MempoolSyncManagerService service = new MempoolSyncManagerService(mempoolManager, mock(MempoolStore.class));
		RemotePeer peer = mock(RemotePeer.class);
		P2PHandshakeCompletedEvent event = new P2PHandshakeCompletedEvent(this, 1, peer, mock(P2PStatusDto.class));

		assertThatCode(() -> service.onPeerConnected(event)).doesNotThrowAnyException();
		verify(peer, never()).sendGetMempoolHashes();
	}
}
