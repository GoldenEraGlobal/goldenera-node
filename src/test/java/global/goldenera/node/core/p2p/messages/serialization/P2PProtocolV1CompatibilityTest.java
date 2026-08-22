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
package global.goldenera.node.core.p2p.messages.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.node.core.p2p.manager.RemotePeer;
import global.goldenera.node.core.p2p.messages.P2PEnvelope;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockHeaderDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersDto;
import global.goldenera.node.core.p2p.netty.protocol.P2PMessageType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;

class P2PProtocolV1CompatibilityTest {

	private static final Address IDENTITY =
			Address.fromHexString("0x1111111111111111111111111111111111111111");
	private static final String HEADER_RLP =
			"f8d001078601a3185c6b58a00000000000000000000000000000000000000000000000000000000000000000"
			+ "a00000000000000000000000000000000000000000000000000000000000000000"
			+ "a00000000000000000000000000000000000000000000000000000000000000000"
			+ "8209c494111111111111111111111111111111111111111188000000000000002a"
			+ "b841" + "00".repeat(65);

	@Test
	void freezesEveryProtocolV1MessageCode() {
		assertThat(Arrays.stream(P2PMessageType.values()))
				.extracting(P2PMessageType::name, P2PMessageType::getCode)
				.containsExactly(
						tuple("STATUS", 0L),
						tuple("DISCONNECT", 1L),
						tuple("PING", 2L),
						tuple("PONG", 3L),
						tuple("NEW_BLOCK", 20L),
						tuple("GET_BLOCK_HEADERS", 40L),
						tuple("BLOCK_HEADERS", 41L),
						tuple("GET_BLOCK_BODIES", 42L),
						tuple("BLOCK_BODIES", 43L),
						tuple("NEW_MEMPOOL_TX", 60L),
						tuple("GET_MEMPOOL_HASHES", 61L),
						tuple("MEMPOOL_HASHES", 62L),
						tuple("GET_MEMPOOL_TRANSACTIONS", 63L),
						tuple("MEMPOOL_TRANSACTIONS", 64L));
	}

	@Test
	void freezesProtocolV1HeaderAndBodyResponseBytesAndRoundTrips() {
		P2PBlockHeadersDto headers = P2PBlockHeadersDto.builder()
				.headers(List.of(P2PBlockHeaderDto.builder().blockHeader(header()).build()))
				.build();
		P2PBlockBodiesDto bodies = P2PBlockBodiesDto.builder()
				.bodies(List.of(List.of()))
				.build();

		Bytes encodedHeaders = P2PSerializer.encodePayload(P2PMessageType.BLOCK_HEADERS, headers);
		Bytes encodedBodies = P2PSerializer.encodePayload(P2PMessageType.BLOCK_BODIES, bodies);

		assertThat(encodedHeaders.toHexString()).isEqualTo("0xf8d6f8d4f8d2" + HEADER_RLP);
		assertThat(encodedBodies.toHexString()).isEqualTo("0xc2c1c0");
		P2PBlockHeadersDto decodedHeaders = (P2PBlockHeadersDto) P2PSerializer.decodePayload(
				P2PMessageType.BLOCK_HEADERS, encodedHeaders);
		P2PBlockBodiesDto decodedBodies = (P2PBlockBodiesDto) P2PSerializer.decodePayload(
				P2PMessageType.BLOCK_BODIES, encodedBodies);
		assertThat(decodedHeaders.getHeaders()).hasSize(1);
		assertThat(decodedHeaders.getHeaders().get(0).getBlockHeader().getHash()).isEqualTo(header().getHash());
		assertThat(decodedBodies.getBodies()).containsExactly(List.of());
	}

	@Test
	void legacyPeerWithoutCapabilitiesUsesOnlyProtocolV1SyncMessages() {
		EmbeddedChannel channel = new EmbeddedChannel();
		RemotePeer peer = new RemotePeer(channel, new SimpleMeterRegistry());
		peer.completeCapabilityNegotiation(List.of());

		peer.sendGetBlockHeaders(List.of(Hash.ZERO), Hash.ZERO, 16, 11);
		peer.sendGetBlockBodies(List.of(Hash.ZERO), 12);

		P2PEnvelope headersRequest = channel.readOutbound();
		P2PEnvelope bodiesRequest = channel.readOutbound();
		assertThat(peer.getCapabilitiesSnapshot()).isEmpty();
		assertThat(headersRequest.getMessageType()).isEqualTo(P2PMessageType.GET_BLOCK_HEADERS);
		assertThat(bodiesRequest.getMessageType()).isEqualTo(P2PMessageType.GET_BLOCK_BODIES);
		assertThat(List.of(headersRequest, bodiesRequest))
				.extracting(envelope -> envelope.getMessageType().getCode())
				.containsExactly(40L, 42L);
	}

	private BlockHeader header() {
		return BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(7)
				.timestamp(Instant.ofEpochMilli(1_800_000_007_000L))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(Hash.ZERO)
				.difficulty(BigInteger.valueOf(2_500))
				.coinbase(IDENTITY)
				.nonce(42)
				.signature(Signature.ZERO)
				.build();
	}
}
