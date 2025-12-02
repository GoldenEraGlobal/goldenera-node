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
package global.goldenera.node.core.mining;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * "The Brain" of mining.
 * Responsible for assembling a "Block Template" (header + body)
 * by pulling transactions from the mempool, creating a coinbase,
 * and executing them to get the final stateRootHash.
 */
@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MiningBlockAssemblerService {

	WorldStateFactory worldStateFactory;
	MempoolManager mempoolService;
	GeneralProperties generalConfig;
	StateProcessor stateProcessor;
	DifficultyCalculator difficultyService;

	/**
	 * Creates a new, mineable block template.
	 *
	 * @return A wrapper containing the BlockModel (header) and the list of
	 *         transactions.
	 * @throws Exception
	 *             if assembly fails
	 */
	public AssembledBlock createBlockTemplate(Block parentBlock) throws Exception {
		log.debug("Creating block template | Parent: {}", parentBlock.getHeight());
		BlockVersion blockVersion = BlockVersion.V1;

		WorldState worldState = worldStateFactory.createForMining(parentBlock.getHeader().getStateRootHash());
		NetworkParamsState params = worldState.getParams();
		long maxBlockSize = Constants.MAX_BLOCK_SIZE_IN_BYTES;
		long nextHeight = parentBlock.getHeight() + 1;
		long now = Instant.now().toEpochMilli();
		long timestamp = (now / 1000) * 1000;
		timestamp = Math.max(timestamp, parentBlock.getHeader().getTimestamp().toEpochMilli() + 1);

		long startSelect = System.currentTimeMillis();
		List<Tx> txs = getExecutableTransactions(maxBlockSize - 512);
		long endSelect = System.currentTimeMillis();
		log.debug("Selected {} tx(s) | Time: {}s", txs.size(),
				String.format("%.2f", (endSelect - startSelect) / 1000.0));

		Address beneficiaryAddress = generalConfig.getBeneficiaryAddress();

		long startExec = System.currentTimeMillis();
		StateProcessor.ExecutionResult result = stateProcessor.executeMiningBatch(
				worldState,
				SimpleBlock.builder()
						.height(nextHeight)
						.timestamp(Instant.ofEpochMilli(timestamp))
						.coinbase(beneficiaryAddress)
						.build(),
				txs,
				params);
		long endExec = System.currentTimeMillis();
		log.debug("Executed {} tx(s) | Time: {}s", txs.size(), String.format("%.2f", (endExec - startExec) / 1000.0));

		// IMPORTANT: Use ONLY valid transactions for hash calculations!
		List<Tx> validTxs = result.getValidTxs();

		Hash stateRootHash = worldState.calculateRootHash();
		Hash txRootHash = TxRootUtil.txRootHash(validTxs); // Use valid txs only!
		BigInteger difficulty = difficultyService.calculateNextDifficulty(parentBlock.getHeader(), params);
		BlockHeaderTemplate template = BlockHeaderTemplate.builder()
				.version(blockVersion)
				.height(nextHeight)
				.timestamp(Instant.ofEpochMilli(timestamp))
				.previousHash(parentBlock.getHash())
				.difficulty(difficulty)
				.txRootHash(txRootHash)
				.stateRootHash(stateRootHash)
				.coinbase(beneficiaryAddress)
				.build();

		log.info("Block template created for height {} with {} txs, stateRoot: {}, difficulty: {}",
				template.getHeight(), validTxs.size(), stateRootHash.toShortLogString(), difficulty);

		return AssembledBlock.builder()
				.blockTemplate(template)
				.txs(validTxs)
				.invalidTxs(result.getInvalidTxs())
				.build();
	}

	/**
	 * Called by the mining service (BlockAssembler) to get
	 * the best transactions for a new block.
	 *
	 * @param maxBlockSizeBytes
	 *            The maximum size the transactions can fill.
	 * @return A list of transactions, sorted by fee, ready for inclusion.
	 */
	public List<Tx> getExecutableTransactions(long maxBlockSizeBytes) {
		Iterator<MempoolEntry> candidates = mempoolService.getTxIterator();
		List<Tx> blockTxs = new ArrayList<>();
		Set<Hash> seenHashes = new HashSet<>();
		long currentSize = 0;

		while (candidates.hasNext()) {
			MempoolEntry entry = candidates.next();
			Tx tx = entry.getTx();
			if (seenHashes.add(tx.getHash())) {
				if (currentSize + tx.getSize() <= maxBlockSizeBytes) {
					blockTxs.add(tx);
					currentSize += tx.getSize();
				} else {
					break;
				}
			}
		}
		return blockTxs;
	}

	@Data
	@Builder
	public static class BlockHeaderTemplate implements BlockHeader {
		BlockVersion version;
		long height;
		Instant timestamp;
		Hash previousHash;
		Hash txRootHash;
		Hash stateRootHash;
		BigInteger difficulty;
		Address coinbase;

		public BlockHeaderImpl toBlockHeader() {
			return BlockHeaderImpl.builder()
					.version(version)
					.height(height)
					.timestamp(timestamp)
					.previousHash(previousHash)
					.difficulty(difficulty)
					.txRootHash(txRootHash)
					.stateRootHash(stateRootHash)
					.coinbase(coinbase)
					.nonce(0L)
					.build();
		}

		@Override
		public Hash getHash() {
			return BlockHeaderUtil.hash(this);
		}

		@Override
		public long getNonce() {
			return 0;
		}

		@Override
		public Signature getSignature() {
			return Signature.ZERO;
		}

		@Override
		public int getSize() {
			return BlockHeaderUtil.size(this);
		}
	}

	@Data
	@Builder
	public static class AssembledBlock {
		BlockHeaderTemplate blockTemplate;
		List<Tx> txs;
		List<Tx> invalidTxs;
	}

}