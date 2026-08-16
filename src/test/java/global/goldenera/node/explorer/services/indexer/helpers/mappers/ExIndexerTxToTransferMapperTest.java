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
package global.goldenera.node.explorer.services.indexer.helpers.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BlockReward;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.explorer.repositories.ExNetworkParamsRepository;

class ExIndexerTxToTransferMapperTest {

	@Test
	void mapsUnlockHeightOnlyOntoBlockRewardTransfer() {
		ExNetworkParamsRepository repository = mock(ExNetworkParamsRepository.class);
		when(repository.findById(1)).thenReturn(Optional.empty());
		ExIndexerTxToTransferMapper mapper = new ExIndexerTxToTransferMapper(repository);

		Address miner = Address.fromHexString("0x0000000000000000000000000000000000000001");
		Address rewardPool = Address.fromHexString("0x0000000000000000000000000000000000000002");
		BlockHeader header = mock(BlockHeader.class);
		when(header.getCoinbase()).thenReturn(miner);
		when(header.getTimestamp()).thenReturn(Instant.parse("2026-08-16T00:00:00Z"));

		Block block = mock(Block.class);
		when(block.getHeight()).thenReturn(11L);
		when(block.getHash()).thenReturn(Hash.ZERO);
		when(block.getHeader()).thenReturn(header);
		when(block.getTxs()).thenReturn(List.of());

		BlockConnectedEvent event = mock(BlockConnectedEvent.class);
		when(event.getBlock()).thenReturn(block);
		when(event.getMinerActualRewardPaid()).thenReturn(Wei.valueOf(12));
		when(event.getMinerTotalFees()).thenReturn(Wei.valueOf(2));
		when(event.getEvents()).thenReturn(List.of(
				new BlockReward(miner, rewardPool, Wei.valueOf(10), 14L)));

		var transfers = mapper.map(event);

		assertThat(transfers).hasSize(2);
		assertThat(transfers).filteredOn(transfer -> transfer.getType() == TransferType.BLOCK_REWARD)
				.singleElement()
				.satisfies(transfer -> {
					assertThat(transfer.getFrom()).isEqualTo(rewardPool);
					assertThat(transfer.getUnlockBlockHeight()).isEqualTo(14L);
				});
		assertThat(transfers).filteredOn(transfer -> transfer.getType() == TransferType.BLOCK_FEES)
				.singleElement()
				.satisfies(transfer -> assertThat(transfer.getUnlockBlockHeight()).isNull());

		when(event.getEvents()).thenReturn(List.of(
				new BlockReward(miner, rewardPool, Wei.valueOf(10), null)));
		assertThat(mapper.map(event)).filteredOn(transfer -> transfer.getType() == TransferType.BLOCK_REWARD)
				.singleElement()
				.satisfies(transfer -> assertThat(transfer.getUnlockBlockHeight()).isNull());
	}
}
