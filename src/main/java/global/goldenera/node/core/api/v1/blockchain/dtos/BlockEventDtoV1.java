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
import global.goldenera.node.core.enums.BlockEventType;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

/**
 * Base DTO for block events exposed through the API.
 * Uses Jackson polymorphic serialization with discriminator field "type".
 */
@Data
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
        @JsonSubTypes.Type(value = BlockEventDtoV1.ValidatorAddedDto.class, name = "VALIDATOR_ADDED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.ValidatorRemovedDto.class, name = "VALIDATOR_REMOVED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.NetworkParamsChangedDto.class, name = "NETWORK_PARAMS_CHANGED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.NetworkParamsUpdatedDto.class, name = "NETWORK_PARAMS_UPDATED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.AddressAliasAddedDto.class, name = "ADDRESS_ALIAS_ADDED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.AddressAliasRemovedDto.class, name = "ADDRESS_ALIAS_REMOVED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.BipStateCreatedDto.class, name = "BIP_STATE_CREATED"),
        @JsonSubTypes.Type(value = BlockEventDtoV1.BipStateUpdatedDto.class, name = "BIP_STATE_UPDATED")
})
@Schema(description = "Block event", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = "BLOCK_REWARD", schema = BlockEventDtoV1.BlockRewardDto.class),
        @DiscriminatorMapping(value = "FEES_COLLECTED", schema = BlockEventDtoV1.FeesCollectedDto.class),
        @DiscriminatorMapping(value = "TOKEN_CREATED", schema = BlockEventDtoV1.TokenCreatedDto.class),
        @DiscriminatorMapping(value = "TOKEN_UPDATED", schema = BlockEventDtoV1.TokenUpdatedDto.class),
        @DiscriminatorMapping(value = "TOKEN_MINTED", schema = BlockEventDtoV1.TokenMintedDto.class),
        @DiscriminatorMapping(value = "TOKEN_BURNED", schema = BlockEventDtoV1.TokenBurnedDto.class),
        @DiscriminatorMapping(value = "TOKEN_SUPPLY_UPDATED", schema = BlockEventDtoV1.TokenSupplyUpdatedDto.class),
        @DiscriminatorMapping(value = "AUTHORITY_ADDED", schema = BlockEventDtoV1.AuthorityAddedDto.class),
        @DiscriminatorMapping(value = "AUTHORITY_REMOVED", schema = BlockEventDtoV1.AuthorityRemovedDto.class),
        @DiscriminatorMapping(value = "VALIDATOR_ADDED", schema = BlockEventDtoV1.ValidatorAddedDto.class),
        @DiscriminatorMapping(value = "VALIDATOR_REMOVED", schema = BlockEventDtoV1.ValidatorRemovedDto.class),
        @DiscriminatorMapping(value = "NETWORK_PARAMS_CHANGED", schema = BlockEventDtoV1.NetworkParamsChangedDto.class),
        @DiscriminatorMapping(value = "NETWORK_PARAMS_UPDATED", schema = BlockEventDtoV1.NetworkParamsUpdatedDto.class),
        @DiscriminatorMapping(value = "ADDRESS_ALIAS_ADDED", schema = BlockEventDtoV1.AddressAliasAddedDto.class),
        @DiscriminatorMapping(value = "ADDRESS_ALIAS_REMOVED", schema = BlockEventDtoV1.AddressAliasRemovedDto.class),
        @DiscriminatorMapping(value = "BIP_STATE_CREATED", schema = BlockEventDtoV1.BipStateCreatedDto.class),
        @DiscriminatorMapping(value = "BIP_STATE_UPDATED", schema = BlockEventDtoV1.BipStateUpdatedDto.class)
})
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
        BlockEventDtoV1.ValidatorAddedDto,
        BlockEventDtoV1.ValidatorRemovedDto,
        BlockEventDtoV1.NetworkParamsChangedDto,
        BlockEventDtoV1.NetworkParamsUpdatedDto,
        BlockEventDtoV1.AddressAliasAddedDto,
        BlockEventDtoV1.AddressAliasRemovedDto,
        BlockEventDtoV1.BipStateCreatedDto,
        BlockEventDtoV1.BipStateUpdatedDto {

    @Schema(description = "Type discriminator", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "BLOCK_REWARD")
    public abstract BlockEventType getType();

    // ============================
    // BLOCK LEVEL EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class BlockRewardDto extends BlockEventDtoV1 {
        @Schema(description = "Miner address who received the reward")
        Address minerAddress;
        @Schema(description = "Reward pool address")
        Address rewardPoolAddress;
        @Schema(description = "Reward amount")
        Wei amount;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.BLOCK_REWARD;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class FeesCollectedDto extends BlockEventDtoV1 {
        @Schema(description = "Miner address who collected the fees")
        Address minerAddress;
        @Schema(description = "Total fees collected")
        Wei amount;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.FEES_COLLECTED;
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
        public BlockEventType getType() {
            return BlockEventType.TOKEN_CREATED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class TokenUpdatedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that updated this token")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.TokenUpdate payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.TOKEN_UPDATED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class TokenMintedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that minted tokens")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.TokenMint payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.TOKEN_MINTED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
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
        public BlockEventType getType() {
            return BlockEventType.TOKEN_BURNED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class TokenSupplyUpdatedDto extends BlockEventDtoV1 {
        @Schema(description = "Token address")
        Address tokenAddress;
        @Schema(description = "New total supply after this block")
        Wei newTotalSupply;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.TOKEN_SUPPLY_UPDATED;
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
    public static final class AuthorityAddedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that added the authority")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AuthorityAdd payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.AUTHORITY_ADDED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class AuthorityRemovedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that removed the authority")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AuthorityRemove payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.AUTHORITY_REMOVED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class ValidatorAddedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that added the validator")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.ValidatorAdd payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.VALIDATOR_ADDED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class ValidatorRemovedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that removed the validator")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.ValidatorRemove payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.VALIDATOR_REMOVED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class NetworkParamsChangedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that changed params")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.NetworkParamsSet payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.NETWORK_PARAMS_CHANGED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class NetworkParamsUpdatedDto extends BlockEventDtoV1 {
        @Schema(description = "Previous network params state")
        NetworkParamsStateDtoV1 oldState;
        @Schema(description = "New network params state")
        NetworkParamsStateDtoV1 newState;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.NETWORK_PARAMS_UPDATED;
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
    public static final class AddressAliasAddedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that added the alias")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AddressAliasAdd payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.ADDRESS_ALIAS_ADDED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class AddressAliasRemovedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that removed the alias")
        Hash bipHash;
        @Schema(description = "Transaction version")
        TxVersion txVersion;
        @Schema(description = "Payload details")
        TxPayloadDtoV1.AddressAliasRemove payload;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.ADDRESS_ALIAS_REMOVED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class BipStateCreatedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash")
        Hash bipHash;
        @Schema(description = "Initial status")
        BipStatus status;
        @Schema(description = "Is action executed")
        boolean actionExecuted;
        @Schema(description = "Approvers")
        LinkedHashSet<Address> approvers;
        @Schema(description = "Disapprovers")
        LinkedHashSet<Address> disapprovers;
        @Schema(description = "Hash of the transaction that created the BIP")
        Hash updatedByTxHash;
        @Schema(description = "Block height of the creation")
        long updatedAtBlockHeight;
        @Schema(description = "Timestamp of the creation")
        Instant updatedAtTimestamp;

        @Override
        @JsonProperty("type")
        public BlockEventType getType() {
            return BlockEventType.BIP_STATE_CREATED;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static final class BipStateUpdatedDto extends BlockEventDtoV1 {
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
        public BlockEventType getType() {
            return BlockEventType.BIP_STATE_UPDATED;
        }
    }
}