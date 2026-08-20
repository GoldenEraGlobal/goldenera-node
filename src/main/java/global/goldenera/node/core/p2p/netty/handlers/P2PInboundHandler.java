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
package global.goldenera.node.core.p2p.netty.handlers;

import static global.goldenera.node.core.config.CoreAsyncConfig.P2P_RECEIVE_EXECUTOR;
import static lombok.AccessLevel.PRIVATE;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import global.goldenera.node.core.p2p.chainidentity.P2PChainIdentityPolicy;
import global.goldenera.node.core.p2p.chainidentity.P2PChainIdentityPolicy.Validation;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PBlockBodiesRequestedEvent;
import global.goldenera.node.core.p2p.events.P2PBlockReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PHandshakeCompletedEvent;
import global.goldenera.node.core.p2p.events.P2PHeadersReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PHeadersRequestedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolHashesReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolHashesRequestedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolTxReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolTxsReceivedEvent;
import global.goldenera.node.core.p2p.events.P2PMempoolTxsRequestedEvent;
import global.goldenera.node.core.p2p.events.P2PPeerHeadAdvancedEvent;
import global.goldenera.node.core.p2p.manager.PeerRegistry;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.messages.P2PEnvelope;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockDto;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockHeaderDto;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PTxDto;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolHashesDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolTxsDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolTxsReqDto;
import global.goldenera.node.core.p2p.messages.validation.P2PValidation;
import global.goldenera.node.core.p2p.netty.protocol.P2PMessageType;
import global.goldenera.node.core.p2p.netty.P2PChannelAttributes;
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.p2p.services.P2PStatusProvider;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.properties.ThrottlingProperties;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles business logic for incoming P2P messages.
 * It receives decoded P2PEnvelope objects from the codec.
 */
@Component
@Scope("prototype")
@Slf4j
@FieldDefaults(level = PRIVATE)
public class P2PInboundHandler extends SimpleChannelInboundHandler<P2PEnvelope> {

	private static final long MIN_SUPPORTED_PROTOCOL_VERSION = 1;

	final MeterRegistry registry;
	final Executor p2pReceiveExecutor;

	final GeneralProperties generalProperties;
	final ApplicationEventPublisher applicationEventPublisher;
	final PeerRegistry peerRegistry;
	final PeerReputationService reputationService;
	final P2PValidation p2pValidation;
	final P2PChainIdentityPolicy chainIdentityPolicy;
	final P2PStatusProvider statusProvider;
	final Bucket rateLimitBucket;
	RemotePeer peer;
	volatile HandshakeState handshakeState = HandshakeState.AWAITING_STATUS;
	volatile Validation acceptedChainIdentity;
	volatile long acceptedProtocolVersion;

