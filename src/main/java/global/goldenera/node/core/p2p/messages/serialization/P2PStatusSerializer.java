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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.serialization.blockheader.BlockHeaderDecoder;
import global.goldenera.cryptoj.serialization.blockheader.BlockHeaderEncoder;
import global.goldenera.node.core.p2p.chainidentity.P2PChainCapabilityCodec;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;
import lombok.experimental.UtilityClass;

@UtilityClass
public class P2PStatusSerializer {
	static final int MAX_NODE_VERSION_BYTES = 128;
	static final int MAX_CUMULATIVE_DIFFICULTY_BYTES = 32;
	static final int MAX_ENCODED_HEADER_BYTES = 2_048;
	private static final P2PChainCapabilityCodec CHAIN_CAPABILITY_CODEC = new P2PChainCapabilityCodec();

	public static Bytes encodeStatus(P2PStatusDto status) {
		validateStatus(status);
		return RLP.encode(out -> {
			out.startList();
			out.writeLongScalar(status.getProtocolVersion());
			out.writeString(status.getNodeVersion());
			out.writeIntScalar(status.getNetwork().getCode());
			out.writeBytes(status.getNodeIdentity());
			out.writeList(status.getCapabilities(), (cap, o) -> o.writeString(cap));
			out.writeBigIntegerScalar(status.getCumulativeDifficulty());
			out.writeRaw(BlockHeaderEncoder.INSTANCE.encode(status.getBestBlockHeader(), true));
			out.endList();
		});
	}

	public static P2PStatusDto decodeStatus(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		long protocolVersion = input.readLongScalar();
		String nodeVersion = readBoundedString(input, MAX_NODE_VERSION_BYTES, "node version");
		Network network = Network.fromCode(input.readIntScalar());
		Bytes nodeIdentityBytes = input.readBytes();
		if (nodeIdentityBytes.size() != Address.SIZE) {
			throw new IllegalArgumentException("P2P node identity must be exactly 20 bytes");
		}
		Address nodeIdentity = Address.wrap(nodeIdentityBytes);
		List<String> capabilities = readCapabilities(input);
		if (input.nextIsList() || input.nextSize() > MAX_CUMULATIVE_DIFFICULTY_BYTES) {
			throw new IllegalArgumentException("P2P cumulative difficulty exceeds the uint256 bound");
		}
		BigInteger cumulativeDifficulty = input.readBigIntegerScalar();
		if (!input.nextIsList() || input.nextSize() > MAX_ENCODED_HEADER_BYTES) {
			throw new IllegalArgumentException("P2P best block header exceeds the encoded bound");
		}
		BlockHeader bestBlockHeader = BlockHeaderDecoder.INSTANCE.decode(input.readRaw());
		input.leaveList();
		P2PStatusDto status = P2PStatusDto.builder()
				.protocolVersion(protocolVersion)
				.nodeVersion(nodeVersion)
				.network(network)
				.nodeIdentity(nodeIdentity)
				.capabilities(capabilities)
				.cumulativeDifficulty(cumulativeDifficulty)
				.bestBlockHeader(bestBlockHeader)
				.build();
		validateStatus(status);
		return status;
	}

	private static List<String> readCapabilities(RLPInput input) {
		if (!input.nextIsList()) {
			throw new IllegalArgumentException("P2P capabilities must be an RLP list");
		}
		input.enterList();
		List<String> capabilities = new ArrayList<>();
		Set<String> unique = new HashSet<>();
		while (!input.isEndOfCurrentList()) {
			if (capabilities.size() == P2PChainCapabilityCodec.MAX_CAPABILITIES) {
				throw new IllegalArgumentException("P2P capability count exceeds the bound");
			}
			String capability = readBoundedString(
					input,
					P2PChainCapabilityCodec.MAX_CAPABILITY_BYTES,
					"capability");
			if (!unique.add(capability)) {
				throw new IllegalArgumentException("P2P capabilities must not contain duplicates");
			}
			capabilities.add(capability);
		}
		input.leaveList();
		CHAIN_CAPABILITY_CODEC.find(capabilities);
		return List.copyOf(capabilities);
	}

	private static String readBoundedString(RLPInput input, int maxBytes, String field) {
		if (input.nextIsList() || input.nextSize() > maxBytes) {
			throw new IllegalArgumentException("P2P " + field + " exceeds the encoded bound");
		}
		Bytes value = input.readBytes();
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(value.toArrayUnsafe()))
					.toString();
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("P2P " + field + " is not canonical UTF-8", e);
		}
	}

	private static void validateStatus(P2PStatusDto status) {
		if (status == null || status.getNodeVersion() == null || status.getNetwork() == null
				|| status.getNodeIdentity() == null || status.getCumulativeDifficulty() == null
				|| status.getBestBlockHeader() == null) {
			throw new IllegalArgumentException("P2P status is incomplete");
		}
		if (status.getNodeVersion().getBytes(StandardCharsets.UTF_8).length > MAX_NODE_VERSION_BYTES) {
			throw new IllegalArgumentException("P2P node version exceeds the bound");
		}
		if (status.getCumulativeDifficulty().signum() < 0
				|| status.getCumulativeDifficulty().bitLength() > 256) {
			throw new IllegalArgumentException("P2P cumulative difficulty exceeds the uint256 bound");
		}
		CHAIN_CAPABILITY_CODEC.find(status.getCapabilities());
	}
}
