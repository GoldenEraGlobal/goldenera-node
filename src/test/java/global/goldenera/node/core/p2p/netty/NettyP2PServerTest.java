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
package global.goldenera.node.core.p2p.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.properties.P2PProperties;

class NettyP2PServerTest {

	@Test
	void bindsPortZeroObservablyAndHoldsTheChannel() {
		NettyP2PServer server = server(0);
		try {
			int boundPort = server.start();

			assertThat(boundPort).isPositive();
			assertThat(server.isBound()).isTrue();
			assertThat(server.boundPort()).isEqualTo(boundPort);
			assertThat(server.start()).isEqualTo(boundPort);
		} finally {
			server.stop();
		}
		assertThat(server.isBound()).isFalse();
	}

	@Test
	void occupiedPortFailsSynchronously() throws IOException {
		try (ServerSocket occupied = new ServerSocket(0)) {
			NettyP2PServer server = server(occupied.getLocalPort());
			try {
				assertThatThrownBy(server::start)
						.isInstanceOf(P2PServerBindException.class)
						.hasMessageContaining(Integer.toString(occupied.getLocalPort()));
				assertThat(server.isBound()).isFalse();
			} finally {
				server.stop();
			}
		}
	}

	private NettyP2PServer server(int port) {
		P2PProperties properties = new P2PProperties();
		properties.setHost("127.0.0.1");
		properties.setPort(port);
		return new NettyP2PServer(mock(P2PChannelInitializer.class), properties);
	}
}
