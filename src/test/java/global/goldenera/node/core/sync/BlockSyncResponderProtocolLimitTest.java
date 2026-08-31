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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.p2p.events.P2PHeadersRequestedEvent;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

class BlockSyncResponderProtocolLimitTest {

	@Test
	void legacyPeerCannotRequestMoreThanOneThousandHeaders() {
		assertResponderRange(P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT, 1_010L);
	}

	@Test
	void negotiatedV2PeerCannotRequestMoreThanFourThousandNinetySixHeaders() {
		assertResponderRange(P2PSyncProtocol.V2_HEADER_PAGE_LIMIT, 4_106L);
	}

	private void assertResponderRange(int negotiatedLimit, long expectedEndHeight) {
		ChainQuery chainQuery = mock(ChainQuery.class);
		StoredBlock ancestor = mock(StoredBlock.class);
		when(ancestor.getHeight()).thenReturn(10L);
		when(chainQuery.findCommonAncestor(any())).thenReturn(Optional.of(ancestor));
		when(chainQuery.getLatestBlockHeight()).thenReturn(Optional.of(10_000L));
		when(chainQuery.findStoredBlockHeadersByHeightRange(11L, expectedEndHeight)).thenReturn(List.of());

		RemotePeer peer = mock(RemotePeer.class);
		when(peer.negotiatedHeaderPageLimit()).thenReturn(negotiatedLimit);
		BlockSyncResponderService responder = new BlockSyncResponderService(chainQuery);
		responder.handleGetHeaders(new P2PHeadersRequestedEvent(
				this,
				7L,
				peer,
				List.of(Hash.ZERO),
				Hash.ZERO,
				Integer.MAX_VALUE));

		verify(chainQuery).findStoredBlockHeadersByHeightRange(11L, expectedEndHeight);
		verify(peer).sendBlockHeaders(List.of(), 7L);
	}
}
