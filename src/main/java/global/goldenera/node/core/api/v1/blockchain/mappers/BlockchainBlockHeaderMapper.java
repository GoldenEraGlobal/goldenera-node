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

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainBlockHeaderDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockchainBlockHeaderDtoV1.BlockchainBlockHeaderMetadataDtoV1;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Component
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BlockchainBlockHeaderMapper {

    BlockHeaderMapper blockHeaderMapper;
    BlockEventMapper blockEventMapper;

    /**
     * Maps StoredBlock to DTO without events.
     */
    public BlockchainBlockHeaderDtoV1 map(@NonNull StoredBlock in) {
        return map(in, false);
    }

    /**
     * Maps StoredBlock to DTO with optional events.
     */
    public BlockchainBlockHeaderDtoV1 map(@NonNull StoredBlock in, boolean withEvents) {
        BlockchainBlockHeaderMetadataDtoV1 metadata = new BlockchainBlockHeaderMetadataDtoV1(
                in.getHash(),
                in.getBlockSize(),
                in.getTxCount());

        List<BlockEventDtoV1> eventDtos = null;
        if (withEvents && in.getEvents() != null && !in.getEvents().isEmpty()) {
            eventDtos = blockEventMapper.map(in.getEvents());
        }

        return new BlockchainBlockHeaderDtoV1(
                blockHeaderMapper.map(in.getBlock().getHeader()),
                metadata,
                eventDtos);
    }

    /**
     * Maps list of StoredBlocks to DTOs without events.
     */
    public List<BlockchainBlockHeaderDtoV1> map(@NonNull List<StoredBlock> in) {
        return map(in, false);
    }

    /**
     * Maps list of StoredBlocks to DTOs with optional events.
     */
    public List<BlockchainBlockHeaderDtoV1> map(@NonNull List<StoredBlock> in, boolean withEvents) {
        List<BlockchainBlockHeaderDtoV1> result = new ArrayList<>(in.size());
        for (StoredBlock storedBlock : in) {
            result.add(map(storedBlock, withEvents));
        }
        return result;
    }

    /**
     * Maps Block to DTO (no events available from Block).
     */
    public BlockchainBlockHeaderDtoV1 mapBlock(@NonNull Block in) {
        return new BlockchainBlockHeaderDtoV1(
                blockHeaderMapper.map(in.getHeader()),
                new BlockchainBlockHeaderMetadataDtoV1(
                        in.getHash(),
                        in.getSize(),
                        in.getTxs().size()));
    }

    public List<BlockchainBlockHeaderDtoV1> mapBlock(@NonNull List<Block> in) {
        List<BlockchainBlockHeaderDtoV1> result = new ArrayList<>(in.size());
        for (Block block : in) {
            result.add(mapBlock(block));
        }
        return result;
    }

    /**
     * Maps Block to DTO (no events available from Block).
     */
    public BlockchainBlockHeaderDtoV1 mapBlockWithEvents(@NonNull Block in, @NonNull List<BlockEvent> events) {
        return new BlockchainBlockHeaderDtoV1(
                blockHeaderMapper.map(in.getHeader()),
                new BlockchainBlockHeaderMetadataDtoV1(
                        in.getHash(),
                        in.getSize(),
                        in.getTxs().size()),
                blockEventMapper.map(events));
    }
}
