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
import global.goldenera.cryptoj.enums.BipVoteType;
import global.goldenera.cryptoj.enums.TxPayloadType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Base Transaction Payload DTO for API v1.
 * Uses polymorphic deserialization based on payloadType.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "payloadType", visible = true)
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
public abstract class TxPayloadDtoV1 {

    TxPayloadType payloadType;

    // --- Concrete Payload Types ---

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class AddressAliasAdd extends TxPayloadDtoV1 {
        Address address;
        String alias;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class AddressAliasRemove extends TxPayloadDtoV1 {
        String alias;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class AuthorityAdd extends TxPayloadDtoV1 {
        Address address;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class AuthorityRemove extends TxPayloadDtoV1 {
        Address address;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class NetworkParamsSet extends TxPayloadDtoV1 {
        Wei blockReward;
        Address blockRewardPoolAddress;
        Long targetMiningTimeMs;
        Long asertHalfLifeBlocks;
        BigInteger minDifficulty;
        Wei minTxBaseFee;
        Wei minTxByteFee;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class TokenBurn extends TxPayloadDtoV1 {
        Address tokenAddress;
        Address sender;
        Wei amount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class TokenCreate extends TxPayloadDtoV1 {
        String name;
        String smallestUnitName;
        int numberOfDecimals;
        String websiteUrl;
        String logoUrl;
        BigInteger maxSupply;
        boolean userBurnable;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class TokenMint extends TxPayloadDtoV1 {
        Address tokenAddress;
        Address recipient;
        Wei amount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class TokenUpdate extends TxPayloadDtoV1 {
        Address tokenAddress;
        String name;
        String smallestUnitName;
        String websiteUrl;
        String logoUrl;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class Vote extends TxPayloadDtoV1 {
        BipVoteType type;
    }
}
