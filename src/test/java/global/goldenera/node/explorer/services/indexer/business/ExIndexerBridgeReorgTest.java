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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockDisconnectedEvent;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.explorer.entities.ExStatus;
import global.goldenera.node.explorer.events.ExBlockReorgEvent;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerBlockDataCoreService;
import global.goldenera.node.explorer.services.indexer.core.ExIndexerStatusCoreService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerAccountHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerConsensusHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.ExIndexerTokenHelperService;
import global.goldenera.node.explorer.services.indexer.helpers.mappers.ExIndexerTxToTransferMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExIndexerBridgeReorgTest {

	private final ChainQuery chainQuery = mock(ChainQuery.class);
	private final ExIndexerEventReconstructionService eventReconstruction = mock(
			ExIndexerEventReconstructionService.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final ExIndexerStatusCoreService statusService = mock(ExIndexerStatusCoreService.class);
	private final ExIndexerRevertService revertService = mock(ExIndexerRevertService.class);
	private ExIndexerService indexer;

	@BeforeEach
	void setUp() {
		indexer = new ExIndexerService(
				new SimpleMeterRegistry(),
				chainQuery,
				eventReconstruction,
				eventPublisher,
				statusService,
				revertService,
				mock(ExIndexerPartitionService.class),
				mock(ExIndexerTxToTransferMapper.class),
				mock(ExIndexerBlockDataCoreService.class),
				mock(ExIndexerAccountHelperService.class),
				mock(ExIndexerConsensusHelperService.class),
				mock(ExIndexerTokenHelperService.class));
	}

	@Test
	void explicitDisconnectPublishesOrphanBlockInsideRevertFlow() {
		Hash oldHash = hash(1);
		Hash parentHash = hash(2);
		Block orphan = block(12L, oldHash, parentHash);
		ExStatus status = status(12L, oldHash);
		StoredBlock storedBlock = mock(StoredBlock.class);
		when(storedBlock.getBlock()).thenReturn(orphan);
		when(statusService.getStatus()).thenReturn(Optional.of(status));
		when(chainQuery.getStoredBlockByHash(oldHash)).thenReturn(Optional.of(storedBlock));

		indexer.handleBlockDisconnected(new BlockDisconnectedEvent(this, orphan));

		verify(revertService).revertBlock(oldHash, 12L);
		ExBlockReorgEvent event = capturedReorg();
		assertThat(event.getOrphanBlock()).isSameAs(orphan);
		assertThat(event.getOldHash()).isEqualTo(oldHash);
		assertThat(event.getNewHash()).isEqualTo(parentHash);
		assertThat(event.getNewHeight()).isEqualTo(11L);
	}

	@Test
	void internalChainSplitPublishesRevertedExplorerHead() {
		Hash oldHash = hash(3);
		Hash incomingHash = hash(4);
		Hash nonAttachingPreviousHash = hash(5);
		Block orphan = block(4L, oldHash, hash(6));
		Block incoming = block(5L, incomingHash, nonAttachingPreviousHash);
		ExStatus oldStatus = status(4L, oldHash);
		ExStatus indexedStatus = status(5L, incomingHash);
		StoredBlock oldStoredBlock = mock(StoredBlock.class);
		when(oldStoredBlock.getBlock()).thenReturn(orphan);
		when(statusService.getStatusOrThrow()).thenReturn(oldStatus, oldStatus, indexedStatus);
		when(chainQuery.getStoredBlockByHashOrThrow(oldHash)).thenReturn(oldStoredBlock);

		indexer.handleBlockConnected(mockConnected(incoming));

		verify(revertService).revertBlock(oldHash, 4L);
		ExBlockReorgEvent event = capturedReorg();
		assertThat(event.getOrphanBlock()).isSameAs(orphan);
		assertThat(event.getOldHash()).isEqualTo(oldHash);
		assertThat(event.getNewHash()).isEqualTo(incomingHash);
	}

	private ExBlockReorgEvent capturedReorg() {
		ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
		verify(eventPublisher).publishEvent(event.capture());
		assertThat(event.getValue()).isInstanceOf(ExBlockReorgEvent.class);
		return (ExBlockReorgEvent) event.getValue();
	}

	private BlockConnectedEvent mockConnected(Block block) {
		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		when(event.getBlock()).thenReturn(block);
		return event;
	}

	private Block block(long height, Hash hash, Hash previousHash) {
		Block block = mock(Block.class);
		BlockHeader header = mock(BlockHeader.class);
		when(block.getHeight()).thenReturn(height);
		when(block.getHash()).thenReturn(hash);
		when(block.getHeader()).thenReturn(header);
		when(header.getPreviousHash()).thenReturn(previousHash);
		return block;
	}

	private ExStatus status(long height, Hash hash) {
		ExStatus status = mock(ExStatus.class);
		when(status.getSyncedBlockHeight()).thenReturn(height);
		when(status.getSyncedBlockHash()).thenReturn(hash);
		return status;
	}

	private Hash hash(int value) {
		return Hash.fromHexString("0x" + String.format("%064x", value));
	}
}
