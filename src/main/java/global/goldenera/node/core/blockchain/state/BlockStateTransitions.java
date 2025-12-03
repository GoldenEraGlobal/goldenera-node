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
package global.goldenera.node.core.blockchain.state;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.blockchain.BlockRepository;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.shared.exceptions.GEFailedException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockStateTransitions {

	BlockRepository blockRepository;
	ApplicationEventPublisher applicationEventPublisher;

	public void connectBlock(
			@NonNull Block block,
			@NonNull WorldState worldState,
			@NonNull ConnectedSource source,
			StateProcessor.ExecutionResult executionResult,
			Address receivedFrom,
			Instant receivedAt) {
		long start = System.currentTimeMillis();
		long height = block.getHeight();
		BigInteger cumulativeDifficulty;

		if (source == ConnectedSource.GENESIS) {
			cumulativeDifficulty = block.getHeader().getDifficulty();
		} else {
			StoredBlock parentStored = blockRepository
					.getStoredBlockByHash(block.getHeader().getPreviousHash())
					.orElseThrow(() -> new GEFailedException(
							"Parent block not found: " + block.getHeader().getPreviousHash()));

			cumulativeDifficulty = parentStored.getCumulativeDifficulty()
					.add(block.getHeader().getDifficulty());
		}

		StoredBlock storedBlockToSave = StoredBlock.builder()
				.block(block)
				.cumulativeDifficulty(cumulativeDifficulty)
				.receivedFrom(receivedFrom)
				.receivedAt(receivedAt)
				.connectedSource(source)
				.build();

		boolean isNewHead = false;

		if (source == ConnectedSource.GENESIS) {
			isNewHead = true;
		} else {
			StoredBlock currentHead = blockRepository.getLatestStoredBlock().orElse(null);
			if (currentHead == null || cumulativeDifficulty.compareTo(currentHead.getCumulativeDifficulty()) > 0) {
				isNewHead = true;
			}
		}

		final boolean performFullConnect = isNewHead;

		try {
			blockRepository.getRepository().executeAtomicBatch(batch -> {
				worldState.persistToBatch(batch);
				if (performFullConnect) {
					blockRepository.addBlockToBatch(batch, storedBlockToSave);
				} else {
					try {
						blockRepository.saveBlockDataToBatch(batch, storedBlockToSave);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});
		} catch (RuntimeException e) {
			log.error("Atomic commit failed for block {}: {}", block.getHeight(), e.getMessage(), e);
			worldState.rollback();
			throw new GEFailedException("Atomic commit failed for block " + block.getHeight() + ": " + e.getMessage(),
					e.getCause() != null ? e.getCause() : e);
		}

		long end = System.currentTimeMillis();

		if (performFullConnect) {
			log.info("Block connected as NEW HEAD at height {} (TD: {}) in {}s",
					block.getHeight(), cumulativeDifficulty, String.format("%.2f", (end - start) / 1000.0));

			Wei totalFees = height == 0 ? Wei.ZERO : executionResult.getTotalFeesCollected();
			Wei actualRewardPaid = height == 0 ? Wei.ZERO : executionResult.getMinerActualRewardPaid();
			Map<Hash, Wei> actualBurnAmounts = height == 0 ? Map.of() : executionResult.getActualBurnAmounts();

			BlockConnectedEvent event = new BlockConnectedEvent(
					this,
					source,
					block,
					worldState.getBalanceDiffs(),
					worldState.getNonceDiffs(),
					worldState.getTokenDiffs(),
					worldState.getBipDiffs(),
					worldState.getParamsDiff(),
					worldState.getDirtyAuthorities(),
					worldState.getAuthoritiesRemovedWithState(),
					worldState.getDirtyAddressAliases(),
					worldState.getAliasesRemovedWithState(),
					totalFees,
					actualRewardPaid,
					cumulativeDifficulty,
					actualBurnAmounts,
					receivedFrom,
					receivedAt);

			applicationEventPublisher.publishEvent(event);
		} else {
			log.info("Block {} stored as FORK (TD: {} <= Current Head). No event emitted.",
					block.getHeight(), cumulativeDifficulty);
		}
	}
}