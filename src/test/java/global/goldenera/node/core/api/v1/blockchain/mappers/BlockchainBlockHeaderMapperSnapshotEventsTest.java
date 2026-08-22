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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockEventDtoV1.BlockRewardDto;
import global.goldenera.node.core.api.v1.blockchain.dtos.BlockHeaderDtoV1;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BlockReward;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;

class BlockchainBlockHeaderMapperSnapshotEventsTest {

	@Test
	void exposesManifestBoundSnapshotEventsWhenCoreApiRequestsEvents() {
		BlockHeaderMapper headerMapper = mock(BlockHeaderMapper.class);
		BlockEventMapper eventMapper = mock(BlockEventMapper.class);
		StoredBlock stored = mock(StoredBlock.class);
		Block block = mock(Block.class);
		BlockHeader header = mock(BlockHeader.class);
		List<BlockEvent> events = List.of(
				new BlockReward(Address.ZERO, Address.ZERO, Wei.valueOf(7), null));
		List<BlockEventDtoV1> eventDtos = List.of(
				new BlockRewardDto(Address.ZERO, Address.ZERO, Wei.valueOf(7), null));
		when(stored.getHash()).thenReturn(Hash.ZERO);
		when(stored.getBlockSize()).thenReturn(123);
		when(stored.getIdentity()).thenReturn(Address.ZERO);
		when(stored.getTxCount()).thenReturn(0);
		when(stored.getBlock()).thenReturn(block);
		when(stored.getEvents()).thenReturn(events);
		when(block.getHeader()).thenReturn(header);
		when(headerMapper.map(header)).thenReturn(mock(BlockHeaderDtoV1.class));
		when(eventMapper.map(same(events))).thenReturn(eventDtos);

		var result = new BlockchainBlockHeaderMapper(headerMapper, eventMapper).map(stored, true);

		assertThat(result.getEvents()).isEqualTo(eventDtos);
		verify(eventMapper).map(same(events));
	}
}
