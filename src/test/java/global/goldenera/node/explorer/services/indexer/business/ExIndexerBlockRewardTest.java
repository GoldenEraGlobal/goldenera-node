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
package global.goldenera.node.explorer.services.indexer.business;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerBlockDataCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerAccountHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerConsensusHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerTokenHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.mappers.ExIndexerTxToTransferMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerBlockRewardTest {

	@Test
	void storesBlockRewardWithoutDuplicatingTransactionFees() {
		Hash parentHash = hash(1);
		Hash blockHash = hash(2);
		Block block = mock(Block.class);
		BlockHeader header = mock(BlockHeader.class);
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		ExStatus status = mock(ExStatus.class);
		ExIndexerStatusCoreService statusService = mock(ExIndexerStatusCoreService.class);
		ExIndexerBlockDataCoreService blockDataService = mock(ExIndexerBlockDataCoreService.class);
		ExIndexerTxToTransferMapper transferMapper = mock(ExIndexerTxToTransferMapper.class);

		when(block.getHeight()).thenReturn(1L);
		when(block.getHash()).thenReturn(blockHash);
		when(block.getHeader()).thenReturn(header);
		when(block.getTxs()).thenReturn(List.of());
		when(header.getPreviousHash()).thenReturn(parentHash);
		when(event.getBlock()).thenReturn(block);
		when(event.getCumulativeDifficulty()).thenReturn(BigInteger.valueOf(100));
		when(event.getMinerTotalFees()).thenReturn(Wei.valueOf(3));
		when(event.getMinerActualRewardPaid()).thenReturn(Wei.valueOf(10));
		when(status.getSyncedBlockHeight()).thenReturn(0L);
		when(status.getSyncedBlockHash()).thenReturn(parentHash);
		when(statusService.getStatusOrThrow()).thenReturn(status);
		when(transferMapper.map(event)).thenReturn(List.of());

		ExIndexerService service = new ExIndexerService(
				new SimpleMeterRegistry(),
				mock(ChainQuery.class),
				mock(ExIndexerEventReconstructionService.class),
				ignored -> { },
				statusService,
				mock(ExIndexerRevertService.class),
				mock(ExIndexerPartitionService.class),
				transferMapper,
				blockDataService,
				mock(ExIndexerAccountHelperService.class),
				mock(ExIndexerConsensusHelperService.class),
				mock(ExIndexerTokenHelperService.class));

		service.handleBlockConnected(event);

		verify(blockDataService).insertBlockHeader(
				block, BigInteger.valueOf(100), BigInteger.valueOf(3), BigInteger.valueOf(7));
	}

	private static Hash hash(int value) {
		return Hash.fromHexString("0x" + "%064x".formatted(value));
	}
}
