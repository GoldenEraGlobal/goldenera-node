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
package global.goldenera.node.explorer.api.v1.transfer;

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
import global.goldenera.node.explorer.api.v1.transfer.dtos.TransferDtoV1;
import global.goldenera.node.explorer.api.v1.transfer.dtos.TransferDtoV1_Page;
import global.goldenera.node.explorer.api.v1.transfer.mappers.TransferMapper;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.explorer.services.core.ExTransferCoreService;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@AllArgsConstructor
@PreAuthorize("hasAuthority('READ_TX')")
@RequestMapping(value = "api/explorer/v1/transfer")
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class TransferApiV1 {

	ExTransferCoreService exTransferCoreService;
	TransferMapper transferMapper;

	@GetMapping("by-id/{id}")
	public TransferDtoV1 apiV1TransferGetById(@PathVariable Long id) {
		return transferMapper.map(exTransferCoreService.getById(id));
	}

	@GetMapping("page")
	public TransferDtoV1_Page apiV1TransferGetPage(
			@RequestParam int pageNumber,
			@RequestParam int pageSize,
			@RequestParam(required = false) Sort.Direction direction,
			@RequestParam(required = false) Address address,
			@RequestParam(required = false) Long blockHeight,
			@RequestParam(required = false) Instant timestampFrom,
			@RequestParam(required = false) Instant timestampTo,
			@RequestParam(required = false) TransferType type,
			@RequestParam(required = false) Hash blockHash,
			@RequestParam(required = false) Hash txHash,
			@RequestParam(required = false) Address from,
			@RequestParam(required = false) Address to,
			@RequestParam(required = false) Address tokenAddress) {
		return transferMapper.map(
				exTransferCoreService.getPage(
						pageNumber,
						pageSize,
						direction,
						address,
						blockHeight,
						timestampFrom,
						timestampTo,
						type,
						blockHash,
						txHash,
						from,
						to,
						tokenAddress));
	}

	@GetMapping("count")
	public long apiV1TransferGetCount() {
		return exTransferCoreService.getCount();
	}

}
