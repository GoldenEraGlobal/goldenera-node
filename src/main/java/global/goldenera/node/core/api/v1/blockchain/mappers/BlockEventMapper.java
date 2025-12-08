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

import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.AddressAliasAddedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.AddressAliasRemovedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.AuthorityAddedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.AuthorityRemovedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.BlockRewardDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.FeesCollectedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.NetworkParamsChangedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.TokenBurnedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.TokenCreatedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.TokenMintedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.TokenSupplyUpdatedDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.TokenUpdatedDto;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AddressAliasAdded;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AddressAliasRemoved;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AuthorityAdded;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.AuthorityRemoved;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BlockReward;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.FeesCollected;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.NetworkParamsChanged;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenBurned;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenCreated;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenMinted;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenSupplyUpdated;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.TokenUpdated;

/**
 * Maps BlockEvent domain objects to BlockEventDtoV1 subtypes for API responses.
 */
@Component
public class BlockEventMapper {

        public List<BlockEventDtoV1> map(List<BlockEvent> events) {
                if (events == null || events.isEmpty()) {
                        return List.of();
                }
                List<BlockEventDtoV1> result = new ArrayList<>(events.size());
                for (BlockEvent event : events) {
                        result.add(map(event));
                }
                return result;
        }

        public BlockEventDtoV1 map(BlockEvent event) {
                return switch (event) {
                        case BlockReward e -> new BlockRewardDto(
                                        e.minerAddress(),
                                        e.rewardPoolAddress(),
                                        e.amount());

                        case FeesCollected e -> new FeesCollectedDto(
                                        e.minerAddress(),
                                        e.amount());

                        case TokenCreated e -> new TokenCreatedDto(
                                        e.bipHash(),
                                        e.tokenAddress(),
                                        e.name(),
                                        e.smallestUnitName(),
                                        e.decimals(),
                                        e.websiteUrl(),
                                        e.logoUrl(),
                                        e.maxSupply(),
                                        e.userBurnable());

                        case TokenUpdated e -> new TokenUpdatedDto(
                                        e.bipHash(),
                                        e.tokenAddress(),
                                        e.name(),
                                        e.smallestUnitName(),
                                        e.websiteUrl(),
                                        e.logoUrl());

                        case TokenMinted e -> new TokenMintedDto(
                                        e.bipHash(),
                                        e.tokenAddress(),
                                        e.recipient(),
                                        e.amount());

                        case TokenBurned e -> new TokenBurnedDto(
                                        e.bipHash(),
                                        e.tokenAddress(),
                                        e.owner(),
                                        e.requestedAmount(),
                                        e.actualBurnedAmount());

                        case TokenSupplyUpdated e -> new TokenSupplyUpdatedDto(
                                        e.tokenAddress(),
                                        e.newTotalSupply());

                        case AuthorityAdded e -> new AuthorityAddedDto(
                                        e.bipHash(),
                                        e.authorityAddress());

                        case AuthorityRemoved e -> new AuthorityRemovedDto(
                                        e.bipHash(),
                                        e.authorityAddress());

                        case NetworkParamsChanged e -> new NetworkParamsChangedDto(
                                        e.bipHash(),
                                        e.newBlockReward(),
                                        e.newBlockRewardPoolAddress(),
                                        e.newTargetMiningTimeMs(),
                                        e.newAsertHalfLifeBlocks(),
                                        e.newMinDifficulty(),
                                        e.newMinTxBaseFee(),
                                        e.newMinTxByteFee());

                        case AddressAliasAdded e -> new AddressAliasAddedDto(
                                        e.bipHash(),
                                        e.address(),
                                        e.alias());

                        case AddressAliasRemoved e -> new AddressAliasRemovedDto(
                                        e.bipHash(),
                                        e.alias());
                };
        }
}