	public P2PInboundHandler(ApplicationEventPublisher applicationEventPublisher, PeerRegistry peerRegistry,
			PeerReputationService reputationService,
			GeneralProperties generalProperties, P2PStatusProvider statusProvider,
			@Qualifier(P2P_RECEIVE_EXECUTOR) Executor p2pReceiveExecutor,
			P2PValidation p2pValidation, P2PChainIdentityPolicy chainIdentityPolicy,
			MeterRegistry registry, ThrottlingProperties throttlingProperties) {
		this.applicationEventPublisher = applicationEventPublisher;
		this.peerRegistry = peerRegistry;
		this.reputationService = reputationService;
		this.generalProperties = generalProperties;
		this.statusProvider = statusProvider;
		this.p2pReceiveExecutor = p2pReceiveExecutor;
		this.registry = registry;
		this.p2pValidation = p2pValidation;
		this.chainIdentityPolicy = chainIdentityPolicy;

		this.rateLimitBucket = Bucket.builder()
				.addLimit(limit -> limit.capacity(throttlingProperties.getP2pCapacity())
						.refillGreedy(throttlingProperties.getP2pRefillTokens(), Duration.ofSeconds(1)))
				.build();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		log.debug("[RAW] New Peer Connected: {}", ctx.channel().remoteAddress());
		peer = new RemotePeer(ctx.channel(), registry);
		peerRegistry.register(peer);
		// Send Handshake immediately on connect
		peer.sendStatus(statusProvider.currentStatus());
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		handshakeState = HandshakeState.REJECTED;
		peerRegistry.unregister(ctx.channel());
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.error("Netty Handler Exception for {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
		rejectProtocol(ctx, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, P2PEnvelope envelope) {
		if (!rateLimitBucket.tryConsume(1)) {
			log.warn("Peer {} exceeded rate limit. Closing connection.", getPeerLogInfo());
			rejectProtocol(ctx, "P2P rate limit exceeded");
			return;
		}

		registry.counter("p2p.messages.in", "type", envelope.getMessageType().name()).increment();
		if (envelope.getMessageType() == P2PMessageType.STATUS) {
			handleStatusSerially(ctx, envelope);
			return;
		}
		if (requiresCompletedHandshake(envelope.getMessageType())
				&& handshakeState != HandshakeState.COMPLETED) {
			rejectProtocol(ctx, "Received " + envelope.getMessageType() + " before a valid STATUS handshake");
			return;
		}
		try {
			Timer.Sample sample = Timer.start(registry);
			p2pReceiveExecutor.execute(() -> {
				try {
					processData(ctx, envelope);
				} finally {
					sample.stop(registry.timer("p2p.message.process_time", "type", envelope.getMessageType().name()));
				}
			});
		} catch (RejectedExecutionException re) {
			log.warn("Node Overloaded: Dropping message from {} (Queue full)", getPeerLogInfo());
		} catch (Exception e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			log.error("Protocol Error from {}: {}", getPeerLogInfo(), message);
			rejectProtocol(ctx, message);
		}
	}

	public void processData(ChannelHandlerContext ctx, P2PEnvelope envelope) {
		if (envelope.getMessageType() == P2PMessageType.STATUS) {
			rejectProtocol(ctx, "STATUS must be handled serially on the channel event loop");
			return;
		}
		if (requiresCompletedHandshake(envelope.getMessageType())
				&& handshakeState != HandshakeState.COMPLETED) {
			if (handshakeState != HandshakeState.REJECTED) {
				rejectProtocol(ctx, "Queued P2P traffic no longer has a valid handshake");
			}
			return;
		}
		try {
			P2PMessageType messageType = envelope.getMessageType();
			switch (messageType) {
				case PING:
					peer.sendPong(statusProvider.currentStatus());
					break;
				case PONG:
					if (!(envelope.getPayload() instanceof P2PStatusDto status)) {
						throw new GEFailedException("PONG status payload is required");
					}
					handlePongStatusUpdate(status);
					break;
				case NEW_BLOCK:
					P2PBlockDto blockDto = (P2PBlockDto) envelope.getPayload();
					try {
						p2pValidation.validateBlockDto(blockDto);
					} catch (GEValidationException e) {
						if (e.getMessage() != null && e.getMessage().contains("Seed block at height")) {
							log.warn("Ignoring future block {} from {}: {}", blockDto.getBlock().getHash(),
									getPeerLogInfo(), e.getMessage());
							return;
						}
						throw e;
					}
					applicationEventPublisher
							.publishEvent(new P2PBlockReceivedEvent(this, envelope.getRequestId(), peer,
									blockDto.getBlock()));
					break;
				case GET_BLOCK_HEADERS:
					P2PBlockHeadersReqDto getHeadersReq = (P2PBlockHeadersReqDto) envelope.getPayload();
					applicationEventPublisher
							.publishEvent(new P2PHeadersRequestedEvent(this, envelope.getRequestId(), peer,
									getHeadersReq.getLocators(),
									getHeadersReq.getStopHash(), getHeadersReq.getBatchSize()));
					break;
				case BLOCK_HEADERS:
					P2PBlockHeadersDto headersMsg = (P2PBlockHeadersDto) envelope.getPayload();
					// We do NOT validate headers here anymore.
					// Validation requires context (previous batches) which is held by
					// BlockSyncManager.
					// BlockSyncManager will validate the batch upon receipt.
					applicationEventPublisher.publishEvent(new P2PHeadersReceivedEvent(this, envelope.getRequestId(),
							peer, headersMsg
									.getHeaders().stream()
									.map(P2PBlockHeaderDto::getBlockHeader)
									.collect(Collectors.toList())));
					break;
				case GET_BLOCK_BODIES:
					P2PBlockBodiesReqDto getBodiesReq = (P2PBlockBodiesReqDto) envelope.getPayload();
					applicationEventPublisher
							.publishEvent(new P2PBlockBodiesRequestedEvent(this, envelope.getRequestId(), peer,
									getBodiesReq.getHashes()));
					break;
				case BLOCK_BODIES:
					P2PBlockBodiesDto bodiesMsg = (P2PBlockBodiesDto) envelope.getPayload();
					bodiesMsg.getBodies().parallelStream()
							.forEach(body -> body.parallelStream().forEach(p2pValidation::validateTxDto));
					applicationEventPublisher.publishEvent(new P2PBlockBodiesReceivedEvent(this,
							envelope.getRequestId(), peer,
							bodiesMsg.getBodies().stream()
									.map(txs -> txs.stream().map(P2PTxDto::getTx).collect(Collectors.toList()))
									.collect(Collectors.toList())));
					break;
				case GET_MEMPOOL_HASHES:
					applicationEventPublisher
							.publishEvent(new P2PMempoolHashesRequestedEvent(this, envelope.getRequestId(), peer));
					break;
				case MEMPOOL_HASHES:
					P2PMempoolHashesDto hashesMsg = (P2PMempoolHashesDto) envelope.getPayload();
					applicationEventPublisher.publishEvent(
							new P2PMempoolHashesReceivedEvent(this, envelope.getRequestId(), peer,
									hashesMsg.getHashes()));
					break;
				case GET_MEMPOOL_TRANSACTIONS:
					P2PMempoolTxsReqDto txsReq = (P2PMempoolTxsReqDto) envelope.getPayload();
					applicationEventPublisher
							.publishEvent(new P2PMempoolTxsRequestedEvent(this, envelope.getRequestId(), peer,
									txsReq.getHashes()));
					break;
				case MEMPOOL_TRANSACTIONS:
					P2PMempoolTxsDto txsMsg = (P2PMempoolTxsDto) envelope.getPayload();
					txsMsg.getTxs().parallelStream().forEach(p2pValidation::validateTxDto);
					applicationEventPublisher.publishEvent(
							new P2PMempoolTxsReceivedEvent(this, envelope.getRequestId(), peer,
									txsMsg.getTxs().stream().map(P2PTxDto::getTx).collect(Collectors.toList())));
					break;
				case NEW_MEMPOOL_TX:
					P2PTxDto txDto = (P2PTxDto) envelope.getPayload();
					p2pValidation.validateTxDto(txDto);
					applicationEventPublisher
							.publishEvent(new P2PMempoolTxReceivedEvent(this, envelope.getRequestId(), peer,
									txDto.getTx()));
					break;
				default:
					log.warn("Received unhandled message type: {}", envelope.getMessageType());
			}
		} catch (ClassCastException cce) {
			log.error("Payload type mismatch for message {}: {}", envelope.getMessageType(), cce.getMessage());
			rejectProtocol(ctx, "Payload type mismatch for " + envelope.getMessageType());
		} catch (Exception e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			boolean logAsError = peer.getIdentity() == null
					|| !reputationService.isBanned(peer.getIdentity())
					&& !message.toUpperCase().contains("BANNED");
			if (logAsError) {
				log.error("Protocol Error from {}: {}", getPeerLogInfo(), message);
			} else {
				log.debug("Protocol Error from {}: {}", getPeerLogInfo(), message);
			}
			rejectProtocol(ctx, message);
		}
	}

	private void handleStatusSerially(ChannelHandlerContext ctx, P2PEnvelope envelope) {
		if (handshakeState != HandshakeState.AWAITING_STATUS) {
			rejectProtocol(ctx, "Repeated STATUS handshake");
			return;
		}
		handshakeState = HandshakeState.VALIDATING;
		try {
			if (!(envelope.getPayload() instanceof P2PStatusDto status)) {
				throw new GEFailedException("STATUS payload is required");
			}
			handleStatus(status, envelope.getRequestId());
		} catch (Exception e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			log.warn("Rejected STATUS from {}: {}", getPeerLogInfo(), message);
			rejectProtocol(ctx, message);
		}
	}

	private void handleStatus(P2PStatusDto status, long requestId) {
		var expectedIdentity = peer.getChannel().attr(P2PChannelAttributes.EXPECTED_REMOTE_IDENTITY).get();
		if (expectedIdentity != null && !expectedIdentity.equals(status.getNodeIdentity())) {
			throw new GEFailedException("Configured peer identity mismatch: expected "
					+ expectedIdentity + " but received " + status.getNodeIdentity());
		}
		if (status.getNetwork() != generalProperties.getNetwork()) {
			throw new GEFailedException("Wrong Network: " + status.getNetwork());
		}
		if (status.getProtocolVersion() < MIN_SUPPORTED_PROTOCOL_VERSION) {
			throw new GEFailedException("Incompatible Protocol Version (Too Old)");
		}
		if (reputationService.isBanned(status.getNodeIdentity())) {
			throw new GEFailedException("Peer is BANNED locally");
		}
		Validation validatedChainIdentity = chainIdentityPolicy.validate(status);
		acceptedChainIdentity = validatedChainIdentity;
		acceptedProtocolVersion = status.getProtocolVersion();
		log.info("Handshake Success with {}: Identity={}", peer.getChannel().remoteAddress(), status.getNodeIdentity());
		reputationService.recordSuccess(status.getNodeIdentity());
		updatePeerState(status);
		peerRegistry.updateIdentity(peer.getChannel(), status.getNodeIdentity());
		handshakeState = HandshakeState.COMPLETED;
		applicationEventPublisher
				.publishEvent(new P2PHandshakeCompletedEvent(this, requestId, peer, status));
	}

	private void handlePongStatusUpdate(P2PStatusDto status) {
		Validation pongChainIdentity = chainIdentityPolicy.validate(status);
		if (status.getNetwork() != generalProperties.getNetwork()
				|| status.getProtocolVersion() != acceptedProtocolVersion
				|| peer.getIdentity() == null
				|| !peer.getIdentity().equals(status.getNodeIdentity())
				|| !pongChainIdentity.equals(acceptedChainIdentity)) {
			throw new GEFailedException("PONG status identity drift");
		}
		peer.updateLatency();
		boolean advanced;
		synchronized (peer) {
			advanced = status.getCumulativeDifficulty().compareTo(peer.getTotalDifficulty()) > 0;
			if (advanced) {
				updatePeerHeadState(status);
			}
		}
		if (advanced) {
			applicationEventPublisher.publishEvent(new P2PPeerHeadAdvancedEvent(this, peer, status));
		}
	}

	private void updatePeerState(P2PStatusDto status) {
		peer.setIdentity(status.getNodeIdentity());
		peer.setClientVersion(status.getNodeVersion());
		peer.setTotalDifficulty(status.getCumulativeDifficulty());
		peer.setHeadHash(status.getBestBlockHeader().getHash());
		peer.setHeadHeight(status.getBestBlockHeader().getHeight());
	}

	private void updatePeerHeadState(P2PStatusDto status) {
		peer.setTotalDifficulty(status.getCumulativeDifficulty());
		peer.setHeadHash(status.getBestBlockHeader().getHash());
		peer.setHeadHeight(status.getBestBlockHeader().getHeight());
	}

	private boolean requiresCompletedHandshake(P2PMessageType messageType) {
		return messageType != P2PMessageType.STATUS;
	}

	private void rejectProtocol(ChannelHandlerContext ctx, String reason) {
		handshakeState = HandshakeState.REJECTED;
		if (peer != null && peer.getIdentity() != null) {
			reputationService.recordFailure(peer.getIdentity());
		}
		log.debug("Closing peer {}: {}", getPeerLogInfo(), reason);
		ctx.close();
	}

	private String getPeerLogInfo() {
		if (peer == null) {
			return "unregistered-peer";
		}
		return peer.getIdentity() != null ? peer.getIdentity().toChecksumAddress()
				: String.valueOf(peer.getChannel().remoteAddress());
	}

	private enum HandshakeState {
		AWAITING_STATUS,
		VALIDATING,
		COMPLETED,
		REJECTED
	}
}
