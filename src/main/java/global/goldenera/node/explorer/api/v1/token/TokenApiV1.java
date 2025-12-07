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
package global.goldenera.node.explorer.api.v1.token;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.explorer.api.v1.token.dtos.TokenDtoV1;
import global.goldenera.node.explorer.api.v1.token.dtos.TokenDtoV1_Page;
import global.goldenera.node.explorer.api.v1.token.mappers.TokenMapper;
import global.goldenera.node.explorer.services.core.ExTokenCoreService;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.GeneralApiSecurity;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@GeneralApiSecurity(ApiKeyPermission.READ_TOKEN)
@RequestMapping(value = "api/explorer/v1/token")
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class TokenApiV1 {

        ExTokenCoreService exTokenCoreService;
        TokenMapper tokenMapper;

        @GetMapping("by-address/{address}")
        public TokenDtoV1 apiV1TokenGetByAddress(@PathVariable Address address) {
                return tokenMapper.map(
                                exTokenCoreService.getByAddress(address));
        }

        @GetMapping("page")
        public TokenDtoV1_Page apiV1TokenGetPage(
                        @RequestParam int pageNumber,
                        @RequestParam int pageSize,
                        @RequestParam(required = false) Sort.Direction direction,
                        @RequestParam(required = false) String nameOrSmallestUnitNameLike,
                        @RequestParam(required = false) Hash originTxHash,
                        @RequestParam(required = false) Long createdAtBlockHeightFrom,
                        @RequestParam(required = false) Long createdAtBlockHeightTo,
                        @RequestParam(required = false) Long updatedAtBlockHeightFrom,
                        @RequestParam(required = false) Long updatedAtBlockHeightTo,
                        @RequestParam(required = false) Instant createdAtTimestampFrom,
                        @RequestParam(required = false) Instant createdAtTimestampTo,
                        @RequestParam(required = false) Instant updatedAtTimestampFrom,
                        @RequestParam(required = false) Instant updatedAtTimestampTo) {
                return tokenMapper.map(
                                exTokenCoreService.getPage(pageNumber, pageSize, direction, nameOrSmallestUnitNameLike,
                                                originTxHash, createdAtBlockHeightFrom, createdAtBlockHeightTo,
                                                updatedAtBlockHeightFrom, updatedAtBlockHeightTo,
                                                createdAtTimestampFrom, createdAtTimestampTo,
                                                updatedAtTimestampFrom, updatedAtTimestampTo));
        }

        @GetMapping("count")
        public long apiV1TokenGetCount() {
                return exTokenCoreService.getCount();
        }

}
