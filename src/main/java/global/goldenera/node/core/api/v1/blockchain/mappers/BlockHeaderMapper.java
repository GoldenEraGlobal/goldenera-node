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
package global.goldenera.node.core.api.v1.blockchain.mappers;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockHeaderDtoV1;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

/**
 * Maps BlockHeader domain objects to BlockHeaderDtoV1.
 */
@Component("coreBlockHeaderMapper")
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BlockHeaderMapper {

    public BlockHeaderDtoV1 map(@NonNull BlockHeader in) {
        return BlockHeaderDtoV1.builder()
                .version(in.getVersion())
                .height(in.getHeight())
                .timestamp(in.getTimestamp())
                .previousHash(in.getPreviousHash())
                .txRootHash(in.getTxRootHash())
                .stateRootHash(in.getStateRootHash())
                .difficulty(in.getDifficulty())
                .coinbase(in.getCoinbase())
                .nonce(in.getNonce())
                .signature(in.getSignature())
                .build();
    }
}
