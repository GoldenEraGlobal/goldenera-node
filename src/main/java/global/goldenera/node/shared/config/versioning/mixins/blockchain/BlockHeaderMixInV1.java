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
package global.goldenera.node.shared.config.versioning.mixins.blockchain;

import java.math.BigInteger;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;

@JsonPropertyOrder({
        "version",
        "height",
        "timestamp",
        "previousHash",
        "txRootHash",
        "stateRootHash",
        "difficulty",
        "coinbase",
        "nonce",
        "signature"
})
public abstract class BlockHeaderMixInV1 implements BlockHeader {

    @Override
    @JsonProperty("version")
    public abstract BlockVersion getVersion();

    @Override
    @JsonProperty("height")
    public abstract long getHeight();

    @Override
    @JsonProperty("timestamp")
    public abstract Instant getTimestamp();

    @Override
    @JsonProperty("previousHash")
    public abstract Hash getPreviousHash();

    @Override
    @JsonProperty("txRootHash")
    public abstract Hash getTxRootHash();

    @Override
    @JsonProperty("stateRootHash")
    public abstract Hash getStateRootHash();

    @Override
    @JsonProperty("difficulty")
    public abstract BigInteger getDifficulty();

    @Override
    @JsonProperty("coinbase")
    public abstract Address getCoinbase();

    @Override
    @JsonProperty("nonce")
    public abstract long getNonce();

    @Override
    @JsonProperty("signature")
    public abstract Signature getSignature();

    // --- IGNORED (Calculated / Cached) ---

    @Override
    @JsonIgnore
    public abstract Hash getHash();

    @Override
    @JsonIgnore
    public abstract int getSize();
}