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
package global.goldenera.node.core.p2p.netty.codec;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.node.core.p2p.messages.NetworkMessage;
import global.goldenera.node.core.p2p.messages.P2PEnvelope;
import global.goldenera.node.core.p2p.messages.serialization.P2PSerializer;
import global.goldenera.node.core.p2p.netty.protocol.P2PMessageType;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the encoding of P2PEnvelope to Bytes (RLP)
 * and decoding of Bytes (RLP) to P2PEnvelope.
 */
@Slf4j
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class P2PMessageCodec extends ByteToMessageCodec<P2PEnvelope> {

	@Override
	protected void encode(ChannelHandlerContext ctx, P2PEnvelope msg, ByteBuf out) {
		Bytes rlpEncoded = RLP.encode(writer -> {
			writer.startList();
			writer.writeLongScalar(msg.getRequestId());
			writer.writeLongScalar(msg.getMessageType().getCode());
			Bytes payloadBytes = P2PSerializer.encodePayload(msg.getMessageType(), msg.getPayload());
			if (payloadBytes != null) {
				writer.writeBytes(payloadBytes);
			} else {
				writer.writeNull();
			}
			writer.endList();
		});

		out.writeBytes(rlpEncoded.toArrayUnsafe());
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		byte[] bytes = new byte[in.readableBytes()];
		in.readBytes(bytes);
		Bytes rlpBytes = Bytes.wrap(bytes);
		P2PMessageType type = null;
		try {
			RLPInput input = RLP.input(rlpBytes);
			input.enterList();

			long requestId = input.readLongScalar();
			int typeCode = input.readIntScalar();
			type = P2PMessageType.fromCode(typeCode);

			Bytes payloadRaw = null;
			if (!input.isEndOfCurrentList()) {
				payloadRaw = input.readBytes();
			}

			NetworkMessage payload = null;
			if (payloadRaw != null && !payloadRaw.isEmpty()) {
				// Check if it's an empty RLP string (0x80) which means null payload
				if (payloadRaw.size() == 1 && payloadRaw.get(0) == (byte) 0x80) {
					payload = null;
				} else {
					payload = P2PSerializer.decodePayload(type, payloadRaw);
				}
			}

			input.leaveList();

			out.add(new P2PEnvelope(requestId, type, payload));

		} catch (Exception e) {
			log.error("Failed to decode P2P message from {} (Type: {}): {}",
					ctx.channel().remoteAddress(), type, e.getMessage());
			log.error("RLP bytes (first 100): {}", rlpBytes.slice(0, Math.min(100, rlpBytes.size())).toHexString());
			ctx.close();
		}
	}
}