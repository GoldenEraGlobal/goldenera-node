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
package global.goldenera.node.explorer.api.v1.tx.dtos;

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.BipVoteType;
import global.goldenera.cryptoj.enums.TxPayloadType;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
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
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AddressAliasAdd.class, name = "BIP_ADDRESS_ALIAS_ADD"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AddressAliasRemove.class, name = "BIP_ADDRESS_ALIAS_REMOVE"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AuthorityAdd.class, name = "BIP_AUTHORITY_ADD"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.AuthorityRemove.class, name = "BIP_AUTHORITY_REMOVE"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.NetworkParamsSet.class, name = "BIP_NETWORK_PARAMS_SET"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenBurn.class, name = "BIP_TOKEN_BURN"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenCreate.class, name = "BIP_TOKEN_CREATE"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenMint.class, name = "BIP_TOKEN_MINT"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.TokenUpdate.class, name = "BIP_TOKEN_UPDATE"),
        @JsonSubTypes.Type(value = TxPayloadDtoV1.Vote.class, name = "BIP_VOTE")
})
@Schema(description = "Transaction payload", discriminatorProperty = "payloadType", discriminatorMapping = {
        @DiscriminatorMapping(value = "BIP_ADDRESS_ALIAS_ADD", schema = TxPayloadDtoV1.AddressAliasAdd.class),
        @DiscriminatorMapping(value = "BIP_ADDRESS_ALIAS_REMOVE", schema = TxPayloadDtoV1.AddressAliasRemove.class),
        @DiscriminatorMapping(value = "BIP_AUTHORITY_ADD", schema = TxPayloadDtoV1.AuthorityAdd.class),
        @DiscriminatorMapping(value = "BIP_AUTHORITY_REMOVE", schema = TxPayloadDtoV1.AuthorityRemove.class),
        @DiscriminatorMapping(value = "BIP_NETWORK_PARAMS_SET", schema = TxPayloadDtoV1.NetworkParamsSet.class),
        @DiscriminatorMapping(value = "BIP_TOKEN_BURN", schema = TxPayloadDtoV1.TokenBurn.class),
        @DiscriminatorMapping(value = "BIP_TOKEN_CREATE", schema = TxPayloadDtoV1.TokenCreate.class),
        @DiscriminatorMapping(value = "BIP_TOKEN_MINT", schema = TxPayloadDtoV1.TokenMint.class),
        @DiscriminatorMapping(value = "BIP_TOKEN_UPDATE", schema = TxPayloadDtoV1.TokenUpdate.class),
        @DiscriminatorMapping(value = "BIP_VOTE", schema = TxPayloadDtoV1.Vote.class)
})
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

    @Schema(description = "Payload type discriminator", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "BIP_ADDRESS_ALIAS_ADD")
    public abstract TxPayloadType getPayloadType();

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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_ADDRESS_ALIAS_ADD;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_ADDRESS_ALIAS_REMOVE;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_AUTHORITY_ADD;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_AUTHORITY_REMOVE;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_NETWORK_PARAMS_SET;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_TOKEN_BURN;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_TOKEN_CREATE;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_TOKEN_MINT;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_TOKEN_UPDATE;
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
        public TxPayloadType getPayloadType() {
            return TxPayloadType.BIP_VOTE;
        }
    }
}