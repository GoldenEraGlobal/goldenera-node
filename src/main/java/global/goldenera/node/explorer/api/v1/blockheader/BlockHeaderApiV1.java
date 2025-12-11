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
package global.goldenera.node.explorer.api.v1.blockheader;

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
import global.goldenera.node.explorer.api.v1.blockheader.dtos.BlockHeaderDtoV1;
import global.goldenera.node.explorer.api.v1.blockheader.dtos.BlockHeaderDtoV1_Page;
import global.goldenera.node.explorer.api.v1.blockheader.mappers.BlockHeaderMapper;
import global.goldenera.node.explorer.services.core.ExBlockHeaderCoreService;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.GeneralApiSecurity;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@GeneralApiSecurity(ApiKeyPermission.READ_BLOCK_HEADER)
@RequestMapping(value = "api/explorer/v1/block-header")
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class BlockHeaderApiV1 {

        ExBlockHeaderCoreService exBlockHeaderCoreService;
        BlockHeaderMapper blockMapper;

        @GetMapping("by-hash/{hash}")
        public BlockHeaderDtoV1 apiV1BlockGetByHash(@PathVariable Hash hash) {
                return blockMapper.map(
                                exBlockHeaderCoreService.getByHash(hash));
        }

        @GetMapping("by-height/{height}")
        public BlockHeaderDtoV1 apiV1BlockGetByHeight(@PathVariable long height) {
                return blockMapper.map(
                                exBlockHeaderCoreService.getByHeight(height));
        }

        @GetMapping("page")
        public BlockHeaderDtoV1_Page apiV1BlockGetPage(
                        @RequestParam int pageNumber,
                        @RequestParam int pageSize,
                        @RequestParam(required = false) Sort.Direction direction,
                        @RequestParam(required = false) Address coinbase,
                        @RequestParam(required = false) Instant timestampFrom,
                        @RequestParam(required = false) Instant timestampTo,
                        @RequestParam(required = false) Integer minNumberOfTxs) {
                return blockMapper.map(
                                exBlockHeaderCoreService.getPage(
                                                pageNumber,
                                                pageSize,
                                                direction,
                                                coinbase,
                                                timestampFrom,
                                                timestampTo,
                                                minNumberOfTxs));
        }

        @GetMapping("by-height-range")
        public List<BlockHeaderDtoV1> apiV1BlockGetByHeightRange(
                        @RequestParam long fromHeight,
                        @RequestParam long toHeight,
                        @RequestParam(required = false) Address coinbase,
                        @RequestParam(required = false) Integer minNumberOfTransactions) {
                return blockMapper.map(
                                exBlockHeaderCoreService.findByHeightRange(
                                                fromHeight,
                                                toHeight,
                                                coinbase,
                                                minNumberOfTransactions));
        }

        @GetMapping("count")
        public long apiV1BlockGetCount() {
                return exBlockHeaderCoreService.getCount();
        }

        @GetMapping("by-hash/{hash}/affected-addresses")
        public List<Address> apiV1BlockGetAffectedAddressesByHash(@PathVariable Hash hash) {
                return exBlockHeaderCoreService.getAffectedAddressesByHash(hash);
        }

        @GetMapping("by-height/{height}/affected-addresses")
        public List<Address> apiV1BlockGetAffectedAddressesByHeight(@PathVariable long height) {
                return exBlockHeaderCoreService.getAffectedAddressesByHeight(height);
        }

}
