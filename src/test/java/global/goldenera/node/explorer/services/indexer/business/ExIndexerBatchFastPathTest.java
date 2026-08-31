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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerBlockDataCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerAccountHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerConsensusHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerTokenHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.mappers.ExIndexerTxToTransferMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerBatchFastPathTest {

	@Test
	void continuousBatchReadsAndUpdatesStatusOnlyOnce() {
		Hash initialHash = hash('1');
		Hash firstHash = hash('2');
		Hash secondHash = hash('3');
		BlockHeader firstHeader = header(11L, initialHash);
		BlockHeader secondHeader = header(12L, firstHash);
		Block first = block(11L, firstHash, firstHeader);
		Block second = block(12L, secondHash, secondHeader);
		BlockConnectedEvent firstEvent = event(first);
		BlockConnectedEvent secondEvent = event(second);
		ExIndexerStatusCoreService status = mock(ExIndexerStatusCoreService.class);
		when(status.getStatus()).thenReturn(Optional.of(
				new ExStatus(1, 10L, initialHash, java.time.Instant.EPOCH, "test")));
		ExIndexerTxToTransferMapper transferMapper = mock(ExIndexerTxToTransferMapper.class);
		when(transferMapper.map(any())).thenReturn(List.of());
		ExIndexerService service = new ExIndexerService(
				new SimpleMeterRegistry(), mock(ChainQuery.class),
				mock(ExIndexerEventReconstructionService.class), mock(ApplicationEventPublisher.class),
				status, mock(ExIndexerRevertService.class), mock(ExIndexerPartitionService.class),
				transferMapper, mock(ExIndexerBlockDataCoreService.class),
				mock(ExIndexerAccountHelperService.class), mock(ExIndexerConsensusHelperService.class),
				mock(ExIndexerTokenHelperService.class));

		service.handleBlockConnectedBatch(List.of(firstEvent, secondEvent));

		verify(status, times(1)).getStatus();
		verify(status, times(1)).updateStatus(secondHeader);
	}

	@Test
	void retrySafelySkipsAlreadyCommittedPrefixBeforeContinuingBatch() {
		Hash committedHash = hash('4');
		Hash nextHash = hash('5');
		BlockHeader committedHeader = header(64L, hash('3'));
		BlockHeader nextHeader = header(65L, committedHash);
		Block committed = block(64L, committedHash, committedHeader);
		Block next = block(65L, nextHash, nextHeader);
		ExStatus committedStatus = new ExStatus(
				1, 64L, committedHash, java.time.Instant.EPOCH, "test");
		ExIndexerStatusCoreService status = mock(ExIndexerStatusCoreService.class);
		when(status.getStatus()).thenReturn(Optional.of(committedStatus));
		when(status.getStatusOrThrow()).thenReturn(committedStatus);
		ExIndexerTxToTransferMapper transferMapper = mock(ExIndexerTxToTransferMapper.class);
		when(transferMapper.map(any())).thenReturn(List.of());
		ExIndexerBlockDataCoreService blockData = mock(ExIndexerBlockDataCoreService.class);
		ExIndexerService service = new ExIndexerService(
				new SimpleMeterRegistry(), mock(ChainQuery.class),
				mock(ExIndexerEventReconstructionService.class), mock(ApplicationEventPublisher.class),
				status, mock(ExIndexerRevertService.class), mock(ExIndexerPartitionService.class),
				transferMapper, blockData, mock(ExIndexerAccountHelperService.class),
				mock(ExIndexerConsensusHelperService.class), mock(ExIndexerTokenHelperService.class));

		service.handleBlockConnectedBatch(List.of(event(committed), event(next)));

		verify(blockData, never()).insertBlockHeader(eq(committed), any(), any(), any());
		verify(blockData).insertBlockHeader(eq(next), any(), any(), any());
		verify(status).updateStatus(nextHeader);
	}

	private BlockConnectedEvent event(Block block) {
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		when(event.getBlock()).thenReturn(block);
		when(event.getCumulativeDifficulty()).thenReturn(BigInteger.ONE);
		when(event.getMinerTotalFees()).thenReturn(Wei.ZERO);
		when(event.getMinerActualRewardPaid()).thenReturn(Wei.ZERO);
		when(event.getEvents()).thenReturn(List.of());
		when(event.getConnectedSource()).thenReturn(ConnectedSource.SYNC);
		return event;
	}

	private Block block(long height, Hash hash, BlockHeader header) {
		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(height);
		when(block.getHash()).thenReturn(hash);
		when(block.getHeader()).thenReturn(header);
		when(block.getTxs()).thenReturn(List.of());
		return block;
	}

	private BlockHeader header(long height, Hash previousHash) {
		BlockHeader header = mock(BlockHeader.class);
		when(header.getHeight()).thenReturn(height);
		when(header.getPreviousHash()).thenReturn(previousHash);
		return header;
	}

	private Hash hash(char digit) {
		return Hash.fromHexString("0x" + String.valueOf(digit).repeat(64));
	}
}
