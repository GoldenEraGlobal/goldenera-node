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
package global.goldenera.node.core.blockchain.validation;

import static com.google.common.base.Preconditions.checkArgument;
import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.Constants;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkHasher;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.ProofOfWorkTarget;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.time.ProductionChainClock;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.core.processing.ValidatorMiningPolicyService;
import global.goldenera.node.core.monitoring.EquivocationDetectionService;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockValidator {

	ProofOfWorkProvider proofOfWorkProvider;
	DifficultyCalculator difficultyService;
	CheckpointRegistry checkpointService;
	TxValidator txValidator;
	ValidatorMiningPolicyService validatorMiningPolicyService;
	EquivocationDetectionService equivocationDetectionService;
	ChainClock chainClock;

	@Autowired
	public BlockValidator(ProofOfWorkProvider proofOfWorkProvider, DifficultyCalculator difficultyService,
			CheckpointRegistry checkpointService, TxValidator txValidator,
			ValidatorMiningPolicyService validatorMiningPolicyService,
			EquivocationDetectionService equivocationDetectionService, ChainClock chainClock) {
		this.proofOfWorkProvider = proofOfWorkProvider;
		this.difficultyService = difficultyService;
		this.checkpointService = checkpointService;
		this.txValidator = txValidator;
		this.validatorMiningPolicyService = validatorMiningPolicyService;
		this.equivocationDetectionService = equivocationDetectionService;
		this.chainClock = chainClock;
	}

	/** Test-friendly constructor preserving the former production behavior. */
	public BlockValidator(ProofOfWorkProvider proofOfWorkProvider, DifficultyCalculator difficultyService,
			CheckpointRegistry checkpointService, TxValidator txValidator,
			ValidatorMiningPolicyService validatorMiningPolicyService,
			EquivocationDetectionService equivocationDetectionService) {
		this(proofOfWorkProvider, difficultyService, checkpointService, txValidator, validatorMiningPolicyService,
				equivocationDetectionService, new ProductionChainClock());
	}

	/** Test-friendly constructor for consensus tests that do not exercise monitoring. */
	public BlockValidator(ProofOfWorkProvider proofOfWorkProvider, DifficultyCalculator difficultyService,
			CheckpointRegistry checkpointService, TxValidator txValidator,
			ValidatorMiningPolicyService validatorMiningPolicyService) {
		this(proofOfWorkProvider, difficultyService, checkpointService, txValidator, validatorMiningPolicyService, null,
				new ProductionChainClock());
	}

	// =================================================================================
	// 1. HEADER VALIDATION (Lightweight)
	// =================================================================================

	/**
	 * Heavy PoW check (Header only).
	 */
	public StatelessValidatedHeader validateHeader(@NonNull BlockHeader header) {
		return validateHeader(header, Collections.emptyMap());
	}

	public StatelessValidatedHeader validateHeader(@NonNull BlockHeader header, @NonNull Map<Long, Hash> batchContext) {
		try {
			if (header.getHeight() == 0) {
				throw new GEValidationException("Consensus violation: Genesis block cannot be validated.");
			}

			// 1. Structural fields required by hashing, signature and contextual checks.
			checkArgument(header.getPreviousHash() != null, "Previous block hash cannot be null");
			checkArgument(header.getTxRootHash() != null, "Transaction root hash cannot be null");
			checkArgument(header.getStateRootHash() != null, "State root hash cannot be null");
			checkArgument(header.getTimestamp() != null, "Block timestamp cannot be null");
			checkArgument(header.getDifficulty() != null && header.getDifficulty().signum() > 0,
					"Block difficulty must be positive");
			checkArgument(header.getCoinbase() != null && !header.getCoinbase().equals(Address.ZERO),
					"Consensus violation: Miner coinbase cannot be null or the zero address.");
			checkArgument(header.getSignature() != null && !header.getSignature().equals(Signature.ZERO),
					"Consensus violation: Miner signature cannot be null or the zero signature.");
			checkArgument(header.getIdentity() != null && !header.getIdentity().equals(Address.ZERO),
					"Consensus violation: Miner identity cannot be null or the zero address.");

			// 2. Header Size sanity check
			checkArgument(header.getSize() <= Constants.getSettings().getMaxHeaderSizeInBytes(header.getHeight()),
					"Header size exceeded: %s", header.getSize());

			// 3. Checkpoint (Fast fail)
			if (!checkpointService.verifyCheckpoint(header.getHeight(), header.getHash())) {
				throw new GEValidationException("Checkpoint mismatch for block " + header.getHeight());
			}
			if (Constants.isForkActive(ForkName.MINING_ECONOMICS, header.getHeight())) {
				checkArgument(header.getSignature().validate(BlockHeaderUtil.hashForSigning(header), header.getIdentity()),
						"Miner signature does not authenticate the recovered identity");
			}
			// 4. Proof-of-work calculation
			validateProofOfWorkInternal(header, batchContext);
			return new StatelessValidatedHeader(header);
		} catch (BlockValidationException e) {
			throw e;
		} catch (Exception e) {
			log.warn("Stateless validation failed for header at height {}: {}", header.getHeight(), e.getMessage());
			throw new BlockValidationException(
					BlockValidationException.Category.STATELESS, "PoW Validation failed", e);
		}
	}

	/**
	 * Contextual Validation (Header vs Parent).
	 */
	public void validateHeaderContext(
			@NonNull BlockHeader child,
			@NonNull BlockHeader parent,
			@NonNull WorldState worldState) {

		try {
			NetworkParamsState params = worldState.getParams();
			// 1. Linkage
			checkArgument(child.getPreviousHash().equals(parent.getHash()),
					"Broken Linkage: PrevHash %s != ParentHash %s",
					child.getPreviousHash(), parent.getHash());

			// 2. Height
			checkArgument(child.getHeight() == parent.getHeight() + 1,
					"Invalid Height: %s (expected %s)",
					child.getHeight(), parent.getHeight() + 1);

			// 3-4. Timestamp policy (past ordering and future drift/window)
			long targetMiningTimeMs = params.getTargetMiningTimeMs();
			long allowedDrift = DifficultyUtil.calculateDynamicMaxFutureTime(targetMiningTimeMs);
			chainClock.validateBlockTimestamp(child, parent, allowedDrift);

			// 5. Difficulty
			BigInteger expectedDifficulty = difficultyService.calculateNextDifficulty(parent, params);
			checkArgument(child.getDifficulty().equals(expectedDifficulty),
					"Invalid Difficulty. Expected %s, got %s",
					expectedDifficulty, child.getDifficulty());

			// 6. Validator Check - miner must be a registered validator (skip if no
			// validators registered)
			if (params.getCurrentValidatorCount() > 0) {
				Address minerIdentity = child.getIdentity();
				checkArgument(worldState.getValidator(minerIdentity).exists(),
						"Consensus violation: Miner %s is not a registered validator",
						minerIdentity.toChecksumAddress());
			}
		} catch (BlockValidationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new BlockValidationException(BlockValidationException.Category.CONTEXTUAL,
					"Contextual Header Validation failed: " + e.getMessage(), e);
		}

		try {
			validatorMiningPolicyService.validateCandidate(worldState, child.getHeight(), child.getIdentity());
		} catch (RuntimeException e) {
			throw new BlockValidationException(BlockValidationException.Category.CONSENSUS_POLICY,
					"Consensus policy validation failed: " + e.getMessage(), e);
		}
		observeContextuallyValidatedHeader(child);
	}

	private void observeContextuallyValidatedHeader(BlockHeader header) {
		if (equivocationDetectionService == null) {
			return;
		}
		try {
			equivocationDetectionService.enqueueValidatedHeader(header, Instant.now());
		} catch (Exception e) {
			log.error("Equivocation monitoring failed for contextually valid header {}", header.getHash(), e);
		}
	}

	// =================================================================================
	// 2. FULL BLOCK VALIDATION (Heavy Data)
	// =================================================================================

	public StatelessValidatedBlock validateFullBlock(@NonNull Block block) {
		return validateFullBlock(block, Collections.emptyMap());
	}

	/**
	 * Validates a complete block with optional seed-block hashes from the same
	 * downloaded header batch. The batch context is only a PoW seed resolver; it
	 * never skips validation.
	 */
	public StatelessValidatedBlock validateFullBlock(@NonNull Block block, @NonNull Map<Long, Hash> batchContext) {
		try {
			Block snapshot = ImmutableBlockSnapshot.copyOf(block);
			// 1. Every full-block path reuses the complete header validation.
			validateHeader(snapshot.getHeader(), batchContext);
			validateBody(snapshot);
			return new StatelessValidatedBlock(snapshot);
		} catch (BlockValidationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw statelessBlockFailure(e);
		}
	}

	/**
	 * Validates a downloaded body after its header was validated as part of an
	 * explicit sync header batch. This method never validates or connects state.
	 */
	public StatelessValidatedBlock validateBlockBody(Block block, StatelessValidatedHeader validatedHeader) {
		try {
			checkArgument(block != null, "Block cannot be null");
			checkArgument(validatedHeader != null && validatedHeader.matches(block.getHeader()),
					"Validated header does not belong to the supplied block");
			Block snapshot = ImmutableBlockSnapshot.copyOf(block);
			validateBody(snapshot);
			return new StatelessValidatedBlock(snapshot);
		} catch (BlockValidationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw statelessBlockFailure(e);
		}
	}

	private void validateBody(Block block) {
		try {
			// 2. Coinbase address must be set
			checkArgument(block.getHeader().getCoinbase() != null,
					"Block coinbase address cannot be null");

			List<Tx> txs = block.getTxs();
			long blockHeight = block.getHeight();

			// 3. Full Block Size Limit (height-aware for fork overrides)
			checkArgument(txs.size() <= Constants.getSettings().getMaxTxCountPerBlock(blockHeight),
					"Transaction count exceeded limit: %s (max: %s)",
					txs.size(), Constants.getSettings().getMaxTxCountPerBlock(blockHeight));

			checkArgument(block.getSize() <= Constants.getSettings().getMaxBlockSizeInBytes(blockHeight),
					"Block size exceeded limit: %s", block.getSize());

			// 4. MERKLE ROOT CHECK
			Hash calculatedRoot = TxRootUtil.txRootHash(txs);
			if (!calculatedRoot.equals(block.getHeader().getTxRootHash())) {
				throw new GEValidationException(String.format(
						"Merkle Root Mismatch! Header: %s, Body Calculated: %s",
						block.getHeader().getTxRootHash(), calculatedRoot));
			}

			// 5. Transaction Validation (Signatures, Formats)
			txs.parallelStream().forEach(txValidator::validateStateless);
		} catch (BlockValidationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw statelessBlockFailure(e);
		}
	}

	private BlockValidationException statelessBlockFailure(RuntimeException cause) {
		return new BlockValidationException(BlockValidationException.Category.STATELESS,
				"Full Block Validation failed: " + cause.getMessage(), cause);
	}

	// --- Private Helpers ---

	private void validateProofOfWorkInternal(BlockHeader header, Map<Long, Hash> batchContext) {
		BigInteger difficulty = header.getDifficulty();
		BigInteger target = DifficultyUtil.calculateTargetFromDifficulty(difficulty);
		ProofOfWorkTarget proofOfWorkTarget = ProofOfWorkTarget.of(target);

		byte[] powInput = BlockHeaderUtil.powInput(header);
		byte[] randomXHashBytes;

		// Batch context supplies seed blocks that have not reached local storage yet.
		// The production provider falls back to its canonical chain query when absent.

		try (ProofOfWorkHasher hasher = proofOfWorkProvider.openVerificationHasher(header.getHeight(), height -> {
			if (batchContext.containsKey(height)) {
				return Optional.of(batchContext.get(height).toArray());
			}
			return Optional.empty();
		})) {
			randomXHashBytes = hasher.hash(powInput);
		}

		if (!proofOfWorkTarget.accepts(randomXHashBytes)) {
			BigInteger resultValue = new BigInteger(1, randomXHashBytes);
			throw new GEValidationException(String.format(
					"PoW Failed! Hash %s > Target %s",
					resultValue.toString(16), proofOfWorkTarget.value().toString(16)));
		}
	}
}
