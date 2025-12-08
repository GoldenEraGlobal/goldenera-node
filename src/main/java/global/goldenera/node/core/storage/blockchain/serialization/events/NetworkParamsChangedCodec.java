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

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.NetworkParamsChanged;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

public class NetworkParamsChangedCodec implements BlockEventCodec<NetworkParamsChanged> {

    public static final NetworkParamsChangedCodec INSTANCE = new NetworkParamsChangedCodec();

    @Override
    public int currentVersion() {
        return 1;
    }

    @Override
    public void encode(RLPOutput out, NetworkParamsChanged event, int version) {
        out.writeBytes32(event.bipHash());
        out.writeOptionalWeiScalar(event.newBlockReward());
        out.writeOptionalBytes(event.newBlockRewardPoolAddress());
        out.writeOptionalLongScalar(event.newTargetMiningTimeMs());
        out.writeOptionalLongScalar(event.newAsertHalfLifeBlocks());
        out.writeOptionalBigIntegerScalar(event.newMinDifficulty());
        out.writeOptionalWeiScalar(event.newMinTxBaseFee());
        out.writeOptionalWeiScalar(event.newMinTxByteFee());
    }

    @Override
    public NetworkParamsChanged decode(RLPInput input, int version) {
        Hash bipHash = Hash.wrap(input.readBytes32());
        Wei newBlockReward = input.readOptionalWeiScalar();
        Bytes addressBytes = input.readOptionalBytes();
        Address newBlockRewardPoolAddress = addressBytes != null ? Address.wrap(addressBytes) : null;
        Long newTargetMiningTimeMs = input.readOptionalLongScalar();
        Long newAsertHalfLifeBlocks = input.readOptionalLongScalar();
        BigInteger newMinDifficulty = input.readOptionalBigIntegerScalar();
        Wei newMinTxBaseFee = input.readOptionalWeiScalar();
        Wei newMinTxByteFee = input.readOptionalWeiScalar();
        return new NetworkParamsChanged(bipHash, newBlockReward, newBlockRewardPoolAddress,
                newTargetMiningTimeMs, newAsertHalfLifeBlocks, newMinDifficulty,
                newMinTxBaseFee, newMinTxByteFee);
    }

    @Override
    public boolean supportsVersion(int version) {
        return version == 1;
    }
}
