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
package global.goldenera.node.explorer.api.v1.tx;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;

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
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.explorer.api.v1.common.BulkTxPageRequestV1;
import global.goldenera.node.explorer.api.v1.tx.dtos.TxDtoV1;
import global.goldenera.node.explorer.api.v1.tx.dtos.TxDtoV1_Page;
import global.goldenera.node.explorer.api.v1.tx.mappers.TxMapper;
import global.goldenera.node.explorer.services.core.ExTxCoreService;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.security.ExplorerApiSecurity;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@ExplorerApiSecurity(ApiKeyPermission.READ_TX)
@RequestMapping(value = "api/explorer/v1/tx")
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class TxApiV1 {

    ExTxCoreService exTxCoreService;
    TxMapper txMapper;

    @GetMapping("by-hash/{hash}")
    public TxDtoV1 apiV1TxGetByHash(@PathVariable Hash hash) {
        return txMapper.map(exTxCoreService.getByHash(hash));
    }

    @GetMapping("page")
    public TxDtoV1_Page apiV1TxGetPage(
            @RequestParam int pageNumber,
            @RequestParam int pageSize,
            @RequestParam(required = false) Sort.Direction direction,
            @RequestParam(required = false) Address address,
            @RequestParam(required = false) Long blockHeight,
            @RequestParam(required = false) Instant timestampFrom,
            @RequestParam(required = false) Instant timestampTo,
            @RequestParam(required = false) TxType type,
            @RequestParam(required = false) Hash blockHash,
            @RequestParam(required = false) Address sender,
            @RequestParam(required = false) Address recipient,
            @RequestParam(required = false) Address tokenAddress,
            @RequestParam(required = false) Hash referenceHash) {
        return txMapper.map(
                exTxCoreService.getPage(
                        pageNumber,
                        pageSize,
                        direction,
                        address,
                        blockHeight,
                        timestampFrom,
                        timestampTo,
                        type,
                        blockHash,
                        sender,
                        recipient,
                        tokenAddress,
                        referenceHash));
    }

    @PostMapping("page/bulk")
    public TxDtoV1_Page apiV1TxGetPageBulk(@RequestBody BulkTxPageRequestV1 request) {
        return txMapper.map(
                exTxCoreService.getPageBulk(
                        request.pageNumber(),
                        request.pageSize(),
                        request.direction(),
                        request.addresses(),
                        request.blockHeight(),
                        request.timestampFrom(),
                        request.timestampTo(),
                        request.type(),
                        request.blockHash(),
                        request.senders(),
                        request.recipients(),
                        request.tokenAddresses(),
                        request.referenceHash()));
    }

    @GetMapping("confirmations/{hash}")
    public long apiV1TxGetConfirmationsByHash(
            @PathVariable Hash hash) {
        return exTxCoreService.getConfirmationsByHash(hash);
    }

    @GetMapping("count")
    public long apiV1TxGetCount() {
        return exTxCoreService.getCount();
    }

}
