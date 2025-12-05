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

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.Constants;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.difficulty.DifficultyCalculator;
import global.goldenera.node.core.blockchain.utils.DifficultyUtil;
import global.goldenera.node.shared.consensus.state.NetworkParamsState;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.randomx.RandomXVM;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockValidator {

	RandomXManager randomXService;
	DifficultyCalculator difficultyService;
	CheckpointRegistry checkpointService;
	TxValidator txValidator;

	// =================================================================================
	// 1. HEADER VALIDATION (Lightweight)
	// =================================================================================

	/**
	 * Heavy PoW check (Header only).
	 */
	public void validateHeader(@NonNull BlockHeader header) {
		validateHeader(header, Collections.emptyMap());
	}

	public void validateHeader(@NonNull BlockHeader header, @NonNull Map<Long, Hash> batchContext) {
		try {
			// 1. Header Size sanity check
			checkArgument(header.getSize() <= Constants.MAX_HEADER_SIZE,
					"Header size exceeded: %s", header.getSize());

			// 2. Checkpoint (Fast fail)
			if (!checkpointService.verifyCheckpoint(header.getHeight(), header.getHash())) {
				throw new GEValidationException("Checkpoint mismatch for block " + header.getHeight());
			}

			// 3. RandomX PoW Calculation
			validateRandomXPoWInternal(header, batchContext);
		} catch (Exception e) {
			log.warn("PoW Validation failed for header {}: {}", header.getHash(), e.getMessage());
			throw new GEValidationException("PoW Validation failed", e);
		}
	}

	/**
	 * Contextual Validation (Header vs Parent).
	 */
	public void validateHeaderContext(
			@NonNull BlockHeader child,
			@NonNull BlockHeader parent,
			@NonNull NetworkParamsState params) {

		try {
			// 1. Linkage
			checkArgument(child.getPreviousHash().equals(parent.getHash()),
					"Broken Linkage: PrevHash %s != ParentHash %s",
					child.getPreviousHash(), parent.getHash());

			// 2. Height
			checkArgument(child.getHeight() == parent.getHeight() + 1,
					"Invalid Height: %s (expected %s)",
					child.getHeight(), parent.getHeight() + 1);

			// 3. Timestamp (Past)
			checkArgument(child.getTimestamp().isAfter(parent.getTimestamp()),
					"Timestamp invalid: Child %s <= Parent %s",
					child.getTimestamp(), parent.getTimestamp());

			// 4. Timestamp (Future Drift)
			long targetMiningTimeMs = params.getTargetMiningTimeMs();
			long allowedDrift = DifficultyUtil.calculateDynamicMaxFutureTime(targetMiningTimeMs);
			Instant maxTime = Instant.now().plusMillis(allowedDrift);

			checkArgument(!child.getTimestamp().isAfter(maxTime),
					"Timestamp too far in future: %s (Max: %s)",
					child.getTimestamp(), maxTime);

			// 5. Difficulty
			BigInteger expectedDifficulty = difficultyService.calculateNextDifficulty(parent, params);
			checkArgument(child.getDifficulty().equals(expectedDifficulty),
					"Invalid Difficulty. Expected %s, got %s",
					expectedDifficulty, child.getDifficulty());

		} catch (IllegalArgumentException e) {
			throw new GEValidationException("Contextual Header Validation failed: " + e.getMessage(), e);
		}
	}

	// =================================================================================
	// 2. FULL BLOCK VALIDATION (Heavy Data)
	// =================================================================================

	public void validateFullBlock(@NonNull Block block) {
		validateFullBlock(block, true);
	}

	public void validateFullBlock(@NonNull Block block, boolean validatePow) {
		try {
			// 1. Re-use header validation
			if (validatePow) {
				validateHeader(block.getHeader());
			}

			// 2. Full Block Size Limit
			checkArgument(block.getSize() <= Constants.MAX_BLOCK_SIZE_IN_BYTES,
					"Block size exceeded limit: %s", block.getSize());

			// 3. Transaction Existence
			List<Tx> txs = block.getTxs();
			// 4. MERKLE ROOT CHECK
			Hash calculatedRoot = TxRootUtil.txRootHash(txs);
			if (!calculatedRoot.equals(block.getHeader().getTxRootHash())) {
				throw new GEValidationException(String.format(
						"Merkle Root Mismatch! Header: %s, Body Calculated: %s",
						block.getHeader().getTxRootHash(), calculatedRoot));
			}

			// 5. Transaction Validation (Signatures, Formats)
			txs.parallelStream().forEach(txValidator::validateStateless);
		} catch (IllegalArgumentException e) {
			throw new GEValidationException("Full Block Validation failed: " + e.getMessage(), e);
		}
	}

	// --- Private Helpers ---

	private void validateRandomXPoWInternal(BlockHeader header, Map<Long, Hash> batchContext) {
		BigInteger difficulty = header.getDifficulty();
		BigInteger target = DifficultyUtil.calculateTargetFromDifficulty(difficulty);

		byte[] powInput = BlockHeaderUtil.powInput(header);
		byte[] randomXHashBytes;

		// Use provider that checks batchContext first, then DB (via ChainQuery in
		// RandomXManager? No, I need to provide fallback here)
		// RandomXManager.getLightVMForVerification(height, provider) uses the provider.
		// So I must provide a provider that does both.

		try (RandomXVM vm = randomXService.getLightVMForVerification(header.getHeight(), height -> {
			if (batchContext.containsKey(height)) {
				return Optional.of(batchContext.get(height).toArray());
			}
			// Fallback to DB (Handled by RandomXManager)
			return Optional.empty();
		})) {
			randomXHashBytes = vm.calculateHash(powInput);
		}

		BigInteger resultValue = new BigInteger(1, randomXHashBytes);

		if (resultValue.compareTo(target) > 0) {
			throw new GEValidationException(String.format(
					"PoW Failed! Hash %s > Target %s",
					resultValue.toString(16), target.toString(16)));
		}
	}
}