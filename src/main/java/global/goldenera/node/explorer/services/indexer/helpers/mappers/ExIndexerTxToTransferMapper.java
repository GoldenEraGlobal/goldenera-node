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

import static lombok.AccessLevel.PRIVATE;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.StateDiff;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.Constants;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent;
import global.goldenera.node.explorer.entities.ExNetworkParams;
import global.goldenera.node.explorer.entities.ExTransfer;
import global.goldenera.node.explorer.enums.TransferType;
import global.goldenera.node.explorer.repositories.ExNetworkParamsRepository;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE)
public class ExIndexerTxToTransferMapper {

	private static final Address NULL_ADDRESS = Address.ZERO;
	private static final Address NATIVE_TOKEN_ADDRESS = Address.NATIVE_TOKEN;

	final ExNetworkParamsRepository networkParamsRepository;

	Address cachedRewardPoolAddress;

	public List<ExTransfer> map(BlockConnectedEvent event) {
		Address currentRewardPool = resolveRewardPoolAddress(event);
		Block block = event.getBlock();
		List<ExTransfer> transfers = new ArrayList<>();

		var baseBuilder = ExTransfer.builder()
				.blockHash(block.getHash())
				.blockHeight(block.getHeight())
				.timestamp(block.getHeader().getTimestamp());

		// 0. GENESIS BLOCK: Special handling for initial token mints
		if (block.getHeight() == 0) {
			processGenesisMints(baseBuilder, transfers);
			return transfers; // Genesis has no other transfers
		}

		Wei reward = event.getMinerActualRewardPaid();
		Wei fees = event.getMinerTotalFees();
		Wei cleanBlockReward = reward.subtract(fees);

		if (cleanBlockReward.compareTo(Wei.ZERO) > 0) {
			transfers.add(baseBuilder.build().toBuilder()
					.txHash(null)
					.type(TransferType.BLOCK_REWARD)
					.from(currentRewardPool)
					.to(block.getHeader().getCoinbase())
					.amount(cleanBlockReward)
					.tokenAddress(NATIVE_TOKEN_ADDRESS)
					.build());
		}

		if (!fees.isZero()) {
			transfers.add(baseBuilder.build().toBuilder()
					.type(TransferType.BLOCK_FEES)
					.from(null)
					.to(block.getHeader().getCoinbase())
					.amount(fees)
					.tokenAddress(NATIVE_TOKEN_ADDRESS)
					.build());
		}

		// 2. TRANSACTIONS (Native & Fees)
		int txIndex = 0;
		for (Tx tx : block.getTxs()) {
			var txBuilder = baseBuilder.build().toBuilder().txHash(tx.getHash());
			processTransaction(tx, block, txBuilder, transfers, txIndex);
			txIndex++;
		}

		// 3. BIP EXECUTION (Mints & Burns)
		if (!event.getBipDiffs().isEmpty()) {
			processBipExecutions(event.getBipDiffs(), baseBuilder, transfers, event);
		}

		return transfers;
	}

	private void processTransaction(Tx tx, Block block, ExTransfer.ExTransferBuilder txBuilder,
			List<ExTransfer> transfers, int txIndex) {

		// A. VALUE TRANSFER
		if (tx.getType() == TxType.TRANSFER) {
			Address tokenAddr = tx.getTokenAddress() != null ? tx.getTokenAddress() : NATIVE_TOKEN_ADDRESS;
			TransferType type = TransferType.resolveFromTx(tx);

			transfers.add(txBuilder
					.type(type)
					.from(tx.getSender())
					.to(tx.getRecipient())
					.amount(tx.getAmount())
					.tokenAddress(tokenAddr)
					.fee(tx.getFee())
					.nonce(tx.getNonce())
					.message(tx.getMessage())
					.txIndex(txIndex)
					.timestamp(tx.getTimestamp())
					.build());
		}
	}

