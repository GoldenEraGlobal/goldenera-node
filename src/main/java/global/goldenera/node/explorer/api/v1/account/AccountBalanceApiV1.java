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
package global.goldenera.node.explorer.api.v1.account;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.node.explorer.api.v1.account.dtos.AccountBalanceDtoV1;
import global.goldenera.node.explorer.api.v1.account.dtos.AccountBalanceDtoV1_Page;
import global.goldenera.node.explorer.api.v1.account.mappers.AccountBalanceMapper;
import global.goldenera.node.explorer.api.v1.common.BulkAccountBalancePageRequestV1;
import global.goldenera.node.explorer.services.core.ExAccountBalanceCoreService;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.GeneralApiSecurity;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@GeneralApiSecurity(ApiKeyPermission.READ_ACCOUNT)
@RequestMapping(value = "api/explorer/v1/account/balance")
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class AccountBalanceApiV1 {

        ExAccountBalanceCoreService accountBalanceCoreService;
        AccountBalanceMapper accountBalanceMapper;

        @GetMapping("by-address/{address}")
        public AccountBalanceDtoV1 apiV1AccountBalanceGetByAddressAndTokenContractAddress(
                        @PathVariable Address address,
                        @RequestParam(required = false) Address tokenAddress) {
                if (tokenAddress == null) {
                        tokenAddress = Address.NATIVE_TOKEN;
                }
                return accountBalanceMapper
                                .map(accountBalanceCoreService.getByAddressAndTokenAddress(address, tokenAddress));
        }

        @GetMapping("page")
        public AccountBalanceDtoV1_Page apiV1AccountBalanceGetPage(
                        @RequestParam int pageNumber,
                        @RequestParam int pageSize,
                        @RequestParam(required = false) Sort.Direction direction,
                        @RequestParam(required = false) Address address,
                        @RequestParam(required = false) Address tokenAddress,
                        @RequestParam(required = false) Wei balanceGreaterThan,
                        @RequestParam(required = false) Wei balanceLessThan,
                        @RequestParam(required = false) Long createdAtBlockHeightFrom,
                        @RequestParam(required = false) Long createdAtBlockHeightTo,
                        @RequestParam(required = false) Long updatedAtBlockHeightFrom,
                        @RequestParam(required = false) Long updatedAtBlockHeightTo,
                        @RequestParam(required = false) Instant createdAtTimestampFrom,
                        @RequestParam(required = false) Instant createdAtTimestampTo,
                        @RequestParam(required = false) Instant updatedAtTimestampFrom,
                        @RequestParam(required = false) Instant updatedAtTimestampTo) {
                return accountBalanceMapper.map(
                                accountBalanceCoreService.getPage(
                                                pageNumber,
                                                pageSize,
                                                direction,
                                                address,
                                                tokenAddress,
                                                balanceGreaterThan,
                                                balanceLessThan,
                                                createdAtBlockHeightFrom,
                                                createdAtBlockHeightTo,
                                                updatedAtBlockHeightFrom,
                                                updatedAtBlockHeightTo,
                                                createdAtTimestampFrom,
                                                createdAtTimestampTo,
                                                updatedAtTimestampFrom,
                                                updatedAtTimestampTo));
        }

        @PostMapping("page/bulk")
        public AccountBalanceDtoV1_Page apiV1AccountBalanceGetPageBulk(
                        @RequestBody BulkAccountBalancePageRequestV1 request) {
                return accountBalanceMapper.map(
                                accountBalanceCoreService.getPageBulk(
                                                request.pageNumber(),
                                                request.pageSize(),
                                                request.direction(),
                                                request.addresses(),
                                                request.tokenAddresses(),
                                                request.balanceGreaterThan(),
                                                request.balanceLessThan(),
                                                request.createdAtBlockHeightFrom(),
                                                request.createdAtBlockHeightTo(),
                                                request.updatedAtBlockHeightFrom(),
                                                request.updatedAtBlockHeightTo(),
                                                request.createdAtTimestampFrom(),
                                                request.createdAtTimestampTo(),
                                                request.updatedAtTimestampFrom(),
                                                request.updatedAtTimestampTo()));
        }

        @GetMapping("count")
        public long apiV1AccountBalanceGetCount() {
                return accountBalanceCoreService.getCount();
        }

}
