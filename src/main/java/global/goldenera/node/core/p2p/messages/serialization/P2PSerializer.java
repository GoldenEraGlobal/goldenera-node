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

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.core.p2p.messages.NetworkMessage;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockDto;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PTxDto;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolHashesDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolHashesReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolTxsDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolTxsReqDto;
import global.goldenera.node.core.p2p.netty.protocol.P2PMessageType;
import lombok.experimental.UtilityClass;

/**
 * Stateless utility for RLP encoding/decoding of specific Data Transfer
 * Objects.
 * This class does NOT handle the Envelope wrapping, only the payload content.
 */
@UtilityClass
public class P2PSerializer {

	/**
	 * Dispatches serialization based on the message type.
	 */
	public static Bytes encodePayload(P2PMessageType type, NetworkMessage payload) {
		if (payload == null) {
			return null;
		}
		switch (type) {
			case STATUS:
			case PING:
			case PONG:
				if (payload instanceof P2PStatusDto)
					return P2PStatusSerializer.encodeStatus((P2PStatusDto) payload);
				return null;
			case NEW_BLOCK:
				return P2PBlockSerializer.encodeBlock((P2PBlockDto) payload);
			case GET_BLOCK_HEADERS:
				return P2PSyncSerializer.encodeGetHeaders((P2PBlockHeadersReqDto) payload);
			case BLOCK_HEADERS:
				return P2PSyncSerializer.encodeBlockHeaders(((P2PBlockHeadersDto) payload).getHeaders());
			case GET_BLOCK_BODIES:
				return P2PSyncSerializer.encodeGetBodies((P2PBlockBodiesReqDto) payload);
			case BLOCK_BODIES:
				return P2PSyncSerializer.encodeBlockBodies((P2PBlockBodiesDto) payload);
			case NEW_MEMPOOL_TX:
				return P2PTxSerializer.encodeTx((P2PTxDto) payload);
			case GET_MEMPOOL_HASHES:
				return P2PSyncSerializer.encodeGetMempoolHashes((P2PMempoolHashesReqDto) payload);
			case GET_MEMPOOL_TRANSACTIONS:
				return P2PSyncSerializer.encodeGetMempoolTransactions((P2PMempoolTxsReqDto) payload);
			case MEMPOOL_HASHES:
				return P2PSyncSerializer.encodeMempoolHashes((P2PMempoolHashesDto) payload);
			case MEMPOOL_TRANSACTIONS:
				return P2PSyncSerializer.encodeMempoolTransactions((P2PMempoolTxsDto) payload);
			default:
				throw new IllegalArgumentException("Serialization not implemented for type: " + type);
		}
	}

	/**
	 * Dispatches deserialization based on the message type.
	 */
	public static NetworkMessage decodePayload(P2PMessageType type, Bytes payloadBytes) {
		if (payloadBytes == null || payloadBytes.isEmpty()) {
			return null;
		}
		switch (type) {
			case STATUS:
			case PONG:
				return P2PStatusSerializer.decodeStatus(payloadBytes);
			case NEW_BLOCK:
				return P2PBlockSerializer.decodeBlock(payloadBytes);
			case GET_BLOCK_HEADERS:
				return P2PSyncSerializer.decodeGetHeaders(payloadBytes);
			case BLOCK_HEADERS:
				return P2PSyncSerializer.decodeBlockHeaders(payloadBytes);
			case GET_BLOCK_BODIES:
				return P2PSyncSerializer.decodeGetBodies(payloadBytes);
			case BLOCK_BODIES:
				return P2PSyncSerializer.decodeBlockBodies(payloadBytes);
			case NEW_MEMPOOL_TX:
				return P2PTxSerializer.decodeTx(payloadBytes);
			case GET_MEMPOOL_HASHES:
				return P2PSyncSerializer.decodeMempoolHashes(payloadBytes);
			case MEMPOOL_HASHES:
				return P2PSyncSerializer.decodeMempoolHashes(payloadBytes);
			case GET_MEMPOOL_TRANSACTIONS:
				return P2PSyncSerializer.decodeGetMempoolTransactions(payloadBytes);
			case MEMPOOL_TRANSACTIONS:
				return P2PSyncSerializer.decodeMempoolTransactions(payloadBytes);
			default:
				return null;
		}
	}
}