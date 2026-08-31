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

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.p2p.messages.NetworkMessage;
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
import global.goldenera.node.core.p2p.netty.protocol.P2PMessageType;
import global.goldenera.node.core.p2p.netty.protocol.P2PSyncProtocol;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@FieldDefaults(level = PRIVATE)
public class RemotePeer {

	final MeterRegistry registry;
	final Channel channel;
	final AtomicLong requestIdCounter = new AtomicLong(1);
	final AtomicInteger consecutiveUnwritableWrites = new AtomicInteger();

	volatile Address identity;
	volatile String clientVersion;
	@Getter(lombok.AccessLevel.NONE)
	@Setter(lombok.AccessLevel.NONE)
	volatile Set<String> capabilities = Set.of();
	volatile boolean capabilityNegotiationCompleted;

	volatile BigInteger totalDifficulty;
	volatile Hash headHash;
	volatile long headHeight;

	volatile Instant lastPongReceived = Instant.now();
	volatile Instant connectedAt;

	public RemotePeer(Channel channel, MeterRegistry registry) {
		this.channel = channel;
		this.connectedAt = Instant.now();
		this.registry = registry;
	}

	public void updateLatency() {
		this.lastPongReceived = Instant.now();
	}

	public long reserveRequestId() {
		return requestIdCounter.incrementAndGet();
	}

	public boolean canSend() {
		return channel.isActive() && channel.isWritable();
	}

	/**
	 * Commits the capabilities negotiated by a successful STATUS handshake.
	 * PONG status updates intentionally cannot renegotiate this snapshot.
	 */
	public synchronized void completeCapabilityNegotiation(List<String> negotiatedCapabilities) {
		Set<String> negotiated = negotiatedCapabilities == null || negotiatedCapabilities.isEmpty()
				? Set.of()
				: Collections.unmodifiableSet(new LinkedHashSet<>(negotiatedCapabilities));
		if (capabilityNegotiationCompleted) {
			if (capabilities.equals(negotiated)) {
				return;
			}
			throw new IllegalStateException("P2P capabilities were already negotiated");
		}
		capabilities = negotiated;
		capabilityNegotiationCompleted = true;
	}

	/** Returns the immutable capability snapshot negotiated at handshake time. */
	public Set<String> getCapabilities() {
		return capabilities;
	}

	/** Explicit snapshot alias for callers that need to retain the result. */
	public Set<String> getCapabilitiesSnapshot() {
		return capabilities;
	}

	public boolean supportsCapability(String capability) {
		return capability != null && capabilities.contains(capability);
	}

	/** Returns the header page bound fixed by the immutable STATUS snapshot. */
	public int negotiatedHeaderPageLimit() {
		return supportsCapability(P2PSyncProtocol.BLOCK_SYNC_V2_CAPABILITY)
				? P2PSyncProtocol.V2_HEADER_PAGE_LIMIT
				: P2PSyncProtocol.LEGACY_HEADER_PAGE_LIMIT;
	}

	/**
	 * Used for responses (Response), where we need to keep the original request ID.
	 */
	private boolean send(P2PMessageType type, NetworkMessage payload, long requestId) {
		if (!channel.isActive()) {
			registry.counter("p2p.messages.out.dropped", "reason", "inactive", "type", type.name()).increment();
			return false;
		}
		if (!channel.isWritable()) {
			registry.counter("p2p.messages.out.dropped", "reason", "unwritable", "type", type.name()).increment();
			if (consecutiveUnwritableWrites.incrementAndGet() >= 8) {
				channel.close();
			}
			return false;
		}
		channel.writeAndFlush(new P2PEnvelope(requestId, type, payload)).addListener(future -> {
			if (future.isSuccess()) {
				consecutiveUnwritableWrites.set(0);
				registry.counter("p2p.messages.out", "type", type.name()).increment();
			} else {
				registry.counter("p2p.messages.out.failed", "type", type.name()).increment();
				channel.close();
			}
		});
		return true;
	}

	private long sendNewRequest(P2PMessageType type, NetworkMessage payload) {
		long newId = requestIdCounter.incrementAndGet();
		send(type, payload, newId);
		return newId;
	}

	public void sendStatus(P2PStatusDto status) {
		send(P2PMessageType.STATUS, status, 0);
	}

	public void sendPing() {
		send(P2PMessageType.PING, null, 0);
	}

	public void sendPong(P2PStatusDto status) {
		send(P2PMessageType.PONG, status, 0);
	}

