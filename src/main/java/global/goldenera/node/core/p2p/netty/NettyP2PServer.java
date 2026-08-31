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

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.properties.P2PProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class NettyP2PServer {

	private final P2PChannelInitializer channelInitializer;
	private final EventLoopGroup bossGroup;
	private final EventLoopGroup workerGroup;
	private final P2PProperties p2pProperties;
	private volatile Channel serverChannel;

	@Autowired
	public NettyP2PServer(P2PChannelInitializer channelInitializer, P2PProperties p2pProperties) {
		this(channelInitializer, p2pProperties, new NioEventLoopGroup(1),
				new NioEventLoopGroup(resolveWorkerThreads()));
	}

	static int resolveWorkerThreads() {
		return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
	}

	NettyP2PServer(P2PChannelInitializer channelInitializer, P2PProperties p2pProperties,
			EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
		this.channelInitializer = channelInitializer;
		this.p2pProperties = p2pProperties;
		this.bossGroup = bossGroup;
		this.workerGroup = workerGroup;
	}

	public synchronized int start() {
		if (serverChannel != null && serverChannel.isActive()) {
			return boundPort();
		}
		int configuredPort = p2pProperties.getPort();
		log.info("Starting Netty P2P Server on port {}", configuredPort);
		ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(channelInitializer)
				.option(ChannelOption.SO_BACKLOG, 128)
				.option(ChannelOption.SO_REUSEADDR, true)
				.childOption(ChannelOption.SO_KEEPALIVE, true)
				.childOption(ChannelOption.TCP_NODELAY, true)
				.childOption(ChannelOption.SO_SNDBUF, 256 * 1024)
				.childOption(ChannelOption.SO_RCVBUF, 256 * 1024)
				.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
						new WriteBufferWaterMark(1024 * 1024, 4 * 1024 * 1024));

		ChannelFuture bind = bootstrap.bind(configuredPort).awaitUninterruptibly();
		if (!bind.isSuccess()) {
			throw new P2PServerBindException("Failed to bind P2P listener on port " + configuredPort,
					bind.cause());
		}
		serverChannel = bind.channel();
		log.info("Netty P2P Server bound on port {}", boundPort());
		return boundPort();
	}

	public boolean isBound() {
		Channel channel = serverChannel;
		return channel != null && channel.isActive();
	}

	public int boundPort() {
		Channel channel = serverChannel;
		if (channel == null || !(channel.localAddress() instanceof InetSocketAddress address)) {
			throw new IllegalStateException("P2P listener is not bound");
		}
		return address.getPort();
	}

	@PreDestroy
	public synchronized void stop() {
		Channel channel = serverChannel;
		serverChannel = null;
		if (channel != null) {
			channel.close().awaitUninterruptibly();
		}
		bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
		workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
	}
}
