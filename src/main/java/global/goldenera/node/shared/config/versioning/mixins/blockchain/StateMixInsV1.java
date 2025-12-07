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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

public class StateMixInsV1 {

    // --- Account Balance ---
    @JsonPropertyOrder({ "version", "balance", "updatedAtBlockHeight", "updatedAtTimestamp" })
    public abstract static class AccountBalanceStateMixIn {
        @JsonProperty("version")
        abstract Object getVersion();

        @JsonProperty("balance")
        abstract Object getBalance();

        @JsonProperty("updatedAtBlockHeight")
        abstract long getUpdatedAtBlockHeight();

        @JsonProperty("updatedAtTimestamp")
        abstract Object getUpdatedAtTimestamp();

        @JsonIgnore
        abstract boolean exists();
    }

    // --- Account Nonce ---
    @JsonPropertyOrder({ "version", "nonce", "updatedAtBlockHeight", "updatedAtTimestamp" })
    public abstract static class AccountNonceStateMixIn {
        @JsonProperty("version")
        abstract Object getVersion();

        @JsonProperty("nonce")
        abstract long getNonce();

        @JsonProperty("updatedAtBlockHeight")
        abstract long getUpdatedAtBlockHeight();

        @JsonProperty("updatedAtTimestamp")
        abstract Object getUpdatedAtTimestamp();

        @JsonIgnore
        abstract boolean exists();
    }

    // --- Address Alias ---
    @JsonPropertyOrder({ "version", "address", "originTxHash", "createdAtBlockHeight", "createdAtTimestamp" })
    public abstract static class AddressAliasStateMixIn {
        @JsonProperty("version")
        abstract Object getVersion();

        @JsonProperty("address")
        abstract Object getAddress();

        @JsonProperty("originTxHash")
        abstract Object getOriginTxHash();

        @JsonProperty("createdAtBlockHeight")
        abstract long getCreatedAtBlockHeight();

        @JsonProperty("createdAtTimestamp")
        abstract Object getCreatedAtTimestamp();

        @JsonIgnore
        abstract boolean exists();
    }

    // --- Authority ---
    @JsonPropertyOrder({ "version", "originTxHash", "createdAtBlockHeight", "createdAtTimestamp" })
    public abstract static class AuthorityStateMixIn {
        @JsonProperty("version")
        abstract Object getVersion();

        @JsonProperty("originTxHash")
        abstract Object getOriginTxHash();

        @JsonProperty("createdAtBlockHeight")
        abstract long getCreatedAtBlockHeight();

        @JsonProperty("createdAtTimestamp")
        abstract Object getCreatedAtTimestamp();

        @JsonIgnore
        abstract boolean exists();
    }

    // --- BIP (Proposal) ---
    public abstract static class BipStateMixIn {
        @JsonProperty("version")
        abstract Object getVersion();

        @JsonProperty("status")
        abstract Object getStatus();

        @JsonProperty("type")
        abstract Object getType();

        @JsonProperty("actionExecuted")
        abstract boolean isActionExecuted();

        @JsonProperty("numberOfRequiredVotes")
        abstract long getNumberOfRequiredVotes();

        @JsonProperty("approvers")
        abstract Object getApprovers();

        @JsonProperty("disapprovers")
        abstract Object getDisapprovers();

        @JsonProperty("metadata")
        abstract Object getMetadata();

        @JsonProperty("expirationTimestamp")
        abstract Object getExpirationTimestamp();

        @JsonProperty("executedAtTimestamp")
        abstract Object getExecutedAtTimestamp();

        @JsonProperty("originTxHash")
        abstract Object getOriginTxHash();

        @JsonProperty("updatedByTxHash")
        abstract Object getUpdatedByTxHash();

        @JsonProperty("updatedAtBlockHeight")
        abstract long getUpdatedAtBlockHeight();

        @JsonProperty("updatedAtTimestamp")
        abstract Object getUpdatedAtTimestamp();

        @JsonProperty("approvalCount")
        abstract long getApprovalCount();

        @JsonProperty("disapprovalCount")
        abstract long getDisapprovalCount();

        @JsonProperty("allVoters")
        abstract Object getAllVoters();

        @JsonIgnore
        abstract boolean exists();
    }

    // --- BIP Metadata ---
    public abstract static class BipStateMetadataMixIn {
        @JsonProperty("version")
        abstract Object getVersion();

        @JsonProperty("txVersion")
        abstract Object getTxVersion();

        @JsonProperty("txPayload")
        abstract Object getTxPayload();

        @JsonProperty("derivedTokenAddress")
        abstract Object getDerivedTokenAddress();
    }

    // --- Network Params ---
    public abstract static class NetworkParamsStateMixIn {
        @JsonIgnore
        abstract Object KEY();

        @JsonProperty("version")
        abstract Object getVersion();

        @JsonProperty("blockReward")
        abstract Object getBlockReward();

        @JsonProperty("blockRewardPoolAddress")
        abstract Object getBlockRewardPoolAddress();

        @JsonProperty("targetMiningTimeMs")
        abstract long getTargetMiningTimeMs();

        @JsonProperty("asertHalfLifeBlocks")
        abstract long getAsertHalfLifeBlocks();

        @JsonProperty("asertAnchorHeight")
        abstract long getAsertAnchorHeight();

        @JsonProperty("minDifficulty")
        abstract Object getMinDifficulty();

        @JsonProperty("minTxBaseFee")
        abstract Object getMinTxBaseFee();

        @JsonProperty("minTxByteFee")
        abstract Object getMinTxByteFee();

        @JsonProperty("currentAuthorityCount")
        abstract long getCurrentAuthorityCount();

        @JsonProperty("updatedByTxHash")
        abstract Object getUpdatedByTxHash();

        @JsonProperty("updatedAtBlockHeight")
        abstract long getUpdatedAtBlockHeight();

        @JsonProperty("updatedAtTimestamp")
        abstract Object getUpdatedAtTimestamp();
    }

    // --- Token State ---
    public abstract static class TokenStateMixIn {
        @JsonProperty("version")
        abstract Object getVersion();

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
        abstract Object getMaxSupply();

        @JsonProperty("totalSupply")
        abstract Object getTotalSupply();

        @JsonProperty("userBurnable")
        abstract boolean isUserBurnable();

        @JsonProperty("originTxHash")
        abstract Object getOriginTxHash();

        @JsonProperty("updatedByTxHash")
        abstract Object getUpdatedByTxHash();

        @JsonProperty("updatedAtBlockHeight")
        abstract long getUpdatedAtBlockHeight();

        @JsonProperty("updatedAtTimestamp")
        abstract Object getUpdatedAtTimestamp();

        @JsonIgnore
        abstract boolean exists();
    }
}