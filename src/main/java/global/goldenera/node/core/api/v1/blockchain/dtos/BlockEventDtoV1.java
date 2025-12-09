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

import java.time.Instant;
import java.util.LinkedHashSet;

import org.apache.tuweni.units.ethereum.Wei;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.cryptoj.enums.state.BipStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

/**
 * Base DTO for block events exposed through the API.
 * Uses Jackson polymorphic serialization with discriminator field "type".
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = BlockEventDtoV1.BlockRewardDto.class, name = "BLOCK_REWARD"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.FeesCollectedDto.class, name = "FEES_COLLECTED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.TokenCreatedDto.class, name = "TOKEN_CREATED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.TokenUpdatedDto.class, name = "TOKEN_UPDATED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.TokenMintedDto.class, name = "TOKEN_MINTED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.TokenBurnedDto.class, name = "TOKEN_BURNED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.TokenSupplyUpdatedDto.class, name = "TOKEN_SUPPLY_UPDATED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.AuthorityAddedDto.class, name = "AUTHORITY_ADDED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.AuthorityRemovedDto.class, name = "AUTHORITY_REMOVED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.NetworkParamsChangedDto.class, name = "NETWORK_PARAMS_CHANGED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.AddressAliasAddedDto.class, name = "ADDRESS_ALIAS_ADDED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.AddressAliasRemovedDto.class, name = "ADDRESS_ALIAS_REMOVED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.BipStateChangeDto.class, name = "BIP_STATE_CHANGE")
})
@Schema(description = "Block event", discriminatorProperty = "type")
public abstract sealed class BlockEventDtoV1 permits
        BlockEventDtoV1.BlockRewardDto,
        BlockEventDtoV1.FeesCollectedDto,
        BlockEventDtoV1.TokenCreatedDto,
        BlockEventDtoV1.TokenUpdatedDto,
        BlockEventDtoV1.TokenMintedDto,
        BlockEventDtoV1.TokenBurnedDto,
        BlockEventDtoV1.TokenSupplyUpdatedDto,
        BlockEventDtoV1.AuthorityAddedDto,
        BlockEventDtoV1.AuthorityRemovedDto,
        BlockEventDtoV1.NetworkParamsChangedDto,
        BlockEventDtoV1.AddressAliasAddedDto,
        BlockEventDtoV1.AddressAliasRemovedDto,
        BlockEventDtoV1.BipStateChangeDto {

    @Schema(description = "Event type", requiredMode = Schema.RequiredMode.REQUIRED)
    public abstract String getType();

    // ============================
    // BLOCK LEVEL EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "BlockRewardDto", description = "Miner received block reward from reward pool")
    public static final class BlockRewardDto extends BlockEventDtoV1 {
        @Schema(description = "Miner address who received the reward")
        Address minerAddress;
        @Schema(description = "Reward pool address")
        Address rewardPoolAddress;
        @Schema(description = "Reward amount")
        Wei amount;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "BLOCK_REWARD" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "BLOCK_REWARD";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "FeesCollectedDto", description = "Miner collected transaction fees")
    public static final class FeesCollectedDto extends BlockEventDtoV1 {
        @Schema(description = "Miner address who collected the fees")
        Address minerAddress;
        @Schema(description = "Total fees collected")
        Wei amount;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "FEES_COLLECTED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "FEES_COLLECTED";
        }
    }

    // ============================
    // TOKEN EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "TokenCreatedDto", description = "New token was created via approved BIP")
    public static final class TokenCreatedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that created this token")
        Hash bipHash;
        @Schema(description = "Derived token address")
        Address derivedTokenAddress;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.TokenCreate payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "TOKEN_CREATED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "TOKEN_CREATED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "TokenUpdatedDto", description = "Token metadata was updated via approved BIP")
    public static final class TokenUpdatedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that updated this token")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.TokenUpdate payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "TOKEN_UPDATED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "TOKEN_UPDATED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "TokenMintedDto", description = "Tokens were minted to an address via approved BIP")
    public static final class TokenMintedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that minted tokens")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.TokenMint payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "TOKEN_MINTED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "TOKEN_MINTED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "TokenBurnedDto", description = "Tokens were burned via approved BIP")
    public static final class TokenBurnedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that burned tokens")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.TokenBurn payload;
        @Schema(description = "Actual amount burned")
        Wei actualBurnedAmount;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "TOKEN_BURNED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "TOKEN_BURNED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "TokenSupplyUpdatedDto", description = "Token total supply was updated")
    public static final class TokenSupplyUpdatedDto extends BlockEventDtoV1 {
        @Schema(description = "Token address")
        Address tokenAddress;
        @Schema(description = "New total supply after this block")
        Wei newTotalSupply;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = {
                "TOKEN_SUPPLY_UPDATED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "TOKEN_SUPPLY_UPDATED";
        }
    }

    // ============================
    // GOVERNANCE EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "AuthorityAddedDto", description = "Authority was added to governance via approved BIP")
    public static final class AuthorityAddedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that added the authority")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AuthorityAdd payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "AUTHORITY_ADDED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "AUTHORITY_ADDED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "AuthorityRemovedDto", description = "Authority was removed from governance via approved BIP")
    public static final class AuthorityRemovedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that removed the authority")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AuthorityRemove payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "AUTHORITY_REMOVED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "AUTHORITY_REMOVED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "NetworkParamsChangedDto", description = "Network parameters were changed via approved BIP")
    public static final class NetworkParamsChangedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that changed params")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.NetworkParamsSet payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = {
                "NETWORK_PARAMS_CHANGED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "NETWORK_PARAMS_CHANGED";
        }
    }

    // ============================
    // ADDRESS ALIAS EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "AddressAliasAddedDto", description = "Address alias was added via approved BIP")
    public static final class AddressAliasAddedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that added the alias")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AddressAliasAdd payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = {
                "ADDRESS_ALIAS_ADDED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "ADDRESS_ALIAS_ADDED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "AddressAliasRemovedDto", description = "Address alias was removed via approved BIP")
    public static final class AddressAliasRemovedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that removed the alias")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AddressAliasRemove payload;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = {
                "ADDRESS_ALIAS_REMOVED" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "ADDRESS_ALIAS_REMOVED";
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(name = "BipStateChangeDto", description = "BIP state/status changed")
    public static final class BipStateChangeDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash")
        Hash bipHash;
        @Schema(description = "New status")
        BipStatus status;
        @Schema(description = "Is action executed")
        boolean actionExecuted;
        @Schema(description = "Approvers")
        LinkedHashSet<Address> approvers;
        @Schema(description = "Disapprovers")
        LinkedHashSet<Address> disapprovers;
        @Schema(description = "Hash of the transaction that triggered the update")
        Hash updatedByTxHash;
        @Schema(description = "Block height of the update")
        long updatedAtBlockHeight;
        @Schema(description = "Timestamp of the update")
        Instant updatedAtTimestamp;

        @Override
        @JsonProperty("type")
        @Schema(type = "string", allowableValues = { "BIP_STATE_CHANGE" }, requiredMode = Schema.RequiredMode.REQUIRED)
        public String getType() {
            return "BIP_STATE_CHANGE";
        }
    }
}