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

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AuthorityAdded;
import global.goldenera.rlp.RLPInput;
import global.goldenera.rlp.RLPOutput;

public class AuthorityAddedCodec implements BlockEventCodec<AuthorityAdded> {

    public static final AuthorityAddedCodec INSTANCE = new AuthorityAddedCodec();

    @Override
    public int currentVersion() {
        return 1;
    }

    @Override
    public void encode(RLPOutput out, AuthorityAdded event, int version) {
        out.writeBytes32(event.bipHash());
        out.writeBytes(event.authorityAddress());
    }

    @Override
    public AuthorityAdded decode(RLPInput input, int version) {
        Hash bipHash = Hash.wrap(input.readBytes32());
        Address authorityAddress = Address.wrap(input.readBytes());
        return new AuthorityAdded(bipHash, authorityAddress);
    }

    @Override
    public boolean supportsVersion(int version) {
        return version == 1;
    }
}
