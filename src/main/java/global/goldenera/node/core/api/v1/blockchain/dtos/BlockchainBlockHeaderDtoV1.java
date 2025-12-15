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
package global.goldenera.node.core.api.v1.blockchain.dtos;

import static lombok.AccessLevel.PRIVATE;

import java.util.List;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@FieldDefaults(level = PRIVATE)
public class BlockchainBlockHeaderDtoV1 {

    @Schema(description = "Block header data")
    BlockHeaderDtoV1 header;

    @Schema(description = "Block metadata")
    BlockchainBlockHeaderMetadataDtoV1 metadata;

    @Schema(description = "Block events (only included when withEvents=true)")
    List<BlockEventDtoV1> events;

    /**
     * Constructor without events (default)
     */
    public BlockchainBlockHeaderDtoV1(BlockHeaderDtoV1 header, BlockchainBlockHeaderMetadataDtoV1 metadata) {
        this.header = header;
        this.metadata = metadata;
        this.events = null;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    @FieldDefaults(level = PRIVATE)
    public static class BlockchainBlockHeaderMetadataDtoV1 {

        @Schema(description = "Block hash")
        Hash hash;

        @Schema(description = "Block size in bytes")
        int size;

        @Schema(description = "Block identity (miner address)")
        Address identity;

        @Schema(description = "Number of transactions in the block")
        int numOfTxs;
    }
}
