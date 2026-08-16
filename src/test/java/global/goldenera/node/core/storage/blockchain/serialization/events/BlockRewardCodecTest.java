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
package global.goldenera.node.core.storage.blockchain.serialization.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BlockReward;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;

class BlockRewardCodecTest {

	private static final Address MINER = Address.fromHexString("0x0000000000000000000000000000000000000001");
	private static final Address POOL = Address.fromHexString("0x0000000000000000000000000000000000000002");

	@Test
	void v2RoundTripPreservesUnlockBlockHeight() {
		BlockReward expected = new BlockReward(MINER, POOL, Wei.valueOf(25), 123L);

		assertThat(roundTrip(expected, 2)).isEqualTo(expected);
	}

	@Test
	void v1DecodeLeavesUnlockBlockHeightUnknown() {
		BlockReward decoded = roundTrip(new BlockReward(MINER, POOL, Wei.valueOf(25), 123L), 1);

		assertThat(decoded.minerAddress()).isEqualTo(MINER);
		assertThat(decoded.rewardPoolAddress()).isEqualTo(POOL);
		assertThat(decoded.amount()).isEqualTo(Wei.valueOf(25));
		assertThat(decoded.unlockBlockHeight()).isNull();
		assertThat(roundTrip(decoded, 2)).isEqualTo(decoded);
	}

	private BlockReward roundTrip(BlockReward event, int version) {
		Bytes encoded = RLP.encode(out -> {
			out.startList();
			BlockRewardCodec.INSTANCE.encode(out, event, version);
			out.endList();
		});
		RLPInput input = RLP.input(encoded);
		input.enterList();
		BlockReward decoded = BlockRewardCodec.INSTANCE.decode(input, version);
		input.leaveList();
		return decoded;
	}
}
