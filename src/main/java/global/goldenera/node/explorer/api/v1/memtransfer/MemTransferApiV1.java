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
package global.goldenera.node.explorer.api.v1.memtransfer;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.explorer.api.v1.memtransfer.dtos.MemTransferDtoV1;
import global.goldenera.node.explorer.api.v1.memtransfer.dtos.MemTransferDtoV1_Page;
import global.goldenera.node.explorer.api.v1.memtransfer.mappers.MemTransferMapper;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.explorer.services.core.ExMemTransferCoreService;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@PreAuthorize("hasAuthority('READ_MEMPOOL_TX')")
@RequestMapping(value = "api/explorer/v1/mem-transfer")
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MemTransferApiV1 {

	ExMemTransferCoreService exMemTransferCoreService;
	MemTransferMapper memTransferMapper;

	@GetMapping("by-hash/{hash}")
	public MemTransferDtoV1 apiV1MemTransferGetByHash(@PathVariable Hash hash) {
		return memTransferMapper.map(exMemTransferCoreService.getByHash(hash));
	}

	@GetMapping("page")
	public MemTransferDtoV1_Page apiV1MemTransferGetPage(
			@RequestParam int pageNumber,
			@RequestParam int pageSize,
			@RequestParam(required = false) Sort.Direction direction,
			@RequestParam(required = false) Address address,
			@RequestParam(required = false) Instant addedAtFrom,
			@RequestParam(required = false) Instant addedAtTo,
			@RequestParam(required = false) TransferType transferType,
			@RequestParam(required = false) TxType txType,
			@RequestParam(required = false) Address from,
			@RequestParam(required = false) Address to,
			@RequestParam(required = false) Address tokenAddress,
			@RequestParam(required = false) Hash referenceHash) {
		return memTransferMapper.map(
				exMemTransferCoreService.getPage(
						pageNumber,
						pageSize,
						direction,
						address,
						addedAtFrom,
						addedAtTo,
						transferType,
						txType,
						from,
						to,
						tokenAddress,
						referenceHash));
	}

	@GetMapping("count")
	public long apiV1MemTransferGetCount() {
		return exMemTransferCoreService.getCount();
	}

}
