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
package global.goldenera.node.core.p2p.netty.client;

import static lombok.AccessLevel.PRIVATE;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.p2p.netty.P2PChannelInitializer;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.p2p.services.DirectoryService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class NettyClientService implements DisposableBean {

	P2PChannelInitializer p2pChannelInitializer;
	PeerReputationService peerReputationService;
	EventLoopGroup workerGroup = new NioEventLoopGroup();

	/**
	 * Attempts to connect to a remote peer.
	 * The operation is asynchronous.
	 *
	 * @param p2pDirClient
	 *            Directory Service P2P Client
	 */
	public void connect(DirectoryService.P2PClient p2pDirClient) {
		String host = p2pDirClient.getP2pListenHost().trim();
		int port = p2pDirClient.getP2pListenPort().intValue();
		log.debug("Initiating connection to {}:{}", host, port);

		Bootstrap b = new Bootstrap();
		b.group(workerGroup)
				.channel(NioSocketChannel.class)
				.handler(p2pChannelInitializer)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.TCP_NODELAY, true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
				.option(ChannelOption.SO_SNDBUF, 1024 * 1024)
				.option(ChannelOption.SO_RCVBUF, 1024 * 1024);

		ChannelFuture f = b.connect(host, port);
		f.addListener(future -> {
			if (future.isSuccess()) {
				log.info("Successfully connected to peer: {}:{}", host, port);
			} else {
				log.warn("Failed to connect to peer {}:{}. Cause: {}", host, port, future.cause().getMessage());
				if (p2pDirClient.getIdentity() != null) {
					peerReputationService.recordFailure(p2pDirClient.getIdentity());
				}
			}
		});
	}

	@Override
	public void destroy() {
		log.info("Shutting down Netty Client Worker Group...");
		workerGroup.shutdownGracefully();
	}
}