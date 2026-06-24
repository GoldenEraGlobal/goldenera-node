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
package global.goldenera.node.explorer.api.v1.common.mappers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import global.goldenera.node.explorer.api.v1.account.dtos.AccountBalanceDtoV1;
import global.goldenera.node.explorer.api.v1.account.mappers.AccountBalanceMapper;
import global.goldenera.node.explorer.api.v1.addressalias.dtos.AddressAliasDtoV1;
import global.goldenera.node.explorer.api.v1.addressalias.mappers.AddressAliasMapper;
import global.goldenera.node.explorer.api.v1.authority.dtos.AuthorityDtoV1;
import global.goldenera.node.explorer.api.v1.authority.mappers.AuthorityMapper;
import global.goldenera.node.explorer.api.v1.blockheader.dtos.BlockHeaderDtoV1;
import global.goldenera.node.explorer.api.v1.blockheader.mappers.BlockHeaderMapper;
import global.goldenera.node.explorer.api.v1.common.dtos.SearchDtoV1;
import global.goldenera.node.explorer.api.v1.memtransfer.dtos.MemTransferDtoV1;
import global.goldenera.node.explorer.api.v1.memtransfer.mappers.MemTransferMapper;
import global.goldenera.node.explorer.api.v1.token.dtos.TokenDtoV1;
import global.goldenera.node.explorer.api.v1.token.mappers.TokenMapper;
import global.goldenera.node.explorer.api.v1.tx.dtos.TxDtoV1;
import global.goldenera.node.explorer.api.v1.tx.mappers.TxMapper;
import global.goldenera.node.explorer.api.v1.validator.dtos.ValidatorDtoV1;
import global.goldenera.node.explorer.api.v1.validator.mappers.ValidatorMapper;
import global.goldenera.node.explorer.services.core.ExCommonCoreService.ExSearchResult;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CommonMapper {

        AccountBalanceMapper accountBalanceMapper;
        BlockHeaderMapper blockHeaderMapper;
        TxMapper txMapper;
        TokenMapper tokenMapper;
        MemTransferMapper memTransferMapper;
        ValidatorMapper validatorMapper;
        AuthorityMapper authorityMapper;
        AddressAliasMapper addressAliasMapper;

        public SearchDtoV1 map(@NonNull ExSearchResult in) {
                List<BlockHeaderDtoV1> blocks = in.getBlocks() != null
                                ? in.getBlocks().stream().map(blockHeaderMapper::map).toList()
                                : Collections.emptyList();

                List<TxDtoV1> transactions = in.getTransactions() != null
                                ? txMapper.map(in.getTransactions())
                                : new ArrayList<>();

                List<MemTransferDtoV1> mempoolTransactions = in.getMempoolTransactions() != null
                                ? memTransferMapper.map(in.getMempoolTransactions())
                                : new ArrayList<>();

                List<AccountBalanceDtoV1> accounts = in.getAccounts() != null
                                ? in.getAccounts().stream().map(accountBalanceMapper::map).toList()
                                : new ArrayList<>();

                List<ValidatorDtoV1> validators = in.getValidators() != null
                                ? in.getValidators().stream().map(validatorMapper::map).toList()
                                : Collections.emptyList();

                List<AuthorityDtoV1> authorities = in.getAuthorities() != null
                                ? in.getAuthorities().stream().map(authorityMapper::map).toList()
                                : Collections.emptyList();

                List<AddressAliasDtoV1> aliases = in.getAliases() != null
                                ? in.getAliases().stream().map(addressAliasMapper::map).toList()
                                : Collections.emptyList();

                List<TokenDtoV1> tokens = in.getTokens() != null
                                ? in.getTokens().stream().map(tokenMapper::map).toList()
                                : Collections.emptyList();

                return new SearchDtoV1(blocks, transactions, mempoolTransactions, accounts, tokens, validators,
                                authorities,
                                aliases, in.getCount());
        }
}
