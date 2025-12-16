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
package global.goldenera.node.core.mempool;

import static lombok.AccessLevel.PRIVATE;

import java.time.Instant;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayload;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;
import global.goldenera.node.core.state.WorldState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Validates transactions against both the L4 Chain State (via Workspace)
 * and the L4+ Mempool State (via MempoolStorage governance sets).
 */
@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MempoolValidator {

	MeterRegistry registry;
	ChainHeadStateCache chainHeadStateService;
	ChainQuery chainQueryService;
	MempoolProperties mempoolProperties;
	MempoolStore mempoolStorage;
	TxValidator txValidator;

	/**
	 * Main validation entry point.
	 * Validates a transaction against the *current confirmed blockchain state*
	 * AND the *current mempool state* (for governance duplicates).
	 *
	 * @param tx
	 *            The transaction to validate.
	 * @param tipStateRoot
	 *            The stateRootHash of the current chain tip.
	 * @return A validation result object.
	 */
	public MempoolValidationResult validateAgainstChainAndMempool(@NonNull MempoolEntry entry,
			@NonNull MempoolTxAddEvent.AddReason reason, boolean skipValidation) {
		Timer.Sample sample = Timer.start(registry);
		try {
			Tx tx = entry.getTx();

			// 2. Anti-Spam: Check for minimum fee (applies to ALL tx types)
			Wei minFee = Wei.valueOf(mempoolProperties.getMinAcceptableFeeWei());
			if (tx.getFee().compareTo(minFee) < 0) {
				return MempoolValidationResult.invalid("Fee too low. Must be at least "
						+ minFee.toBigInteger().toString() + " Wei.");
			}

			Block chainTip = chainQueryService.getLatestStoredBlockOrThrow().getBlock();
			if (!skipValidation) {
				txValidator.validateStateless(tx);
			}

			// Inject chain tip data into the tx
			entry.setFirstSeenHeight(chainTip.getHeight());
			entry.setFirstSeenTime(Instant.now());

			// 3. Get current world state
			WorldState worldstate = chainHeadStateService.getHeadState();

			// 4. Route validation based on sender
			if (tx.getSender() != null) {
				// --- User Tx (TRANSFER, BIP_CREATE, BIP_VOTE, TOKEN_BURN) ---
				return validateUserTx(tx, worldstate);
			} else {
				// --- System Tx (TOKEN_MINT) ---
				return MempoolValidationResult.invalid("System tx not supported.");
			}

		} catch (Exception e) {
			log.debug("Mempool validation failed for tx {}: {}", entry.getHash().toHexString(), e.getMessage());
			return MempoolValidationResult.invalid("Validation failed: " + e.getMessage());
		} finally {
			sample.stop(registry.timer("blockchain.mempool.validation_time"));
		}
	}

	/**
	 * Validates transactions that HAVE a sender and nonce.
	 * (TRANSFER, BIP_CREATE, BIP_VOTE, TOKEN_BURN)
	 */
	private MempoolValidationResult validateUserTx(Tx tx, WorldState worldstate) {
		Address sender = tx.getSender();
		log.debug("validateUserTx: hash={}, type={}, sender={}",
				tx.getHash().toShortLogString(), tx.getType(), sender.toChecksumAddress());

		// 4a. Check Nonce (applies to ALL user txs)
		AccountNonceState confirmedNonceState = worldstate.getNonce(sender);
		long currentChainNonce = confirmedNonceState.getNonce();
		if (tx.getNonce() < currentChainNonce + 1) {
			return MempoolValidationResult.stale(currentChainNonce, "Nonce is too low.");
		}

		// 4b. Validate fee against network params (applies to ALL user txs)
		NetworkParamsState params = worldstate.getParams();
		long txSize = tx.getSize();
		Wei minBaseFee = params.getMinTxBaseFee();
		Wei minByteFee = params.getMinTxByteFee();
		Wei requiredFee = minBaseFee.add(minByteFee.multiply(txSize));

		if (tx.getFee().compareTo(requiredFee) < 0) {
			return MempoolValidationResult.invalid(
					String.format("Fee too low. Required: %s, Provided: %s (Size: %d B)",
							requiredFee.toBigInteger().toString(), tx.getFee().toBigInteger().toString(), txSize));
		}

		// 4c. TX-Type specific L4 logic
		switch (tx.getType()) {
			case TRANSFER:
				// This is the only type with a user-paid fee
				AccountBalanceState nativeBalance = worldstate.getBalance(sender, Address.NATIVE_TOKEN);
				Wei totalNativeCost = tx.getFee();

				if (tx.getTokenAddress().equals(Address.NATIVE_TOKEN)) {
					totalNativeCost = totalNativeCost.add(tx.getAmount());
				} else {
					// Check custom token balance
					TokenState tokenState = worldstate.getToken(tx.getTokenAddress());
					if (!tokenState.exists()) {
						return MempoolValidationResult.invalid("Token does not exist on-chain.");
					}
					AccountBalanceState tokenBalance = worldstate.getBalance(sender, tx.getTokenAddress());
					if (tokenBalance.getBalance().compareTo(tx.getAmount()) < 0) {
						return MempoolValidationResult.invalid("Insufficient token balance for transfer.");
					}
				}
				if (nativeBalance.getBalance().compareTo(totalNativeCost) < 0) {
					return MempoolValidationResult.invalid("Insufficient native funds for fee and/or amount.");
				}

				// Early validation: Check if user is trying to burn a non-burnable token
				if (tx.getRecipient().equals(Address.ZERO)) {
					TokenState tokenState = worldstate.getToken(tx.getTokenAddress());
					if (!tokenState.exists()) {
						return MempoolValidationResult.invalid("Cannot burn token that does not exist.");
					}
					if (!tokenState.isUserBurnable()) {
						return MempoolValidationResult.invalid("Token is not user-burnable.");
					}
				}
				break;

			case BIP_CREATE:
			case BIP_VOTE:
				// Inflationary fee. No balance check needed for fee.
				// But we must check if sender is an authority.
				if (!worldstate.getAuthority(sender).exists()) {
					return MempoolValidationResult.invalid("Sender is not an authority.");
				}
				// Check for L4+ governance duplicates
				MempoolValidationResult governanceResult = validateGovernanceTx(tx, worldstate);
				if (!governanceResult.isValid()) {
					return governanceResult;
				}
				break;
			default:
				return MempoolValidationResult.invalid("Unsupported user transaction type: " + tx.getType());
		}

		// All checks passed for this user tx
		return MempoolValidationResult.valid(currentChainNonce);
	}

	/**
	 * Helper to check L4+ (Mempool) state for governance duplicates.
	 * (BIP_CREATE, BIP_VOTE)
	 */
	private MempoolValidationResult validateGovernanceTx(Tx tx, WorldState worldstate) {
		if (tx.getType() == TxType.BIP_CREATE) {
			TxPayload payload = tx.getPayload();

			if (payload instanceof TxBipAuthorityAddPayload) {
				Address addr = ((TxBipAuthorityAddPayload) payload).getAddress();
				// Check chain
				if (worldstate.getAuthority(addr).exists()) {
					return MempoolValidationResult.invalid("Authority already exists on-chain.");
				}
				// Check mempool
				if (mempoolStorage.isAuthorityAddPending(addr)) {
					return MempoolValidationResult.invalid("AuthorityAdd is already pending in mempool.");
				}
			} else if (payload instanceof TxBipAuthorityRemovePayload) {
				Address addr = ((TxBipAuthorityRemovePayload) payload).getAddress();
				// Check chain
				if (!worldstate.getAuthority(addr).exists()) {
					return MempoolValidationResult.invalid("Authority does not exist on-chain.");
				}
				// Check mempool
				if (mempoolStorage.isAuthorityRemovePending(addr)) {
					return MempoolValidationResult.invalid("AuthorityRemove is already pending in mempool.");
				}
			} else if (payload instanceof TxBipValidatorAddPayload) {
				Address addr = ((TxBipValidatorAddPayload) payload).getAddress();
				// Check chain
				if (worldstate.getValidator(addr).exists()) {
					return MempoolValidationResult.invalid("Validator already exists on-chain.");
				}
				// Check mempool
				if (mempoolStorage.isValidatorAddPending(addr)) {
					return MempoolValidationResult.invalid("ValidatorAdd is already pending in mempool.");
				}
			} else if (payload instanceof TxBipValidatorRemovePayload) {
				Address addr = ((TxBipValidatorRemovePayload) payload).getAddress();
				// Check chain
				if (!worldstate.getValidator(addr).exists()) {
					return MempoolValidationResult.invalid("Validator does not exist on-chain.");
				}
				// Check mempool
				if (mempoolStorage.isValidatorRemovePending(addr)) {
					return MempoolValidationResult.invalid("ValidatorRemove is already pending in mempool.");
				}
			} else if (payload instanceof TxBipAddressAliasAddPayload) {
				String alias = ((TxBipAddressAliasAddPayload) payload).getAlias();
				// Check chain
				if (worldstate.getAddressAlias(alias).exists()) {
					return MempoolValidationResult.invalid("Address alias already exists on-chain.");
				}
				// Check mempool
				if (mempoolStorage.isAddressAliasAddPending(alias)) {
					return MempoolValidationResult.invalid("AddressAliasAdd is already pending in mempool.");
				}
			} else if (payload instanceof TxBipAddressAliasRemovePayload) {
				String alias = ((TxBipAddressAliasRemovePayload) payload).getAlias();
				// Check chain
				if (!worldstate.getAddressAlias(alias).exists()) {
					return MempoolValidationResult.invalid("Address alias does not exist on-chain.");
				}
				// Check mempool
				if (mempoolStorage.isAddressAliasRemovePending(alias)) {
					return MempoolValidationResult.invalid("AddressAliasRemove is already pending in mempool.");
				}
			} else if (payload instanceof TxBipNetworkParamsSetPayload) {
				// Check mempool
				if (mempoolStorage.hasAuthorityPendingParamChange(tx.getSender())) {
					return MempoolValidationResult.invalid("ConsensusParamsSet is already pending in mempool.");
				}
			} else if (payload instanceof TxBipTokenMintPayload mintPayload) {
				// Early validation: Check if minting would exceed maxSupply
				TokenState tokenState = worldstate.getToken(mintPayload.getTokenAddress());
				if (!tokenState.exists()) {
					return MempoolValidationResult.invalid("Token does not exist on-chain.");
				}
				if (tokenState.getMaxSupply() != null) {
					var newTotalSupply = tokenState.getTotalSupply().toBigInteger()
							.add(mintPayload.getAmount().toBigInteger());
					if (newTotalSupply.compareTo(tokenState.getMaxSupply()) > 0) {
						return MempoolValidationResult.invalid(
								"Minting would exceed maxSupply. Current: "
										+ tokenState.getTotalSupply().toBigInteger().toString()
										+ ", Minting: " + mintPayload.getAmount().toBigInteger().toString()
										+ ", MaxSupply: "
										+ tokenState.getMaxSupply().toString());
					}
				}
			} else if (payload instanceof TxBipTokenBurnPayload burnPayload) {
				// Early validation: Check if token exists for burn
				TokenState tokenState = worldstate.getToken(burnPayload.getTokenAddress());
				if (!tokenState.exists()) {
					return MempoolValidationResult.invalid("Token does not exist on-chain.");
				}
			} else if (payload instanceof TxBipTokenUpdatePayload updatePayload) {
				// Early validation: Check if token exists for update
				TokenState tokenState = worldstate.getToken(updatePayload.getTokenAddress());
				if (!tokenState.exists()) {
					return MempoolValidationResult.invalid("Token does not exist on-chain.");
				}
			}
		}

		if (tx.getType() == TxType.BIP_VOTE) {
			Hash bipHash = tx.getReferenceHash();
			Address sender = tx.getSender();

			// Check L4 state (from chain)
			BipState bipState = worldstate.getBip(bipHash);
			if (!bipState.exists()) {
				return MempoolValidationResult.invalid("Cannot vote on non-existent BIP.");
			}
			if (bipState.getStatus() != BipStatus.PENDING) {
				return MempoolValidationResult.invalid("BIP is not in PENDING state.");
			}
			// Check if already voted on-chain
			if (bipState.getAllVoters().contains(sender)) {
				return MempoolValidationResult.invalid("Authority has already voted on-chain.");
			}
			// Check L4+ state (from mempool)
			if (mempoolStorage.isBipVotePending(bipHash, sender)) {
				return MempoolValidationResult
						.invalid("Authority already has a vote pending in the mempool for this BIP.");
			}
		}

		return MempoolValidationResult.valid(-1L);
	}

	// --- Helper Inner Class for Validation Result ---

	@Getter
	public static class MempoolValidationResult {

		private final ValidationStatus status;
		private final String errorMessage;
		private final long currentChainNonce; // The confirmed nonce from the chain

		private MempoolValidationResult(ValidationStatus status, String errorMessage, long currentChainNonce) {
			this.status = status;
			this.errorMessage = errorMessage;
			this.currentChainNonce = currentChainNonce;
		}

		public static MempoolValidationResult valid(long currentChainNonce) {
			return new MempoolValidationResult(ValidationStatus.VALID, null, currentChainNonce);
		}

		public static MempoolValidationResult invalid(String message) {
			return new MempoolValidationResult(ValidationStatus.INVALID, message, -1L);
		}

		public static MempoolValidationResult stale(long currentChainNonce, String message) {
			return new MempoolValidationResult(ValidationStatus.STALE, message, currentChainNonce);
		}

		public boolean isValid() {
			return status == ValidationStatus.VALID;
		}
	}

	public enum ValidationStatus {
		VALID, // Can be added to mempool (as executable or future)
		INVALID, // Reject permanently (bad signature, low funds, etc.)
		STALE // Reject permanently (nonce too low)
	}
}