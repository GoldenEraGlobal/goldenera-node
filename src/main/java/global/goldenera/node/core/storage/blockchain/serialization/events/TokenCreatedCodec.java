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

import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadDecoder;
import global.goldenera.cryptoj.serialization.tx.payload.TxPayloadEncoder;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenCreated;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

public class TokenCreatedCodec implements BlockEventCodec<TokenCreated> {

    public static final TokenCreatedCodec INSTANCE = new TokenCreatedCodec();

    private TokenCreatedCodec() {}

    @Override
    public int currentVersion() {
        return 1;
    }

    @Override
    public void encode(RLPOutput out, TokenCreated event, int version) {
        out.writeBytes32(event.bipHash());
        out.writeBytes(event.derivedTokenAddress());
        out.writeIntScalar(event.txVersion().getCode());
        out.writeRaw(TxPayloadEncoder.INSTANCE.encode(event.payload(), event.txVersion()));
    }

    @Override
    public TokenCreated decode(RLPInput input, int version) {
        Hash bipHash = Hash.wrap(input.readBytes32());
        Address derivedAddress = Address.wrap(input.readBytes());
        int txVersionCode = input.readIntScalar();
        TxVersion txVersion = TxVersion.fromCode(txVersionCode);
        TxBipTokenCreatePayload payload = (TxBipTokenCreatePayload) TxPayloadDecoder.INSTANCE.decode(input.readRaw(), txVersion);

        return new TokenCreated(bipHash, derivedAddress, txVersion, payload);
    }

    @Override
    public boolean supportsVersion(int version) {
        return version == 1;
    }
}
