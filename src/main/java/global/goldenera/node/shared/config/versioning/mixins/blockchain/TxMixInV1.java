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

import java.time.Instant;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.TxVersion;

/**
 * Mixin for Tx interface - ignores lazy-calculated properties.
 * - hash: recalculated on every call
 * - size: recalculated on every call
 * - sender: recovered from signature on every call (expensive ECDSA recovery)
 */
@JsonPropertyOrder({
        "version",
        "timestamp",
        "type",
        "network",
        "nonce",
        "recipient",
        "tokenAddress",
        "amount",
        "fee",
        "message",
        "payload",
        "referenceHash",
        "signature"
})
public abstract class TxMixInV1 implements Tx {

    @Override
    @JsonProperty("version")
    public abstract TxVersion getVersion();

    @Override
    @JsonProperty("timestamp")
    public abstract Instant getTimestamp();

    @Override
    @JsonProperty("type")
    public abstract TxType getType();

    @Override
    @JsonProperty("network")
    public abstract Network getNetwork();

    @Override
    @JsonProperty("nonce")
    public abstract Long getNonce();

    @Override
    @JsonProperty("recipient")
    public abstract Address getRecipient();

    @Override
    @JsonProperty("tokenAddress")
    public abstract Address getTokenAddress();

    @Override
    @JsonProperty("amount")
    public abstract Wei getAmount();

    @Override
    @JsonProperty("fee")
    public abstract Wei getFee();

    @Override
    @JsonProperty("message")
    public abstract Bytes getMessage();

    @Override
    @JsonProperty("payload")
    public abstract TxPayload getPayload();

    @Override
    @JsonProperty("referenceHash")
    public abstract Hash getReferenceHash();

    @Override
    @JsonProperty("signature")
    public abstract Signature getSignature();

    // --- IGNORED (Calculated / Cached) ---

    @Override
    @JsonIgnore
    public abstract Address getSender();

    @Override
    @JsonIgnore
    public abstract Hash getHash();

    @Override
    @JsonIgnore
    public abstract int getSize();
}