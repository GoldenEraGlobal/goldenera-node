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

import org.apache.tuweni.units.ethereum.Wei;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipVotePayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.BipVoteType;
import global.goldenera.cryptoj.enums.TxPayloadType;

public class TxPayloadMixInsV1 {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "payloadType", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TxBipAddressAliasAddPayload.class, name = "0"), // BIP_ADDRESS_ALIAS_ADD
            @JsonSubTypes.Type(value = TxBipAddressAliasRemovePayload.class, name = "1"), // BIP_ADDRESS_ALIAS_REMOVE
            @JsonSubTypes.Type(value = TxBipAuthorityAddPayload.class, name = "2"), // BIP_AUTHORITY_ADD
            @JsonSubTypes.Type(value = TxBipAuthorityRemovePayload.class, name = "3"), // BIP_AUTHORITY_REMOVE
            @JsonSubTypes.Type(value = TxBipNetworkParamsSetPayload.class, name = "4"), // BIP_NETWORK_PARAMS_SET
            @JsonSubTypes.Type(value = TxBipTokenBurnPayload.class, name = "5"), // BIP_TOKEN_BURN
            @JsonSubTypes.Type(value = TxBipTokenCreatePayload.class, name = "6"), // BIP_TOKEN_CREATE
            @JsonSubTypes.Type(value = TxBipTokenMintPayload.class, name = "7"), // BIP_TOKEN_MINT
            @JsonSubTypes.Type(value = TxBipTokenUpdatePayload.class, name = "8"), // BIP_TOKEN_UPDATE
            @JsonSubTypes.Type(value = TxBipVotePayload.class, name = "9") // BIP_VOTE
    })
    public abstract static class TxPayloadMixIn {
        @JsonProperty("payloadType")
        abstract TxPayloadType getPayloadType();
    }

    // --- Address Alias ---

    @JsonPropertyOrder({ "payloadType", "address", "alias" })
    public abstract static class TxBipAddressAliasAddPayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("address")
        abstract Address getAddress();

        @JsonProperty("alias")
        abstract String getAlias();
    }

    @JsonPropertyOrder({ "payloadType", "alias" })
    public abstract static class TxBipAddressAliasRemovePayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("alias")
        abstract String getAlias();
    }

    // --- Authority ---

    @JsonPropertyOrder({ "payloadType", "address" })
    public abstract static class TxBipAuthorityAddPayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("address")
        abstract Address getAddress();
    }

    @JsonPropertyOrder({ "payloadType", "address" })
    public abstract static class TxBipAuthorityRemovePayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("address")
        abstract Address getAddress();
    }

    // --- Network Params ---

    @JsonPropertyOrder({
            "payloadType", "blockReward", "blockRewardPoolAddress", "targetMiningTimeMs",
            "asertHalfLifeBlocks", "minDifficulty", "minTxBaseFee", "minTxByteFee"
    })
    public abstract static class TxBipNetworkParamsSetPayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("blockReward")
        abstract Wei getBlockReward();

        @JsonProperty("blockRewardPoolAddress")
        abstract Address getBlockRewardPoolAddress();

        @JsonProperty("targetMiningTimeMs")
        abstract Long getTargetMiningTimeMs();

        @JsonProperty("asertHalfLifeBlocks")
        abstract Long getAsertHalfLifeBlocks();

        @JsonProperty("minDifficulty")
        abstract BigInteger getMinDifficulty();

        @JsonProperty("minTxBaseFee")
        abstract Wei getMinTxBaseFee();

        @JsonProperty("minTxByteFee")
        abstract Wei getMinTxByteFee();
    }

    // --- Token Operations ---

    @JsonPropertyOrder({ "payloadType", "tokenAddress", "sender", "amount" })
    public abstract static class TxBipTokenBurnPayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("tokenAddress")
        abstract Address getTokenAddress();

        @JsonProperty("sender")
        abstract Address getSender();

        @JsonProperty("amount")
        abstract Wei getAmount();
    }

    @JsonPropertyOrder({
            "payloadType", "name", "smallestUnitName", "numberOfDecimals",
            "maxSupply", "userBurnable", "websiteUrl", "logoUrl"
    })
    public abstract static class TxBipTokenCreatePayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("name")
        abstract String getName();

        @JsonProperty("smallestUnitName")
        abstract String getSmallestUnitName();

        @JsonProperty("numberOfDecimals")
        abstract int getNumberOfDecimals();

        @JsonProperty("websiteUrl")
        abstract String getWebsiteUrl();

        @JsonProperty("logoUrl")
        abstract String getLogoUrl();

        @JsonProperty("maxSupply")
        abstract BigInteger getMaxSupply();

        @JsonProperty("userBurnable")
        abstract boolean isUserBurnable();
    }

    @JsonPropertyOrder({ "payloadType", "tokenAddress", "recipient", "amount" })
    public abstract static class TxBipTokenMintPayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("tokenAddress")
        abstract Address getTokenAddress();

        @JsonProperty("recipient")
        abstract Address getRecipient();

        @JsonProperty("amount")
        abstract Wei getAmount();
    }

    @JsonPropertyOrder({ "payloadType", "tokenAddress", "name", "smallestUnitName", "websiteUrl", "logoUrl" })
    public abstract static class TxBipTokenUpdatePayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("tokenAddress")
        abstract Address getTokenAddress();

        @JsonProperty("name")
        abstract String getName();

        @JsonProperty("smallestUnitName")
        abstract String getSmallestUnitName();

        @JsonProperty("websiteUrl")
        abstract String getWebsiteUrl();

        @JsonProperty("logoUrl")
        abstract String getLogoUrl();
    }

    // --- Vote ---

    @JsonPropertyOrder({ "payloadType", "type" })
    public abstract static class TxBipVotePayloadMixIn extends TxPayloadMixIn {
        @JsonProperty("type")
        abstract BipVoteType getType();
    }
}
