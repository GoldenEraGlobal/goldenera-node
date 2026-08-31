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
package global.goldenera.node.core.p2p.chainidentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class P2PChainCapabilityCodecTest {

	private static final P2PChainCapability CAPABILITY = new P2PChainCapability(
			1,
			"sandbox-00112233445566778899aabbccddeeff",
			"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"f39e05d5a4ef05450625e57304b68f801e2a6ab887d5f3b3b57e5c8ca8449d3d");

	private final P2PChainCapabilityCodec codec = new P2PChainCapabilityCodec();

	@Test
	void exactRoundTripHasOneFrozenCanonicalAtomicToken() {
		String encoded = codec.encode(CAPABILITY);

		assertThat(encoded).isEqualTo(
				"ge.chain.v1:AShzYW5kYm94LTAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmqqqqqqqqqqqqqqqq"
						+ "qqqqqqqqqqqqqqqqqqqqqqqqqqoB854F1aTvBUUGJeVzBLaPgB4qariH1fOztX5cjKhEnT0");
		assertThat(codec.decode(encoded)).isEqualTo(CAPABILITY);
		assertThat(codec.find(List.of("chain-identity-v1", encoded)))
				.contains(CAPABILITY);
	}

	@Test
	void nullableFingerprintRoundTripsExactly() {
		P2PChainCapability production = new P2PChainCapability(
				0,
				"mainnet",
				"0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
				null);

		assertThat(codec.decode(codec.encode(production))).isEqualTo(production);
	}

	@Test
	void rejectsMalformedPartialDuplicateAndOversizedCapabilities() {
		String valid = codec.encode(CAPABILITY);
		assertThatThrownBy(() -> codec.decode(P2PChainCapabilityCodec.PREFIX))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.decode(valid + "="))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.find(List.of("ge.chain.v2:abc")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.find(List.of(valid, valid)))
				.isInstanceOf(IllegalArgumentException.class);

		List<String> tooMany = new ArrayList<>();
		for (int index = 0; index <= P2PChainCapabilityCodec.MAX_CAPABILITIES; index++) {
			tooMany.add("capability-" + index);
		}
		assertThatThrownBy(() -> codec.find(tooMany))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.find(List.of("x".repeat(
				P2PChainCapabilityCodec.MAX_CAPABILITY_BYTES + 1))))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
