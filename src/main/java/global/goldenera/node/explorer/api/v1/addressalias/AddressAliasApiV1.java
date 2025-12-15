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
package global.goldenera.node.explorer.api.v1.addressalias;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.api.v1.addressalias.dtos.AddressAliasDtoV1;
import global.goldenera.node.explorer.api.v1.addressalias.dtos.AddressAliasDtoV1_Page;
import global.goldenera.node.explorer.api.v1.addressalias.mappers.AddressAliasMapper;
import global.goldenera.node.explorer.services.core.ExAddressAliasCoreService;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.ExplorerApiSecurity;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@RequestMapping(value = "api/explorer/v1/address-alias")
@ExplorerApiSecurity(ApiKeyPermission.READ_ADDRESS_ALIAS)
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class AddressAliasApiV1 {

        ExAddressAliasCoreService addressAliasCoreService;
        AddressAliasMapper addressAliasMapper;

        @GetMapping("by-alias/{alias}")
        public AddressAliasDtoV1 apiV1AddressAliasGetByAlias(
                        @PathVariable String alias) {
                return addressAliasMapper.map(
                                addressAliasCoreService.getByAlias(alias));
        }

        @GetMapping("by-address/{address}")
        public List<AddressAliasDtoV1> apiV1AddressAliasGetByAddress(
                        @PathVariable Address address) {
                return addressAliasMapper.map(addressAliasCoreService.getByAddress(address));
        }

        @GetMapping("page")
        public AddressAliasDtoV1_Page apiV1AddressAliasGetPage(
                        @RequestParam int pageNumber,
                        @RequestParam int pageSize,
                        @RequestParam(required = false) Sort.Direction direction,
                        @RequestParam(required = false) String aliasLike,
                        @RequestParam(required = false) Address address,
                        @RequestParam(required = false) Hash originTxHash,
                        @RequestParam(required = false) Long createdAtBlockHeightFrom,
                        @RequestParam(required = false) Long createdAtBlockHeightTo,
                        @RequestParam(required = false) Instant createdAtTimestampFrom,
                        @RequestParam(required = false) Instant createdAtTimestampTo) {
                return addressAliasMapper.map(
                                addressAliasCoreService.getPage(pageNumber, pageSize, direction, aliasLike,
                                                address, originTxHash, createdAtBlockHeightFrom,
                                                createdAtBlockHeightTo, createdAtTimestampFrom, createdAtTimestampTo));
        }

        @GetMapping("count")
        public long apiV1AddressAliasGetCount() {
                return addressAliasCoreService.getCount();
        }

}
