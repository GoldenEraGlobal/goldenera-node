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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
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
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
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
        @JsonSubTypes.Type(value = BlockEventDtoV1.AddressAliasRemovedDto.class, name = "ADDRESS_ALIAS_REMOVED")
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
        @DiscriminatorMapping(value = "NETWORK_PARAMS_CHANGED", schema = BlockEventDtoV1.NetworkParamsChangedDto.class),
        @DiscriminatorMapping(value = "ADDRESS_ALIAS_ADDED", schema = BlockEventDtoV1.AddressAliasAddedDto.class),
        @DiscriminatorMapping(value = "ADDRESS_ALIAS_REMOVED", schema = BlockEventDtoV1.AddressAliasRemovedDto.class)
}, oneOf = {
        BlockEventDtoV1.BlockRewardDto.class,
        BlockEventDtoV1.FeesCollectedDto.class,
        BlockEventDtoV1.TokenCreatedDto.class,
        BlockEventDtoV1.TokenUpdatedDto.class,
        BlockEventDtoV1.TokenMintedDto.class,
        BlockEventDtoV1.TokenBurnedDto.class,
        BlockEventDtoV1.TokenSupplyUpdatedDto.class,
        BlockEventDtoV1.AuthorityAddedDto.class,
        BlockEventDtoV1.AuthorityRemovedDto.class,
        BlockEventDtoV1.NetworkParamsChangedDto.class,
        BlockEventDtoV1.AddressAliasAddedDto.class,
        BlockEventDtoV1.AddressAliasRemovedDto.class
})
public abstract sealed class BlockEventDtoV1 {

    // ============================
    // BLOCK LEVEL EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Miner received block reward from reward pool")
    public static final class BlockRewardDto extends BlockEventDtoV1 {
        @Schema(description = "Miner address who received the reward")
        Address minerAddress;
        @Schema(description = "Reward pool address")
        Address rewardPoolAddress;
        @Schema(description = "Reward amount")
        Wei amount;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Miner collected transaction fees")
    public static final class FeesCollectedDto extends BlockEventDtoV1 {
        @Schema(description = "Miner address who collected the fees")
        Address minerAddress;
        @Schema(description = "Total fees collected")
        Wei amount;
    }

    // ============================
    // TOKEN EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "New token was created via approved BIP")
    public static final class TokenCreatedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that created this token")
        Hash bipHash;
        @Schema(description = "Token address")
        Address tokenAddress;
        @Schema(description = "Token name")
        String name;
        @Schema(description = "Smallest unit name")
        String smallestUnitName;
        @Schema(description = "Number of decimals")
        int decimals;
        @Schema(description = "Website URL")
        String websiteUrl;
        @Schema(description = "Logo URL")
        String logoUrl;
        @Schema(description = "Maximum supply")
        BigInteger maxSupply;
        @Schema(description = "Whether users can burn tokens")
        boolean userBurnable;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Token metadata was updated via approved BIP")
    public static final class TokenUpdatedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that updated this token")
        Hash bipHash;
        @Schema(description = "Token address")
        Address tokenAddress;
        @Schema(description = "Updated name")
        String name;
        @Schema(description = "Updated smallest unit name")
        String smallestUnitName;
        @Schema(description = "Updated website URL")
        String websiteUrl;
        @Schema(description = "Updated logo URL")
        String logoUrl;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Tokens were minted to an address via approved BIP")
    public static final class TokenMintedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that minted tokens")
        Hash bipHash;
        @Schema(description = "Token address")
        Address tokenAddress;
        @Schema(description = "Recipient address")
        Address recipient;
        @Schema(description = "Amount minted")
        Wei amount;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Tokens were burned via approved BIP")
    public static final class TokenBurnedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that burned tokens")
        Hash bipHash;
        @Schema(description = "Token address")
        Address tokenAddress;
        @Schema(description = "Owner address whose tokens were burned")
        Address owner;
        @Schema(description = "Requested burn amount")
        Wei requestedAmount;
        @Schema(description = "Actual amount burned")
        Wei actualBurnedAmount;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Token total supply was updated")
    public static final class TokenSupplyUpdatedDto extends BlockEventDtoV1 {
        @Schema(description = "Token address")
        Address tokenAddress;
        @Schema(description = "New total supply after this block")
        Wei newTotalSupply;
    }

    // ============================
    // GOVERNANCE EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Authority was added to governance via approved BIP")
    public static final class AuthorityAddedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that added the authority")
        Hash bipHash;
        @Schema(description = "Authority address")
        Address authorityAddress;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Authority was removed from governance via approved BIP")
    public static final class AuthorityRemovedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that removed the authority")
        Hash bipHash;
        @Schema(description = "Authority address")
        Address authorityAddress;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Network parameters were changed via approved BIP")
    public static final class NetworkParamsChangedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that changed params")
        Hash bipHash;
        @Schema(description = "New block reward (null if unchanged)")
        Wei newBlockReward;
        @Schema(description = "New reward pool address (null if unchanged)")
        Address newBlockRewardPoolAddress;
        @Schema(description = "New target mining time in ms (null if unchanged)")
        Long newTargetMiningTimeMs;
        @Schema(description = "New ASERT half-life blocks (null if unchanged)")
        Long newAsertHalfLifeBlocks;
        @Schema(description = "New minimum difficulty (null if unchanged)")
        BigInteger newMinDifficulty;
        @Schema(description = "New minimum tx base fee (null if unchanged)")
        Wei newMinTxBaseFee;
        @Schema(description = "New minimum tx byte fee (null if unchanged)")
        Wei newMinTxByteFee;
    }

    // ============================
    // ADDRESS ALIAS EVENTS
    // ============================

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Address alias was added via approved BIP")
    public static final class AddressAliasAddedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that added the alias")
        Hash bipHash;
        @Schema(description = "Address for the alias")
        Address address;
        @Schema(description = "Alias string")
        String alias;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Schema(description = "Address alias was removed via approved BIP")
    public static final class AddressAliasRemovedDto extends BlockEventDtoV1 {
        @Schema(description = "BIP hash that removed the alias")
        Hash bipHash;
        @Schema(description = "Alias string that was removed")
        String alias;
    }
}
