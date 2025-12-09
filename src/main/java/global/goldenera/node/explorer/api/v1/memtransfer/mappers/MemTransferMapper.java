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
package global.goldenera.node.explorer.api.v1.memtransfer.mappers;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import global.goldenera.node.explorer.api.v1.memtransfer.dtos.MemTransferDtoV1;
import global.goldenera.node.explorer.api.v1.memtransfer.dtos.MemTransferDtoV1_Page;
import global.goldenera.node.explorer.api.v1.tx.mappers.TxMapper;
import global.goldenera.node.explorer.entities.ExMemTransfer;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MemTransferMapper {

	TxMapper txMapper;

	public MemTransferDtoV1 map(
			@NonNull ExMemTransfer in) {
		return new MemTransferDtoV1(
				in.getHash(),
				in.getAddedAt(),
				in.getTransferType(),
				in.getFrom(),
				in.getTo(),
				in.getTokenAddress(),
				in.getAmount(),
				in.getTxType(),
				in.getTxTimestamp(),
				in.getNetwork(),
				in.getVersion(),
				in.getFee(),
				in.getNonce(),
				in.getSize(),
				in.getSignature(),
				in.getReferenceHash(),
				in.getMessage(),
				in.getPayloadType(),
				txMapper.mapPayload(in.getPayload()));
	}

	public List<MemTransferDtoV1> map(@NonNull List<ExMemTransfer> in) {
		return in.stream().map(this::map).toList();
	}

	public MemTransferDtoV1_Page map(
			@NonNull Page<ExMemTransfer> in) {
		return new MemTransferDtoV1_Page(
				map(in.toList()),
				in.getTotalPages(),
				in.getTotalElements());
	}

}
