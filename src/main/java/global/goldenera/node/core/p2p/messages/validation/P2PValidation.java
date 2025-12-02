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
package global.goldenera.node.core.p2p.messages.validation;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.validation.BlockValidator;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockDto;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockHeaderDto;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PTxDto;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@AllArgsConstructor
public class P2PValidation {

	BlockValidator blockValidator;
	TxValidator txValidator;

	/**
	 * Validates P2P Block Header DTO.
	 * Checks strictly structural integrity and signature.
	 */
	public void validateBlockHeaderDto(P2PBlockHeaderDto blockHeaderDto) {
		if (blockHeaderDto == null || blockHeaderDto.getBlockHeader() == null) {
			throw new GEValidationException("Block Header cannot be null");
		}
		// Delegate to BlockValidator (Size -> Checkpoint -> PoW)
		blockValidator.validateHeader(blockHeaderDto.getBlockHeader());
	}

	public void validateBlockHeadersBatch(List<P2PBlockHeaderDto> headerDtos) {
		if (headerDtos == null || headerDtos.isEmpty())
			return;

		// Build lookup map for this batch
		Map<Long, Hash> batchHashes = new java.util.HashMap<>();
		for (P2PBlockHeaderDto dto : headerDtos) {
			if (dto.getBlockHeader() != null) {
				batchHashes.put(dto.getBlockHeader().getHeight(), dto.getBlockHeader().getHash());
			}
		}

		// Validate each header using the batch context
		headerDtos.parallelStream().forEach(dto -> {
			if (dto == null || dto.getBlockHeader() == null) {
				throw new GEValidationException("Block Header cannot be null");
			}
			blockValidator.validateHeader(dto.getBlockHeader(), batchHashes);
		});
	}

	/**
	 * Validates P2P Block DTO.
	 * Checks merkle root and all transaction signatures.
	 */
	public void validateBlockDto(P2PBlockDto blockDto) {
		if (blockDto == null || blockDto.getBlock() == null) {
			throw new GEValidationException("Block cannot be null");
		}
		// Validate Full Block (Header PoW + Size + Txs + Merkle)
		blockValidator.validateFullBlock(blockDto.getBlock());
	}

	/**
	 * Validates P2P Tx DTO (Ingress validation).
	 * Ensures the transaction structure is valid before we even convert it to
	 * Domain Object.
	 */
	public void validateTxDto(P2PTxDto txDto) {
		if (txDto == null || txDto.getTx() == null) {
			throw new GEValidationException("Tx cannot be null");
		}
		// Delegate to TxValidator (Stateless: Size -> Economy -> Payload -> Structure
		// -> Signature)
		txValidator.validateStateless(txDto.getTx());
	}

}