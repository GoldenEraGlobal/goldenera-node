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
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.node.IdentityService;
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
import global.goldenera.node.core.p2p.reputation.PeerReputationService;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
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

	final ChainQuery chainQueryService;
	final GeneralProperties generalProperties;
	final IdentityService identityService;
	final ApplicationEventPublisher applicationEventPublisher;
	final PeerRegistry peerRegistry;
	final PeerReputationService reputationService;
	final P2PValidation p2pValidation;
	final Bucket rateLimitBucket;
	RemotePeer peer;

	public P2PInboundHandler(ApplicationEventPublisher applicationEventPublisher, PeerRegistry peerRegistry,
			PeerReputationService reputationService,
			GeneralProperties generalProperties, IdentityService identityService,
			ChainQuery chainQueryService, @Qualifier(P2P_RECEIVE_EXECUTOR) Executor p2pReceiveExecutor,
			P2PValidation p2pValidation, MeterRegistry registry, ThrottlingProperties throttlingProperties) {
		this.applicationEventPublisher = applicationEventPublisher;
		this.peerRegistry = peerRegistry;
		this.reputationService = reputationService;
		this.generalProperties = generalProperties;
		this.identityService = identityService;
		this.chainQueryService = chainQueryService;
		this.p2pReceiveExecutor = p2pReceiveExecutor;
		this.registry = registry;
		this.p2pValidation = p2pValidation;

		this.rateLimitBucket = Bucket.builder()
				.addLimit(limit -> limit.capacity(throttlingProperties.getP2pCapacity())
						.refillGreedy(throttlingProperties.getP2pRefillTokens(), Duration.ofSeconds(1)))
				.build();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		log.info("New Peer Connected: {}", ctx.channel().remoteAddress());
		peer = new RemotePeer(ctx.channel(), registry);
		peerRegistry.register(peer);
		// Send Handshake immediately on connect
		peer.sendStatus(createCurrentStatus());
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		peerRegistry.unregister(ctx.channel());
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.error("Netty Handler Exception for {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
		if (peer != null && peer.getIdentity() != null) {
			reputationService.recordFailure(peer.getIdentity());
		}
		ctx.close();
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, P2PEnvelope envelope) {
		if (!rateLimitBucket.tryConsume(1)) {
			log.warn("Peer {} exceeded rate limit. Closing connection.", getPeerLogInfo());
			if (peer.getIdentity() != null) {
				reputationService.recordFailure(peer.getIdentity());
			}
			ctx.close();
			return;
		}

		registry.counter("p2p.messages.in", "type", envelope.getMessageType().name()).increment();
		try {
			Timer.Sample sample = Timer.start(registry);
			p2pReceiveExecutor.execute(() -> {
				try {
					processData(ctx, envelope);
				} finally {
					sample.stop(registry.timer("p2p.message.process_time", "type", envelope.getMessageType().name()));
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException re) {
			log.warn("Node Overloaded: Dropping message from {} (Queue full)", getPeerLogInfo());
		} catch (Exception e) {
			log.error("Protocol Error from {}: {}", getPeerLogInfo(), e.getMessage());
			if (peer.getIdentity() != null) {
				reputationService.recordFailure(peer.getIdentity());
			}
			ctx.close();
		}
	}

	public void processData(ChannelHandlerContext ctx, P2PEnvelope envelope) {
		try {
			P2PMessageType messageType = envelope.getMessageType();
			switch (messageType) {
				case PING:
					peer.sendPong(createCurrentStatus());
					break;
				case PONG:
					peer.updateLatency();
					if (envelope.getPayload() instanceof P2PStatusDto) {
						handlePongStatusUpdate((P2PStatusDto) envelope.getPayload());
					}
					break;
				case STATUS:
					handleStatus((P2PStatusDto) envelope.getPayload(), envelope.getRequestId());
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
			reputationService.recordFailure(peer.getIdentity());
		} catch (Exception e) {
			log.error("Protocol Error from {}: {}", getPeerLogInfo(), e.getMessage());
			if (peer.getIdentity() != null) {
				reputationService.recordFailure(peer.getIdentity());
			}
			ctx.close();
		}
	}

	private P2PStatusDto createCurrentStatus() {
		StoredBlock latestBlock = chainQueryService.getLatestStoredBlockOrThrow();
		return P2PStatusDto.builder()
				.protocolVersion(Constants.P2P_PROTOCOL_VERSION)
				.nodeVersion(Constants.NODE_VERSION)
				.network(generalProperties.getNetwork())
				.cumulativeDifficulty(latestBlock.getCumulativeDifficulty())
				.bestBlockHeader(latestBlock.getBlock().getHeader())
				.nodeIdentity(identityService.getNodeIdentityAddress())
				.capabilities(new ArrayList<>())
				.build();
	}

	private void handleStatus(P2PStatusDto status, long requestId) {
		if (status.getNetwork() != generalProperties.getNetwork()) {
			reputationService.ban(status.getNodeIdentity());
			throw new GEFailedException("Wrong Network: " + status.getNetwork());
		}
		if (status.getProtocolVersion() < MIN_SUPPORTED_PROTOCOL_VERSION) {
			throw new GEFailedException("Incompatible Protocol Version (Too Old)");
		}
		if (reputationService.isBanned(status.getNodeIdentity())) {
			throw new GEFailedException("Peer is BANNED locally");
		}
		log.info("Handshake Success with {}: Identity={}", peer.getChannel().remoteAddress(), status.getNodeIdentity());
		reputationService.recordSuccess(status.getNodeIdentity());
		updatePeerState(status);
		peerRegistry.updateIdentity(peer.getChannel(), status.getNodeIdentity());
		applicationEventPublisher
				.publishEvent(new P2PHandshakeCompletedEvent(this, requestId, peer, status));
	}

	private void handlePongStatusUpdate(P2PStatusDto status) {
		if (status.getCumulativeDifficulty().compareTo(peer.getTotalDifficulty()) > 0) {
			updatePeerState(status);
		}
	}

	private void updatePeerState(P2PStatusDto status) {
		peer.setIdentity(status.getNodeIdentity());
		peer.setClientVersion(status.getNodeVersion());
		peer.setTotalDifficulty(status.getCumulativeDifficulty());
		peer.setHeadHash(status.getBestBlockHeader().getHash());
		peer.setHeadHeight(status.getBestBlockHeader().getHeight());
	}

	private String getPeerLogInfo() {
		return peer.getIdentity() != null ? peer.getIdentity().toChecksumAddress()
				: peer.getChannel().remoteAddress().toString();
	}
}