	private void processBipExecutions(Map<Hash, StateDiff<BipState>> bipDiffs,
			ExTransfer.ExTransferBuilder baseBuilder,
			List<ExTransfer> transfers, BlockConnectedEvent event) {

		bipDiffs.forEach((hash, diff) -> {
			BipState oldState = diff.getOldValue();
			BipState newState = diff.getNewValue();
			if (!oldState.isActionExecuted() && newState.isActionExecuted()) {
				if (newState.getMetadata() != null && newState.getMetadata().getTxPayload() != null) {
					TxPayload payload = newState.getMetadata().getTxPayload();
					var transferBuilder = baseBuilder.build().toBuilder().txHash(hash);
					createTransferFromPayload(payload, transferBuilder, transfers, event);
				}
			}
		});
	}

	private void createTransferFromPayload(TxPayload payload, ExTransfer.ExTransferBuilder builder,
			List<ExTransfer> transfers, BlockConnectedEvent event) {
		switch (payload.getPayloadType()) {
			case BIP_TOKEN_MINT -> {
				var mint = (TxBipTokenMintPayload) payload;
				transfers.add(builder
						.type(TransferType.MINT)
						.from(NULL_ADDRESS)
						.to(mint.getRecipient())
						.amount(mint.getAmount())
						.tokenAddress(mint.getTokenAddress())
						.build());
			}
			case BIP_TOKEN_BURN -> {
				var burn = (TxBipTokenBurnPayload) payload;
				Hash bipHash = Hash.wrap(builder.build().getTxHash().toArray());
				Wei realAmount = event.getActualBurnAmounts().getOrDefault(bipHash, burn.getAmount());
				transfers.add(builder
						.type(TransferType.BURN)
						.from(burn.getSender())
						.to(NULL_ADDRESS)
						.amount(realAmount)
						.tokenAddress(burn.getTokenAddress())
						.build());
			}
		}
	}

	/**
	 * Creates MINT transfers for the initial genesis token distribution.
	 * At genesis, tokens are minted to:
	 * 1. First authority address (initial operating funds)
	 * 2. Block reward pool address (block rewards for first year)
	 */
	private void processGenesisMints(ExTransfer.ExTransferBuilder baseBuilder, List<ExTransfer> transfers) {
		NetworkSettings settings = Constants.getSettings();

		Address firstAuthority = settings.genesisAuthorityAddresses().get(0);
		Address blockRewardPool = settings.genesisNetworkBlockRewardPoolAddress();

		Wei authorityMint = settings.genesisNetworkInitialMintForAuthority();
		Wei blockRewardMint = settings.genesisNetworkInitialMintForBlockReward();

		log.info("Processing genesis mints: Authority={} ({}), BlockRewardPool={} ({})",
				firstAuthority, authorityMint, blockRewardPool, blockRewardMint);

		// Mint to first authority
		if (authorityMint.compareTo(Wei.ZERO) > 0) {
			transfers.add(baseBuilder.build().toBuilder()
					.txHash(null)
					.type(TransferType.MINT)
					.from(NULL_ADDRESS)
					.to(firstAuthority)
					.amount(authorityMint)
					.tokenAddress(NATIVE_TOKEN_ADDRESS)
					.build());
		}

		// Mint to block reward pool
		if (blockRewardMint.compareTo(Wei.ZERO) > 0) {
			transfers.add(baseBuilder.build().toBuilder()
					.txHash(null)
					.type(TransferType.MINT)
					.from(NULL_ADDRESS)
					.to(blockRewardPool)
					.amount(blockRewardMint)
					.tokenAddress(NATIVE_TOKEN_ADDRESS)
					.build());
		}
	}

	private Address resolveRewardPoolAddress(BlockConnectedEvent event) {
		StateDiff<NetworkParamsState> diff = event.getNetworkParamsDiff();
		if (diff != null) {
			Address newAddr = diff.getNewValue().getBlockRewardPoolAddress();
			cachedRewardPoolAddress = (newAddr != null) ? newAddr : NULL_ADDRESS;
			return cachedRewardPoolAddress;
		}

		if (cachedRewardPoolAddress != null)
			return cachedRewardPoolAddress;

		return networkParamsRepository.findById(1)
				.map(ExNetworkParams::getBlockRewardPoolAddress)
				.map(addr -> {
					cachedRewardPoolAddress = addr;
					return addr;
				})
				.orElse(NULL_ADDRESS);
	}
}