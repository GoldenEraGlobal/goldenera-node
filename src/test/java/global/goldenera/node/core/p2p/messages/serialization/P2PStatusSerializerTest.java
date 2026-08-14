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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.serialization.blockheader.BlockHeaderEncoder;
import global.goldenera.node.core.p2p.chainidentity.P2PChainCapabilityCodec;
import global.goldenera.node.core.p2p.messages.dtos.handshake.P2PStatusDto;
import global.goldenera.rlp.RLP;

class P2PStatusSerializerTest {

	private static final Address IDENTITY =
			Address.fromHexString("0x1111111111111111111111111111111111111111");

	@Test
	void frozenProtocolV1StatusBytesAndLegacyNetworkCodesRemainUnchanged() {
		Bytes mainnet = P2PStatusSerializer.encodeStatus(status(Network.MAINNET, List.of()));
		Bytes testnet = P2PStatusSerializer.encodeStatus(status(Network.TESTNET, List.of()));

		assertThat(mainnet.toHexString()).isEqualTo("0xf8f30185302e312e3180941111111111111111111111111111111111111111c0823039f8d001078601a3185c6b58a00000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000008209c494111111111111111111111111111111111111111188000000000000002ab8410000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
		assertThat(testnet.toHexString()).isEqualTo("0xf8f30185302e312e3101941111111111111111111111111111111111111111c0823039f8d001078601a3185c6b58a00000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000008209c494111111111111111111111111111111111111111188000000000000002ab8410000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
		assertThat(P2PStatusSerializer.decodeStatus(mainnet).getProtocolVersion()).isEqualTo(1);
		assertThat(P2PStatusSerializer.decodeStatus(mainnet).getNetwork().getCode()).isZero();
		assertThat(P2PStatusSerializer.decodeStatus(testnet).getNetwork().getCode()).isEqualTo(1);
	}

	@Test
	void exactRoundTripPreservesOneAtomicChainCapability() {
		String capability = "ge.chain.v1:AShzYW5kYm94LTAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZm"
				+ "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqoB854F1aTvBUUGJeVzBLaPgB4qariH1fOztX5cjKhEnT0";
		P2PStatusDto decoded = P2PStatusSerializer.decodeStatus(P2PStatusSerializer.encodeStatus(
				status(Network.TESTNET, List.of("chain-identity-v1", capability))));

		assertThat(decoded.getCapabilities()).containsExactly("chain-identity-v1", capability);
		assertThat(decoded.getProtocolVersion()).isEqualTo(1);
	}

	@Test
	void rejectsDuplicateOversizedAndExtraStatusFieldsOnDecode() {
		P2PStatusDto duplicate = status(Network.TESTNET, List.of("same", "same"));
		assertThatThrownBy(() -> P2PStatusSerializer.encodeStatus(duplicate))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Duplicate");

		Bytes oversizedCapability = rawStatus(
				List.of("x".repeat(P2PChainCapabilityCodec.MAX_CAPABILITY_BYTES + 1)),
				false);
		assertThatThrownBy(() -> P2PStatusSerializer.decodeStatus(oversizedCapability))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bound");

		Bytes extraField = rawStatus(List.of(), true);
		assertThatThrownBy(() -> P2PStatusSerializer.decodeStatus(extraField))
				.isInstanceOf(RuntimeException.class);
	}

	private Bytes rawStatus(List<String> capabilities, boolean extraField) {
		P2PStatusDto status = status(Network.TESTNET, capabilities);
		return RLP.encode(out -> {
			out.startList();
			out.writeLongScalar(status.getProtocolVersion());
			out.writeString(status.getNodeVersion());
			out.writeIntScalar(status.getNetwork().getCode());
			out.writeBytes(status.getNodeIdentity());
			out.writeList(capabilities, (capability, item) -> item.writeString(capability));
			out.writeBigIntegerScalar(status.getCumulativeDifficulty());
			out.writeRaw(BlockHeaderEncoder.INSTANCE.encode(status.getBestBlockHeader(), true));
			if (extraField) {
				out.writeString("unexpected");
			}
			out.endList();
		});
	}

	private P2PStatusDto status(Network network, List<String> capabilities) {
		return P2PStatusDto.builder()
				.protocolVersion(1)
				.nodeVersion("0.1.1")
				.network(network)
				.nodeIdentity(IDENTITY)
				.capabilities(capabilities)
				.cumulativeDifficulty(BigInteger.valueOf(12345))
				.bestBlockHeader(BlockHeaderImpl.builder()
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
						.build())
				.build();
	}
}
