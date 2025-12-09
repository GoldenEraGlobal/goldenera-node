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
package global.goldenera.node.core.api.v1.blockchain.dtos;

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.BipVoteType;
import global.goldenera.cryptoj.enums.TxPayloadType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Base Transaction Payload DTO for API v1.
 * Uses polymorphic deserialization based on payloadType.
 */
@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "payloadType", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AddressAliasAdd.class, name = "0"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AddressAliasRemove.class, name = "1"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AuthorityAdd.class, name = "2"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AuthorityRemove.class, name = "3"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.NetworkParamsSet.class, name = "4"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenBurn.class, name = "5"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenCreate.class, name = "6"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenMint.class, name = "7"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenUpdate.class, name = "8"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.Vote.class, name = "9")
})
@Schema(description = "Transaction payload", discriminatorProperty = "payloadType")
public abstract sealed class TxPayloadDtoV1 permits
        TxPayloadDtoV1.AddressAliasAdd,
        TxPayloadDtoV1.AddressAliasRemove,
        TxPayloadDtoV1.AuthorityAdd,
        TxPayloadDtoV1.AuthorityRemove,
        TxPayloadDtoV1.NetworkParamsSet,
        TxPayloadDtoV1.TokenBurn,
        TxPayloadDtoV1.TokenCreate,
        TxPayloadDtoV1.TokenMint,
        TxPayloadDtoV1.TokenUpdate,
        TxPayloadDtoV1.Vote {

    @Schema(description = "Payload type discriminator", requiredMode = Schema.RequiredMode.REQUIRED)
    public abstract int getPayloadType();

    // --- Concrete Payload Types ---

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "AddressAliasAdd", description = "Add address alias payload")
    public static final class AddressAliasAdd extends TxPayloadDtoV1 {
        Address address;
        String alias;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "0" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_ADDRESS_ALIAS_ADD.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "AddressAliasRemove", description = "Remove address alias payload")
    public static final class AddressAliasRemove extends TxPayloadDtoV1 {
        String alias;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "1" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_ADDRESS_ALIAS_REMOVE.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "AuthorityAdd", description = "Add authority payload")
    public static final class AuthorityAdd extends TxPayloadDtoV1 {
        Address address;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "2" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_AUTHORITY_ADD.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "AuthorityRemove", description = "Remove authority payload")
    public static final class AuthorityRemove extends TxPayloadDtoV1 {
        Address address;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "3" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_AUTHORITY_REMOVE.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "NetworkParamsSet", description = "Set network parameters payload")
    public static final class NetworkParamsSet extends TxPayloadDtoV1 {
        Wei blockReward;
        Address blockRewardPoolAddress;
        Long targetMiningTimeMs;
        Long asertHalfLifeBlocks;
        BigInteger minDifficulty;
        Wei minTxBaseFee;
        Wei minTxByteFee;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "4" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_NETWORK_PARAMS_SET.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "TokenBurn", description = "Burn tokens payload")
    public static final class TokenBurn extends TxPayloadDtoV1 {
        Address tokenAddress;
        Address sender;
        Wei amount;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "5" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_TOKEN_BURN.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "TokenCreate", description = "Create token payload")
    public static final class TokenCreate extends TxPayloadDtoV1 {
        String name;
        String smallestUnitName;
        int numberOfDecimals;
        String websiteUrl;
        String logoUrl;
        BigInteger maxSupply;
        boolean userBurnable;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "6" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_TOKEN_CREATE.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "TokenMint", description = "Mint tokens payload")
    public static final class TokenMint extends TxPayloadDtoV1 {
        Address tokenAddress;
        Address recipient;
        Wei amount;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "7" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_TOKEN_MINT.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "TokenUpdate", description = "Update token payload")
    public static final class TokenUpdate extends TxPayloadDtoV1 {
        Address tokenAddress;
        String name;
        String smallestUnitName;
        String websiteUrl;
        String logoUrl;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "8" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_TOKEN_UPDATE.getCode();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    @Schema(name = "Vote", description = "BIP vote payload")
    public static final class Vote extends TxPayloadDtoV1 {
        BipVoteType type;

        @Override
        @JsonProperty("payloadType")
        @Schema(type = "integer", allowableValues = { "9" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public int getPayloadType() {
            return TxPayloadType.BIP_VOTE.getCode();
        }
    }
}