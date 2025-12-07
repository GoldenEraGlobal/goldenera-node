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
package global.goldenera.node.core.api.v1.blockchain.mappers;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
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
import global.goldenera.node.core.api.v1.blockchain.dtos.TxDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.TxPayloadDtoV1;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

/**
 * Maps Tx domain objects to TxDtoV1.
 */
@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TxMapper {

    public TxDtoV1 map(@NonNull Tx in) {
        return TxDtoV1.builder()
                .version(in.getVersion())
                .timestamp(in.getTimestamp())
                .type(in.getType())
                .network(in.getNetwork())
                .nonce(in.getNonce())
                .recipient(in.getRecipient())
                .tokenAddress(in.getTokenAddress())
                .amount(in.getAmount())
                .fee(in.getFee())
                .message(in.getMessage())
                .payload(mapPayload(in.getPayload()))
                .referenceHash(in.getReferenceHash())
                .signature(in.getSignature())
                .build();
    }

    public List<TxDtoV1> map(@NonNull List<Tx> in) {
        List<TxDtoV1> result = new ArrayList<>(in.size());
        for (Tx tx : in) {
            result.add(map(tx));
        }
        return result;
    }

    private TxPayloadDtoV1 mapPayload(TxPayload payload) {
        if (payload == null) {
            return null;
        }

        TxPayloadDtoV1 dto = switch (payload) {
            case TxBipAddressAliasAddPayload p -> {
                var d = new TxPayloadDtoV1.AddressAliasAdd();
                d.setAddress(p.getAddress());
                d.setAlias(p.getAlias());
                yield d;
            }
            case TxBipAddressAliasRemovePayload p -> {
                var d = new TxPayloadDtoV1.AddressAliasRemove();
                d.setAlias(p.getAlias());
                yield d;
            }
            case TxBipAuthorityAddPayload p -> {
                var d = new TxPayloadDtoV1.AuthorityAdd();
                d.setAddress(p.getAddress());
                yield d;
            }
            case TxBipAuthorityRemovePayload p -> {
                var d = new TxPayloadDtoV1.AuthorityRemove();
                d.setAddress(p.getAddress());
                yield d;
            }
            case TxBipNetworkParamsSetPayload p -> {
                var d = new TxPayloadDtoV1.NetworkParamsSet();
                d.setBlockReward(p.getBlockReward());
                d.setBlockRewardPoolAddress(p.getBlockRewardPoolAddress());
                d.setTargetMiningTimeMs(p.getTargetMiningTimeMs());
                d.setAsertHalfLifeBlocks(p.getAsertHalfLifeBlocks());
                d.setMinDifficulty(p.getMinDifficulty());
                d.setMinTxBaseFee(p.getMinTxBaseFee());
                d.setMinTxByteFee(p.getMinTxByteFee());
                yield d;
            }
            case TxBipTokenBurnPayload p -> {
                var d = new TxPayloadDtoV1.TokenBurn();
                d.setTokenAddress(p.getTokenAddress());
                d.setSender(p.getSender());
                d.setAmount(p.getAmount());
                yield d;
            }
            case TxBipTokenCreatePayload p -> {
                var d = new TxPayloadDtoV1.TokenCreate();
                d.setName(p.getName());
                d.setSmallestUnitName(p.getSmallestUnitName());
                d.setNumberOfDecimals(p.getNumberOfDecimals());
                d.setWebsiteUrl(p.getWebsiteUrl());
                d.setLogoUrl(p.getLogoUrl());
                d.setMaxSupply(p.getMaxSupply());
                d.setUserBurnable(p.isUserBurnable());
                yield d;
            }
            case TxBipTokenMintPayload p -> {
                var d = new TxPayloadDtoV1.TokenMint();
                d.setTokenAddress(p.getTokenAddress());
                d.setRecipient(p.getRecipient());
                d.setAmount(p.getAmount());
                yield d;
            }
            case TxBipTokenUpdatePayload p -> {
                var d = new TxPayloadDtoV1.TokenUpdate();
                d.setTokenAddress(p.getTokenAddress());
                d.setName(p.getName());
                d.setSmallestUnitName(p.getSmallestUnitName());
                d.setWebsiteUrl(p.getWebsiteUrl());
                d.setLogoUrl(p.getLogoUrl());
                yield d;
            }
            case TxBipVotePayload p -> {
                var d = new TxPayloadDtoV1.Vote();
                d.setType(p.getType());
                yield d;
            }
            default -> throw new IllegalArgumentException("Unknown payload type: " + payload.getClass());
        };

        dto.setPayloadType(payload.getPayloadType());
        return dto;
    }
}
