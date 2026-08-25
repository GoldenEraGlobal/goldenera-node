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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.p2p.manager.PeerRegistry;

class BlockPropagationServiceTest {

	@Test
	void syncEventsAreFilteredBeforeTheyCanEnterAsyncQueue() {
		PeerRegistry peers = mock(PeerRegistry.class);
		CapturingExecutor executor = new CapturingExecutor();
		BlockPropagationService service = new BlockPropagationService(peers, executor);
		BlockConnectedEvent event = event(ConnectedSource.SYNC);

		service.onBlockConnected(event);

		assertThat(executor.task).isNull();
		verifyNoInteractions(peers);
	}

	@Test
	void liveEventQueuesOnlyHeaderAnnouncementWork() {
		PeerRegistry peers = mock(PeerRegistry.class);
		when(peers.getBestPeers(anyInt(), any())).thenReturn(List.of());
		CapturingExecutor executor = new CapturingExecutor();
		BlockPropagationService service = new BlockPropagationService(peers, executor);

		service.onBlockConnected(event(ConnectedSource.MINER));
		assertThat(executor.task).isNotNull();
		executor.task.run();

		verify(peers).getBestPeers(anyInt(), any());
	}

	private BlockConnectedEvent event(ConnectedSource source) {
		BlockHeader header = mock(BlockHeader.class);
		Block block = mock(Block.class);
		when(block.getHeader()).thenReturn(header);
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		when(event.getConnectedSource()).thenReturn(source);
		when(event.getBlock()).thenReturn(block);
		return event;
	}

	private static final class CapturingExecutor implements Executor {
		private Runnable task;

		@Override
		public void execute(Runnable command) {
			task = command;
		}
	}
}
