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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.time.BlockTimestampReservation;
import global.goldenera.node.core.blockchain.time.ProductionChainClock;
import global.goldenera.node.core.mempool.MempoolManager;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.node.IdentityService;
import global.goldenera.node.core.processing.StateProcessor;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.shared.properties.GeneralProperties;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * "The Brain" of mining.
 * Responsible for assembling a "Block Template" (header + body)
 * by pulling transactions from the mempool, creating a coinbase,
 * and executing them to get the final stateRootHash.
 *
 * Dynamic block sizing:
 * - Low mempool utilization (< 30%): Target 1/3 of max block size
 * - Medium utilization (30-80%): Target 1/2 of max block size
 * - High utilization (> 80%): Use full block size
 */
@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MiningBlockAssemblerService {

	WorldStateFactory worldStateFactory;
	MempoolManager mempoolService;
	MempoolProperties mempoolProperties;
	GeneralProperties generalConfig;
	StateProcessor stateProcessor;
	DifficultyCalculator difficultyService;
	IdentityService identityService;
	ValidatorMiningPolicyService validatorMiningPolicyService;
	ChainClock chainClock;
	AtomicReference<String> lastMiningIneligibility = new AtomicReference<>();

	@Autowired
	public MiningBlockAssemblerService(WorldStateFactory worldStateFactory, MempoolManager mempoolService,
			MempoolProperties mempoolProperties, GeneralProperties generalConfig, StateProcessor stateProcessor,
			DifficultyCalculator difficultyService, IdentityService identityService,
			ValidatorMiningPolicyService validatorMiningPolicyService, ChainClock chainClock) {
		this.worldStateFactory = worldStateFactory;
		this.mempoolService = mempoolService;
		this.mempoolProperties = mempoolProperties;
		this.generalConfig = generalConfig;
		this.stateProcessor = stateProcessor;
		this.difficultyService = difficultyService;
		this.identityService = identityService;
		this.validatorMiningPolicyService = validatorMiningPolicyService;
		this.chainClock = chainClock;
	}

	/** Test-friendly constructor preserving the former production behavior. */
	public MiningBlockAssemblerService(WorldStateFactory worldStateFactory, MempoolManager mempoolService,
			MempoolProperties mempoolProperties, GeneralProperties generalConfig, StateProcessor stateProcessor,
			DifficultyCalculator difficultyService, IdentityService identityService,
			ValidatorMiningPolicyService validatorMiningPolicyService) {
		this(worldStateFactory, mempoolService, mempoolProperties, generalConfig, stateProcessor, difficultyService,
				identityService, validatorMiningPolicyService, new ProductionChainClock());
	}

	/**
	 * Creates a new, mineable block template.
	 *
	 * @return A wrapper containing the BlockModel (header) and the list of
	 *         transactions.
	 * @throws Exception
	 *             if assembly fails
	 */
	public Optional<AssembledBlock> createBlockTemplate(Block parentBlock) throws Exception {
		return createBlockTemplateInternal(parentBlock, Optional.empty(), false);
	}

	/**
	 * Creates a template using a parent-bound timestamp reserved for this attempt.
	 */
	public Optional<AssembledBlock> createBlockTemplate(
			Block parentBlock,
			BlockTimestampReservation timestampReservation) throws Exception {
		return createBlockTemplateInternal(parentBlock, Optional.of(timestampReservation), false);
	}

	/**
	 * Authors a sandbox control candidate through the production assembler while
	 * bypassing only the local validator mining-policy eligibility precheck. The
	 * returned candidate is still subject to normal proof-of-work, signing and
	 * receiver-side validation.
	 */
	public Optional<AssembledBlock> createSandboxCandidateTemplate(
			Block parentBlock,
			BlockTimestampReservation timestampReservation) throws Exception {
		return createBlockTemplateInternal(parentBlock, Optional.of(timestampReservation), true);
	}

	private Optional<AssembledBlock> createBlockTemplateInternal(
			Block parentBlock,
			Optional<BlockTimestampReservation> timestampReservation,
			boolean bypassMiningPolicyPrecheck) throws Exception {
		log.debug("Creating block template | Parent: {}", parentBlock.getHeight());
		BlockVersion blockVersion = BlockVersion.V1;

		WorldState worldState = worldStateFactory.createForMining(parentBlock.getHeader().getStateRootHash());
		NetworkParamsState params = worldState.getParams();
		long nextHeight = parentBlock.getHeight() + 1;

		// Check if miner identity is a valid validator (skip if no validators
		// registered - open mining)
		Address minerIdentity = identityService.getNodeIdentityAddress();

		if (minerIdentity.equals(Address.ZERO)) {
			if (miningIneligibilityChanged(nextHeight, "zero_identity")) {
				log.error("Mining hashing is waiting for block #{}: node identity cannot be zero", nextHeight);
			}
			return Optional.empty();
		}

		if (params.getCurrentValidatorCount() > 0 && !worldState.getValidator(minerIdentity).exists()) {
			if (miningIneligibilityChanged(nextHeight, "not_registered")) {
				log.info("Mining hashing is waiting for block #{}: node identity {} is not a registered validator",
						nextHeight, minerIdentity.toChecksumAddress());
			}
			return Optional.empty();
		}

		// Beneficiary address is where mining rewards go (may differ from miner
		// identity)
		Address beneficiaryAddress = generalConfig.getBeneficiaryAddress();

		if (!bypassMiningPolicyPrecheck
				&& !validatorMiningPolicyService.isCandidateEligible(worldState, nextHeight, minerIdentity)) {
			if (miningIneligibilityChanged(nextHeight, "share_exhausted")) {
				log.info("Mining hashing is waiting for block #{}: validator {} exhausted its current mining share",
						nextHeight, minerIdentity.toChecksumAddress());
			}
			return Optional.empty();
		}
		lastMiningIneligibility.set(null);

		// Dynamic block size based on mempool utilization (height-aware for fork
		// overrides)
		long maxBlockSize = calculateDynamicBlockSize(nextHeight);
		Instant timestamp = timestampReservation
				.map(reservation -> reservation.consume(parentBlock.getHeader()))
				.orElseGet(() -> chainClock.nextBlockTimestamp(parentBlock.getHeader()));

		long startSelect = System.currentTimeMillis();
		List<Tx> txs = getExecutableTransactions(maxBlockSize - 512, worldState);
		long endSelect = System.currentTimeMillis();
		log.debug("Selected {} tx(s) for block size {} | Time: {}s", txs.size(), maxBlockSize,
				String.format("%.2f", (endSelect - startSelect) / 1000.0));

		long startExec = System.currentTimeMillis();
		StateProcessor.ExecutionResult result = stateProcessor.executeMiningBatch(
				worldState,
				SimpleBlock.builder()
						.height(nextHeight)
						.timestamp(timestamp)
						.coinbase(beneficiaryAddress)
						.identity(minerIdentity)
						.build(),
				txs,
				params);
		long endExec = System.currentTimeMillis();
		log.debug("Executed {} tx(s) | Time: {}s", txs.size(), String.format("%.2f", (endExec - startExec) / 1000.0));

		List<Tx> validTxs = result.getValidTxs();
		Hash stateRootHash = worldState.calculateRootHash();
		Hash txRootHash = TxRootUtil.txRootHash(validTxs);
		BigInteger difficulty = difficultyService.calculateNextDifficulty(parentBlock.getHeader(), params);
		BlockHeaderTemplate template = BlockHeaderTemplate.builder()
				.version(blockVersion)
				.height(nextHeight)
				.timestamp(timestamp)
				.previousHash(parentBlock.getHash())
				.difficulty(difficulty)
				.txRootHash(txRootHash)
				.stateRootHash(stateRootHash)
				.coinbase(beneficiaryAddress)
				.build();

		log.info("Block template created for height {} with {} txs, stateRoot: {}, difficulty: {}",
				template.getHeight(), validTxs.size(), stateRootHash.toShortLogString(), difficulty);

		if (result.getInvalidTxs() != null && !result.getInvalidTxs().isEmpty()) {
			log.warn("Mining assembler selected {} txs, but {} failed execution. Invalid: {}",
					txs.size(), result.getInvalidTxs().size(),
					result.getInvalidTxs().stream().map(tx -> tx.getHash().toShortLogString()).toList());
		}

		return Optional.of(AssembledBlock.builder()
				.blockTemplate(template)
				.txs(validTxs)
				.selectedTxs(List.copyOf(txs))
				.invalidTxs(result.getInvalidTxs())
				.build());
	}

	private boolean miningIneligibilityChanged(long candidateHeight, String reason) {
		String current = candidateHeight + ":" + reason;
		return !current.equals(lastMiningIneligibility.getAndSet(current));
	}

	/**
	 * Called by the mining service (BlockAssembler) to get
	 * the best transactions for a new block.
	 *
	 * @param maxBlockSizeBytes
	 *            The maximum size the transactions can fill.
	 * @param worldState
	 *            The world state to check nonces against.
	 * @return A list of transactions, sorted by fee, ready for inclusion.
	 */
	public List<Tx> getExecutableTransactions(long maxBlockSizeBytes, WorldState worldState) {
		log.debug("[MINING-DEBUG] getExecutableTransactions START, maxBlockSize={}", maxBlockSizeBytes);
		Iterator<MempoolEntry> candidates = mempoolService.getTxIterator();
		List<Tx> blockTxs = new ArrayList<>();
		Set<Hash> seenHashes = new HashSet<>();
		Map<Address, Long> senderNextNonce = new HashMap<>();
		// Buffer for "future" transactions encountered early (due to high fee)
		Map<Address, TreeMap<Long, Tx>> deferredTxs = new HashMap<>();

		long currentSize = 0;
		int skippedDuplicates = 0;
		int skippedSize = 0;
		int skippedOutOfOrder = 0;

		while (candidates.hasNext()) {
			MempoolEntry entry = candidates.next();
			Tx tx = entry.getTx();
			Address sender = tx.getSender();

			// 1. Check Nonce Sequence
			if (sender != null) {
				long expectedNonce = senderNextNonce.computeIfAbsent(sender,
						k -> worldState.getNonce(k).getNonce() + 1);

				if (tx.getNonce() > expectedNonce) {
					// Park future transaction (maybe parent hasn't been seen yet due to lower fee)
					deferredTxs.computeIfAbsent(sender, key -> new TreeMap<>()).put(tx.getNonce(), tx);
					log.trace("[MINING-DEBUG] Deferred future tx: hash={}, sender={}, nonce={}, expected={}",
							tx.getHash().toShortLogString(), sender.toChecksumAddress(), tx.getNonce(), expectedNonce);
					continue;
				} else if (tx.getNonce() < expectedNonce) {
					// Stale/Old
					skippedOutOfOrder++;
					continue;
				}
				// tx.getNonce() == expectedNonce -> PROCEED
			}

			if (!seenHashes.contains(tx.getHash())) {
				if (currentSize + tx.getSize() <= maxBlockSizeBytes) {
					seenHashes.add(tx.getHash());
					blockTxs.add(tx);
					currentSize += tx.getSize();

					log.debug("[MINING-DEBUG] Selected tx: hash={}, sender={}, nonce={}, fee={}, size={}",
							tx.getHash().toShortLogString(),
							tx.getSender() != null ? tx.getSender().toChecksumAddress() : "null",
							tx.getNonce(), tx.getFee().toBigInteger(), tx.getSize());

					// Update expected nonce AND check deferred buffer
					if (sender != null) {
						long nextNonce = tx.getNonce() + 1;
						senderNextNonce.put(sender, nextNonce);

						// Try to pull children from deferred buffer (CPFP)
						TreeMap<Long, Tx> pending = deferredTxs.get(sender);
						if (pending != null) {
							while (pending.containsKey(nextNonce)) {
								Tx childTx = pending.remove(nextNonce);

								if (!seenHashes.contains(childTx.getHash())) {
									if (currentSize + childTx.getSize() <= maxBlockSizeBytes) {
										seenHashes.add(childTx.getHash());
										blockTxs.add(childTx);
										currentSize += childTx.getSize();
										log.debug("[MINING-DEBUG] Selected DEFERRED tx: hash={}, nonce={}",
												childTx.getHash().toShortLogString(), childTx.getNonce());

										nextNonce++;
										senderNextNonce.put(sender, nextNonce);
									} else {
										skippedSize++;
										pending.put(nextNonce, childTx);
										break;
									}
								} else {
									log.warn("[MINING-DEBUG] Deferred duplicate tx found: hash={}",
											childTx.getHash().toShortLogString());
									break;
								}
							}
							if (pending.isEmpty()) {
								deferredTxs.remove(sender);
							}
						}
					}
				} else {
					skippedSize++;
					log.debug("[MINING-DEBUG] Skipped (size limit): hash={}, txSize={}, currentSize={}",
							tx.getHash().toShortLogString(), tx.getSize(), currentSize);
					continue;
				}
			} else {
				skippedDuplicates++;
			}
		}
		log.info(
				"[MINING-DEBUG] getExecutableTransactions END: selected={}, totalSize={}, skippedDuplicates={}, skippedSize={}, deferredRemaining={}",
				blockTxs.size(), currentSize, skippedDuplicates, skippedSize, deferredTxs.size());
		return blockTxs;
	}

	/**
	 * Calculates the dynamic block size based on mempool utilization.
	 * This prevents mining unnecessarily large blocks when there's low demand.
	 *
	 * @param blockHeight
	 *            The target block height (for fork-aware limits)
	 * @return The target block size in bytes
	 */
	private long calculateDynamicBlockSize(long blockHeight) {
		long maxBlockSize = Constants.getSettings().getMaxBlockSizeInBytes(blockHeight);
		long currentMempoolSize = mempoolService.getTransactionCount();
		long maxMempoolSize = mempoolProperties.getMaxSize();

		if (maxMempoolSize <= 0) {
			return maxBlockSize;
		}

		double utilizationRatio = (double) currentMempoolSize / maxMempoolSize;
		double utilizationPercent = utilizationRatio * 100;

		if (utilizationRatio >= 0.80) {
			// High load (80%+): Use full block size
			log.debug("Mempool high load ({}%), using full block size", String.format("%.1f", utilizationPercent));
			return maxBlockSize;
		} else if (utilizationRatio >= 0.30) {
			// Medium load (30-80%): Use 1/2 block size
			log.debug("Mempool medium load ({}%), using 1/2 block size", String.format("%.1f", utilizationPercent));
			return maxBlockSize / 2;
		} else {
			// Low load (<30%): Use 1/3 block size
			log.debug("Mempool low load ({}%), using 1/3 block size", String.format("%.1f", utilizationPercent));
			return maxBlockSize / 3;
		}
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

		public Address getIdentity() {
			return Address.ZERO;
		}
	}

	@Data
	@Builder
	public static class AssembledBlock {
		BlockHeaderTemplate blockTemplate;
		List<Tx> txs;
		List<Tx> selectedTxs;
		List<Tx> invalidTxs;
	}

}
