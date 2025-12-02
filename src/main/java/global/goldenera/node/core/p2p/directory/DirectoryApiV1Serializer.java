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
package global.goldenera.node.core.p2p.directory;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.p2p.directory.v1.NodePingRequest;
import global.goldenera.node.core.p2p.directory.v1.NodePongPayload;
import global.goldenera.rlp.RLP;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class DirectoryApiV1Serializer {

	/**
	 * Encodes V1 PING request.
	 */
	public Bytes encodePingV1(NodePingRequest req) {
		return RLP.encode(out -> {
			out.startList();

			out.writeString(req.getP2pListenHost());
			out.writeIntScalar(req.getP2pListenPort());
			out.writeLongScalar(req.getP2pProtocolVersion());
			out.writeString(req.getSoftwareVersion());
			out.writeLongScalar(req.getTimestamp());
			out.writeIntScalar(req.getNetwork().getCode());
			out.writeBytes(Address.fromHexString(req.getNodeIdentity()));
			out.writeBigIntegerScalar(new BigInteger(req.getTotalDifficulty()));
			out.writeBytes32(Hash.fromHexString(req.getHeadHash()));
			out.writeLongScalar(req.getHeadHeight());

			out.endList();
		});
	}

	/**
	 * Encodes V1 PONG response.
	 */
	public Bytes encodePongV1(NodePongPayload res) {
		return RLP.encode(out -> {
			out.startList();

			out.writeList(res.getPeers(), (peer, out2) -> {
				out2.startList();
				out2.writeBytes(Address.fromHexString(peer.getNodeIdentity()));
				out2.writeString(peer.getP2pListenHost());
				out2.writeIntScalar(peer.getP2pListenPort());
				out2.writeIntScalar(peer.getNetwork().getCode());
				out2.writeString(peer.getSoftwareVersion());
				out2.writeBigIntegerScalar(new BigInteger(peer.getTotalDifficulty()));
				out2.writeBytes32(Hash.fromHexString(peer.getHeadHash()));
				out2.writeLongScalar(peer.getHeadHeight());
				out2.writeLongScalar(peer.getUpdatedAt());
				out2.endList();
			});

			out.writeLongScalar(res.getTimestamp());

			out.endList();
		});
	}
}
