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
package global.goldenera.node.explorer.api.v1.tx.mappers;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

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
import global.goldenera.node.explorer.api.v1.tx.dtos.TxDtoV1;
import global.goldenera.node.explorer.api.v1.tx.dtos.TxDtoV1_Page;
import global.goldenera.node.explorer.api.v1.tx.dtos.TxPayloadDtoV1;
import global.goldenera.node.explorer.entities.ExTx;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TxMapper {

    public TxDtoV1 map(
            @NonNull ExTx in) {
        return new TxDtoV1(
                in.getHash(),
                in.getVersion(),
                in.getTimestamp(),
                in.getType(),
                in.getNetwork(),
                in.getNonce(),
                in.getSender(),
                in.getRecipient(),
                in.getAmount(),
                in.getFee(),
                in.getTokenAddress(),
                in.getMessage(),
                in.getReferenceHash(),
                in.getSignature(),
                in.getPayloadType(),
                mapPayload(in.getPayload()),
                in.getSize(),
                in.getBlockHash(),
                in.getBlockHeight(),
                in.getIndex());
    }

    public List<TxDtoV1> map(@NonNull List<ExTx> in) {
        return in.stream().map(this::map).toList();
    }

    public TxDtoV1_Page map(
            @NonNull Page<ExTx> in) {
        return new TxDtoV1_Page(
                map(in.toList()),
                in.getTotalPages(),
                in.getTotalElements());
    }

    public TxPayloadDtoV1 mapPayload(TxPayload payload) {
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

        return dto;
    }
}
