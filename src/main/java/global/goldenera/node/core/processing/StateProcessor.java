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
package global.goldenera.node.core.processing;

import static com.google.common.base.Preconditions.checkArgument;
import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AccountNonceStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.exceptions.GETxValidationFailedException;
import global.goldenera.node.core.processing.handlers.TxHandler;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.exceptions.GEValidationException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Core logic for applying a block of transactions to the World State.
 * Ensures all consensus rules, including fee validation and state transitions,
 * are followed.
 * Implements Atomic Transaction Execution using Snapshot/Revert logic.
 */
@Service
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
@NamedInterface("state-processor")
public class StateProcessor {

	Map<TxType, TxHandler> handlers = new EnumMap<>(TxType.class);

	public StateProcessor(List<TxHandler> handlerList) {
		for (TxHandler h : handlerList) {
			handlers.put(h.getSupportedType(), h);
		}
	}

	/**
	 * STRICT MODE
	 */
	public ExecutionResult executeTransactions(WorldState worldState, SimpleBlock block, List<Tx> txs,
			NetworkParamsState params) {
		return processTransactionsInternal(worldState, block, txs, params, true);
	}

	/**
	 * MINING MODE
	 */
	public ExecutionResult executeMiningBatch(WorldState worldState, SimpleBlock block, List<Tx> txs,
			NetworkParamsState params) {
		return processTransactionsInternal(worldState, block, txs, params, false);
	}

	/**
	 * INTERNAL SHARED LOGIC
	 */
	private ExecutionResult processTransactionsInternal(
			WorldState worldState,
			SimpleBlock block,
			List<Tx> txs,
			NetworkParamsState params,
			boolean failFast) {
		Wei totalFeesCollected = Wei.ZERO;
		Wei totalSupplyIncrease = Wei.ZERO;

		List<Tx> validTxs = new ArrayList<>(txs.size());
		List<Tx> invalidTxs = new ArrayList<>();
		TxExecutionContext context = new TxExecutionContext();
		Map<Hash, Wei> actualBurnAmounts = new HashMap<>();

		for (Tx tx : txs) {
			Object snapshot = worldState.createSnapshot();
			try {
				context.reset(worldState, tx, block, params, actualBurnAmounts);
				validateAndDeductFee(worldState, tx, block, params);
				TxHandler handler = handlers.get(tx.getType());
				if (handler == null) {
					throw new GEValidationException("Unsupported TxType: " + tx.getType());
				}
				handler.execute(context);
				validTxs.add(tx);
				totalFeesCollected = totalFeesCollected.addExact(tx.getFee());
				if (!isUserPaidFee(tx.getType())) {
					totalSupplyIncrease = totalSupplyIncrease.addExact(tx.getFee());
				}
			} catch (Exception e) {
				worldState.revertToSnapshot(snapshot);
				if (failFast) {
					log.error("Block execution failed at tx {}: {}", tx.getHash(), e.getMessage());
					throw new GETxValidationFailedException(tx, e.getMessage(), e);
				} else {
					log.debug("Mining: Skipping invalid tx {}: {}", tx.getHash().toShortLogString(), e.getMessage());
					invalidTxs.add(tx);
				}
			}
		}
		Wei rewardFromPool = processRewardDistribution(worldState, block, params, totalFeesCollected,
				totalSupplyIncrease);
		Wei minerActualRewardPaid = rewardFromPool.addExact(totalFeesCollected);
		return ExecutionResult.builder()
				.validTxs(validTxs)
				.invalidTxs(invalidTxs)
				.totalFeesCollected(totalFeesCollected)
				.totalSupplyIncrease(totalSupplyIncrease)
				.minerTotalFees(totalFeesCollected)
				.minerActualRewardPaid(minerActualRewardPaid)
				.actualBurnAmounts(actualBurnAmounts)
				.build();
	}

