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
package global.goldenera.node.core.p2p.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.p2p.netty.client.NettyClientService;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.p2p.services.DirectoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NodeConnectionManagerTest {

	@Test
	void repeatedStartDoesNotScheduleDuplicateLoops() {
		ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
		DirectoryService directory = mock(DirectoryService.class);
		PeerRegistry peers = mock(PeerRegistry.class);
		IdentityService identity = mock(IdentityService.class);
		when(directory.getP2PClientList()).thenReturn(List.of());
		when(peers.getAll()).thenReturn(List.of());
		when(identity.getNodeIdentityAddress()).thenReturn(mock(Address.class));
		NodeConnectionManager manager = new NodeConnectionManager(
				scheduler,
				new SimpleMeterRegistry(),
				directory,
				peers,
				mock(PeerReputationService.class),
				mock(NettyClientService.class),
				identity);

		assertThat(manager.start()).isTrue();
		assertThat(manager.start()).isFalse();
		assertThat(manager.isStarted()).isTrue();
		verify(scheduler, times(2)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
	}
}