	public void disconnect(String reason) {
		channel.close();
	}

	public void sendBlock(Block block) {
		P2PBlockDto blockDto = P2PBlockDto.builder()
				.block(block)
				.build();
		sendNewRequest(P2PMessageType.NEW_BLOCK, blockDto);
	}

	public void sendMempoolTx(Tx tx) {
		P2PTxDto txDto = P2PTxDto.builder()
				.tx(tx)
				.build();
		sendNewRequest(P2PMessageType.NEW_MEMPOOL_TX, txDto);
	}

	public void sendGetBlockHeaders(List<Hash> locators, Hash stopHash, int batchSize, long requestId) {
		P2PBlockHeadersReqDto dto = P2PBlockHeadersReqDto.builder()
				.locators(locators)
				.stopHash(stopHash)
				.batchSize(batchSize)
				.build();
		send(P2PMessageType.GET_BLOCK_HEADERS, dto, requestId);
	}

	public long sendGetBlockHeaders(List<Hash> locators, Hash stopHash, int batchSize) {
		P2PBlockHeadersReqDto dto = P2PBlockHeadersReqDto.builder()
				.locators(locators)
				.stopHash(stopHash)
				.batchSize(batchSize)
				.build();
		return sendNewRequest(P2PMessageType.GET_BLOCK_HEADERS, dto);
	}

	public boolean sendBlockHeaders(List<BlockHeader> headers, long requestId) {
		P2PBlockHeadersDto dto = P2PBlockHeadersDto.builder()
				.headers(headers.stream().map(header -> P2PBlockHeaderDto.builder().blockHeader(header).build())
						.collect(Collectors.toList()))
				.build();
		return send(P2PMessageType.BLOCK_HEADERS, dto, requestId);
	}

	public void sendGetBlockBodies(List<Hash> hashes, long requestId) {
		P2PBlockBodiesReqDto dto = P2PBlockBodiesReqDto.builder()
				.hashes(hashes)
				.build();
		send(P2PMessageType.GET_BLOCK_BODIES, dto, requestId);
	}

	public long sendGetBlockBodies(List<Hash> hashes) {
		P2PBlockBodiesReqDto dto = P2PBlockBodiesReqDto.builder()
				.hashes(hashes)
				.build();
		return sendNewRequest(P2PMessageType.GET_BLOCK_BODIES, dto);
	}

	public boolean sendBlockBodies(List<List<Tx>> txs, long requestId) {
		if (!canSend()) {
			registry.counter("p2p.messages.out.dropped", "reason", "pre_encode_unwritable",
					"type", P2PMessageType.BLOCK_BODIES.name()).increment();
			return false;
		}
		P2PBlockBodiesDto dto = P2PBlockBodiesDto.builder()
				.bodies(txs.stream().map(
						tx -> tx.stream().map(tx2 -> P2PTxDto.builder().tx(tx2).build()).collect(Collectors.toList()))
						.collect(Collectors.toList()))
				.build();
		return send(P2PMessageType.BLOCK_BODIES, dto, requestId);
	}

	public long sendGetMempoolHashes() {
		return sendNewRequest(P2PMessageType.GET_MEMPOOL_HASHES, null);
	}

	public void sendMempoolHashes(List<Hash> hashes, long requestId) {
		P2PMempoolHashesDto dto = P2PMempoolHashesDto.builder()
				.hashes(hashes)
				.build();
		send(P2PMessageType.MEMPOOL_HASHES, dto, requestId);
	}

	public long sendGetMempoolTxs(List<Hash> hashes) {
		P2PMempoolTxsReqDto dto = P2PMempoolTxsReqDto.builder()
				.hashes(hashes)
				.build();
		return sendNewRequest(P2PMessageType.GET_MEMPOOL_TRANSACTIONS, dto);
	}

	public boolean sendMempoolTxs(List<Tx> txs, long requestId) {
		if (!canSend()) {
			registry.counter("p2p.messages.out.dropped", "reason", "pre_encode_unwritable",
					"type", P2PMessageType.MEMPOOL_TRANSACTIONS.name()).increment();
			return false;
		}
		P2PMempoolTxsDto dto = P2PMempoolTxsDto.builder()
				.txs(txs.stream().map(tx -> P2PTxDto.builder().tx(tx).build()).collect(Collectors.toList()))
				.build();
		return send(P2PMessageType.MEMPOOL_TRANSACTIONS, dto, requestId);
	}
}