	/**
	 * Handles the distribution of the Block Reward.
	 * VARIANT 1: If Pool Address is NULL, the reward is minted (inflation).
	 * VARIANT 2: If Pool Address is SET, the reward is taken from the pool balance.
	 * If the pool is empty/low, the miner gets only what is available.
	 *
	 * @return The amount of Native Token given to the miner as Block Reward
	 *         (excluding fees).
	 */
	private Wei processRewardDistribution(WorldState state, SimpleBlock block, NetworkParamsState params, Wei totalFees,
			Wei feeInflation) {
		if (block.getHeight() == 0) {
			return Wei.ZERO;
		}
		Wei targetBlockReward = params.getBlockReward();
		Wei actualBlockRewardPayload = Wei.ZERO; // The actual block reward paid to miner
		Wei amountToMint = feeInflation; // Start with system fees (inflation)

		Address poolAddress = params.getBlockRewardPoolAddress();

		if (poolAddress != null && poolAddress.equals(block.getCoinbase())) {
			throw new GEValidationException(String.format(
					"Consensus violation: Miner coinbase (%s) cannot be the same as Reward Pool address.",
					block.getCoinbase().toHexString()));
		}

		if (poolAddress.equals(Address.ZERO)) {
			// VARIANT 1: Inflationary Reward (From thin air)
			// If no pool is defined, the block reward is minted alongside fee inflation.
			actualBlockRewardPayload = targetBlockReward;
			amountToMint = amountToMint.addExact(targetBlockReward);
		} else {
			// VARIANT 2: Pool-based Reward (Transfer from Pool)
			if (!targetBlockReward.isZero()) {
				AccountBalanceStateImpl poolBal = (AccountBalanceStateImpl) state.getBalance(poolAddress,
						Address.NATIVE_TOKEN);
				Wei poolBalance = poolBal.getBalance();

				// If pool has enough, pay target. If not, pay whatever is left.
				if (poolBalance.compareTo(targetBlockReward) >= 0) {
					actualBlockRewardPayload = targetBlockReward;
				} else {
					actualBlockRewardPayload = poolBalance;
				}

				// Debit the pool
				if (!actualBlockRewardPayload.isZero()) {
					state.setBalance(poolAddress, Address.NATIVE_TOKEN,
							poolBal.debit(actualBlockRewardPayload, block.getHeight(), block.getTimestamp()));
				}
			}
		}

		// Credit the Miner (Actual Block Reward + Transaction Fees)
		Wei totalMinerCredit = actualBlockRewardPayload.addExact(totalFees);
		if (!totalMinerCredit.isZero()) {
			AccountBalanceStateImpl minerBal = (AccountBalanceStateImpl) state.getBalance(block.getCoinbase(),
					Address.NATIVE_TOKEN);

			state.setBalance(block.getCoinbase(), Address.NATIVE_TOKEN,
					minerBal.credit(totalMinerCredit, block.getHeight(), block.getTimestamp()));
		}

		// Apply Minting to World State (Inflationary Block Reward + System Fees)
		if (!amountToMint.isZero()) {
			TokenStateImpl nat = (TokenStateImpl) state.getToken(Address.NATIVE_TOKEN);
			state.setToken(Address.NATIVE_TOKEN,
					nat.mint(amountToMint, Hash.ZERO, block.getHeight(), block.getTimestamp()));
		}

		return actualBlockRewardPayload;
	}

	private void validateAndDeductFee(WorldState state, Tx tx, SimpleBlock block, NetworkParamsState params) {
		Address sender = tx.getSender();
		if (sender == null)
			return;

		AccountNonceStateImpl nonceState = (AccountNonceStateImpl) state.getNonce(sender);

		checkArgument(tx.getNonce() == nonceState.getNonce() + 1,
				"Invalid nonce: expected %s, got %s", nonceState.getNonce() + 1, tx.getNonce());

		state.setNonce(sender, nonceState.increaseNonce(block.getHeight(), block.getTimestamp()));

		// Fee validation applies to ALL transactions
		long txSize = tx.getSize();
		Wei minBaseFee = params.getMinTxBaseFee();
		Wei minByteFee = params.getMinTxByteFee();
		Wei requiredFee = minBaseFee.add(minByteFee.multiply(txSize));

		checkArgument(tx.getFee().compareTo(requiredFee) >= 0,
				"Transaction fee is too low. Required: %s, Provided: %s (Size: %s B)",
				requiredFee, tx.getFee(), txSize);

		// Fee deduction only for user-paid transactions (TRANSFER)
		if (isUserPaidFee(tx.getType())) {
			AccountBalanceStateImpl bal = (AccountBalanceStateImpl) state.getBalance(sender, Address.NATIVE_TOKEN);
			state.setBalance(sender, Address.NATIVE_TOKEN,
					bal.debit(tx.getFee(), block.getHeight(), block.getTimestamp()));
		}
	}

	private boolean isUserPaidFee(TxType type) {
		switch (type) {
			case TRANSFER:
				return true;
			default:
				return false;
		}
	}

	@Getter
	@FieldDefaults(level = PRIVATE, makeFinal = true)
	@AllArgsConstructor
	@EqualsAndHashCode(of = "height", callSuper = false)
	@Builder
	@NamedInterface("state-processor-simple-block")
	public static class SimpleBlock {
		long height;
		Instant timestamp;
		Address coinbase;

		public SimpleBlock(BlockHeader header) {
			this.height = header.getHeight();
			this.timestamp = header.getTimestamp();
			this.coinbase = header.getCoinbase();
		}

		public SimpleBlock(Block block) {
			this.height = block.getHeader().getHeight();
			this.timestamp = block.getHeader().getTimestamp();
			this.coinbase = block.getHeader().getCoinbase();
		}
	}

	@Data
	@AllArgsConstructor
	@Builder
	@NamedInterface("state-processor-execution-result")
	public static class ExecutionResult {
		List<Tx> validTxs;
		List<Tx> invalidTxs;

		Wei totalFeesCollected;
		Wei totalSupplyIncrease;

		Wei minerTotalFees;
		Wei minerActualRewardPaid;

		Map<Hash, Wei> actualBurnAmounts;
	}
}