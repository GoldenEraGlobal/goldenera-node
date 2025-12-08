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

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.FeesCollected;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

public class FeesCollectedCodec implements BlockEventCodec<FeesCollected> {

    public static final FeesCollectedCodec INSTANCE = new FeesCollectedCodec();

    @Override
    public int currentVersion() {
        return 1;
    }

    @Override
    public void encode(RLPOutput out, FeesCollected event, int version) {
        out.writeBytes(event.minerAddress());
        out.writeWeiScalar(event.amount());
    }

    @Override
    public FeesCollected decode(RLPInput input, int version) {
        Address minerAddress = Address.wrap(input.readBytes());
        Wei amount = input.readWeiScalar();
        return new FeesCollected(minerAddress, amount);
    }

    @Override
    public boolean supportsVersion(int version) {
        return version == 1;
    }
}
