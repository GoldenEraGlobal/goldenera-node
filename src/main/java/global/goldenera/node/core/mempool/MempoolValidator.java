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
import java.util.Objects;

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

	public MempoolValidationResult validateAgainstChainAndMempool(@NonNull MempoolEntry entry,
			@NonNull MempoolTxAddEvent.AddReason reason, boolean skipValidation) {
		return validateAgainstChainAndMempool(entry, skipValidation, ValidationMode.ADMISSION);
	}

	public MempoolValidationResult revalidateAgainstChain(@NonNull MempoolEntry entry) {
		return validateAgainstChainAndMempool(entry, false, ValidationMode.REVALIDATION);
	}

	/**
	 * Validates a transaction against the current confirmed chain state and, for
	 * admission, pending mempool state.
	 *
	 * @param entry
	 *            transaction and mempool metadata
	 * @param skipValidation
	 *            whether stateless validation was already performed by a trusted caller
	 * @param mode
	 *            admission or periodic chain-only revalidation
	 * @return validation result
	 */
	private MempoolValidationResult validateAgainstChainAndMempool(@NonNull MempoolEntry entry,
			boolean skipValidation, ValidationMode mode) {
		Timer.Sample sample = Timer.start(registry);
		try {
			Tx tx = entry.getTx();

			if (!skipValidation) {
				try {
					txValidator.validateStateless(tx);
				} catch (RuntimeException e) {
					return MempoolValidationResult.statelessInvalid("Stateless validation failed: " + e.getMessage());
				}
			}

			// Anti-spam minimum fee applies to every supported transaction.
			Wei minFee = Wei.valueOf(mempoolProperties.getMinAcceptableFeeWei());
			if (tx.getFee().compareTo(minFee) < 0) {
				return MempoolValidationResult.feeTooLow("Fee too low. Must be at least "
						+ minFee.toBigInteger().toString() + " Wei.");
			}

			Block chainTip = chainQueryService.getLatestStoredBlockOrThrow().getBlock();

			if (mode == ValidationMode.ADMISSION) {
				entry.setFirstSeenHeight(chainTip.getHeight());
				entry.setFirstSeenTime(Instant.now());
			}

			WorldState worldstate = chainHeadStateService.getHeadState();
			Instant earliestBlockTimestamp = Instant.now();
			Instant afterParent = chainTip.getHeader().getTimestamp().plusMillis(1);
			if (afterParent.isAfter(earliestBlockTimestamp)) {
				earliestBlockTimestamp = afterParent;
			}

			if (tx.getSender() != null) {
				return validateUserTx(tx, worldstate, mode, earliestBlockTimestamp);
			} else {
				return MempoolValidationResult.stateInvalid("System tx not supported.");
			}

		} catch (Exception e) {
			log.warn("Transient mempool validation failure for tx {}", entry.getHash().toHexString(), e);
			return MempoolValidationResult.transientError("Validation temporarily unavailable: " + e.getMessage());
		} finally {
			sample.stop(registry.timer("blockchain.mempool.validation_time"));
		}
	}

	/**
	 * Validates transactions that HAVE a sender and nonce.
	 * (TRANSFER, BIP_CREATE, BIP_VOTE, TOKEN_BURN)
	 */
	private MempoolValidationResult validateUserTx(Tx tx, WorldState worldstate, ValidationMode mode,
			Instant earliestBlockTimestamp) {
		Address sender = tx.getSender();
		log.debug("[VALIDATOR-DEBUG] validateUserTx: hash={}, type={}, sender={}, txNonce={}",
				tx.getHash().toShortLogString(), tx.getType(), sender.toChecksumAddress(), tx.getNonce());

		// 4a. Check Nonce (applies to ALL user txs)
		AccountNonceState confirmedNonceState = worldstate.getNonce(sender);
		long currentChainNonce = confirmedNonceState.getNonce();
		log.debug("[VALIDATOR-DEBUG] Nonce check: txNonce={}, chainNonce={}, expectedMin={}",
				tx.getNonce(), currentChainNonce, currentChainNonce + 1);
		if (tx.getNonce() < currentChainNonce + 1) {
			log.debug("[VALIDATOR-DEBUG] STALE: txNonce {} < expectedMin {}", tx.getNonce(), currentChainNonce + 1);
			return MempoolValidationResult.stale(currentChainNonce, "Nonce is too low.");
		}

		// 4b. Validate fee against network params (applies to ALL user txs)
		NetworkParamsState params = worldstate.getParams();
		long txSize = tx.getSize();
		Wei minBaseFee = params.getMinTxBaseFee();
		Wei minByteFee = params.getMinTxByteFee();
		Wei requiredFee = minBaseFee.add(minByteFee.multiply(txSize));

		if (tx.getFee().compareTo(requiredFee) < 0) {
			return MempoolValidationResult.feeTooLow(
					String.format("Fee too low. Required: %s, Provided: %s (Size: %d B)",
							requiredFee.toBigInteger().toString(), tx.getFee().toBigInteger().toString(), txSize));
		}

		// 4c. TX-Type specific L4 logic
		switch (tx.getType()) {
			case TRANSFER:
				AccountBalanceState nativeBalance = worldstate.getBalance(sender, Address.NATIVE_TOKEN);
				Wei totalNativeCost = pendingNativeCost(sender, tx);

				if (!tx.getTokenAddress().equals(Address.NATIVE_TOKEN)) {
					// Check custom token balance
					TokenState tokenState = worldstate.getToken(tx.getTokenAddress());
					if (!tokenState.exists()) {
						return MempoolValidationResult.invalid("Token does not exist on-chain.");
					}
					AccountBalanceState tokenBalance = worldstate.getBalance(sender, tx.getTokenAddress());
					if (tokenBalance.getBalance().compareTo(pendingTokenCost(sender, tx)) < 0) {
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
				// StateProcessor classifies governance fees as user-paid, so admission must
				// reserve them just like transfer fees.
				AccountBalanceState governanceBalance = worldstate.getBalance(sender, Address.NATIVE_TOKEN);
				if (governanceBalance.getBalance().compareTo(pendingNativeCost(sender, tx)) < 0) {
					return MempoolValidationResult.stateInvalid("Insufficient native funds for governance fee.");
				}
				if (!worldstate.getAuthority(sender).exists()) {
					return MempoolValidationResult.stateInvalid("Sender is not an authority.");
				}
				MempoolValidationResult governanceResult = validateGovernanceTx(tx, worldstate,
						mode == ValidationMode.ADMISSION, earliestBlockTimestamp);
				if (!governanceResult.isValid()) {
					return governanceResult;
				}
				break;
			default:
				return MempoolValidationResult.stateInvalid("Unsupported user transaction type: " + tx.getType());
		}

		// All checks passed for this user tx
		log.debug("[VALIDATOR-DEBUG] VALID: tx {} passed all checks, chainNonce={}",
				tx.getHash().toShortLogString(), currentChainNonce);
		return MempoolValidationResult.valid(currentChainNonce);
	}

	/**
	 * Helper to check L4+ (Mempool) state for governance duplicates.
	 * (BIP_CREATE, BIP_VOTE)
	 * 
	 * @param checkMempoolDuplicates
	 *            If true, checks if operation is already pending in mempool.
	 *            Should be FALSE during re-validation to avoid self-collision.
	 */
	private MempoolValidationResult validateGovernanceTx(Tx tx, WorldState worldstate, boolean checkMempoolDuplicates,
			Instant earliestBlockTimestamp) {
		if (tx.getType() == TxType.BIP_CREATE) {
			TxPayload payload = tx.getPayload();

			if (payload instanceof TxBipAuthorityAddPayload) {
				Address addr = ((TxBipAuthorityAddPayload) payload).getAddress();
				// Check chain
				if (worldstate.getAuthority(addr).exists()) {
					return MempoolValidationResult.invalid("Authority already exists on-chain.");
				}
				// Check mempool
				if (isConflictingAdmission(tx, checkMempoolDuplicates, mempoolStorage.isAuthorityAddPending(addr))) {
					return MempoolValidationResult.governanceDuplicate("AuthorityAdd is already pending in mempool.");
				}
			} else if (payload instanceof TxBipAuthorityRemovePayload) {
				Address addr = ((TxBipAuthorityRemovePayload) payload).getAddress();
				// Check chain
				if (!worldstate.getAuthority(addr).exists()) {
					return MempoolValidationResult.invalid("Authority does not exist on-chain.");
				}
				// Check mempool
				if (isConflictingAdmission(tx, checkMempoolDuplicates, mempoolStorage.isAuthorityRemovePending(addr))) {
					return MempoolValidationResult.governanceDuplicate("AuthorityRemove is already pending in mempool.");
				}
			} else if (payload instanceof TxBipValidatorAddPayload) {
				Address addr = ((TxBipValidatorAddPayload) payload).getAddress();
				// Check chain
				if (worldstate.getValidator(addr).exists()) {
					return MempoolValidationResult.invalid("Validator already exists on-chain.");
				}
				// Check mempool
				if (isConflictingAdmission(tx, checkMempoolDuplicates, mempoolStorage.isValidatorAddPending(addr))) {
					return MempoolValidationResult.governanceDuplicate("ValidatorAdd is already pending in mempool.");
				}
			} else if (payload instanceof TxBipValidatorRemovePayload) {
				Address addr = ((TxBipValidatorRemovePayload) payload).getAddress();
				// Check chain
				if (!worldstate.getValidator(addr).exists()) {
					return MempoolValidationResult.invalid("Validator does not exist on-chain.");
				}
				// Check mempool
				if (isConflictingAdmission(tx, checkMempoolDuplicates, mempoolStorage.isValidatorRemovePending(addr))) {
					return MempoolValidationResult.governanceDuplicate("ValidatorRemove is already pending in mempool.");
				}
			} else if (payload instanceof TxBipAddressAliasAddPayload) {
				String alias = ((TxBipAddressAliasAddPayload) payload).getAlias();
				// Check chain
				if (worldstate.getAddressAlias(alias).exists()) {
					return MempoolValidationResult.invalid("Address alias already exists on-chain.");
				}
				// Check mempool
				if (isConflictingAdmission(tx, checkMempoolDuplicates, mempoolStorage.isAddressAliasAddPending(alias))) {
					return MempoolValidationResult.governanceDuplicate("AddressAliasAdd is already pending in mempool.");
				}
			} else if (payload instanceof TxBipAddressAliasRemovePayload) {
				String alias = ((TxBipAddressAliasRemovePayload) payload).getAlias();
				// Check chain
				if (!worldstate.getAddressAlias(alias).exists()) {
					return MempoolValidationResult.invalid("Address alias does not exist on-chain.");
				}
				// Check mempool
				if (isConflictingAdmission(tx, checkMempoolDuplicates, mempoolStorage.isAddressAliasRemovePending(alias))) {
					return MempoolValidationResult.governanceDuplicate("AddressAliasRemove is already pending in mempool.");
				}
			} else if (payload instanceof TxBipNetworkParamsSetPayload) {
				// Check mempool
				if (isConflictingAdmission(tx, checkMempoolDuplicates,
						mempoolStorage.hasAuthorityPendingParamChange(tx.getSender()))) {
					return MempoolValidationResult.governanceDuplicate(
							"ConsensusParamsSet is already pending in mempool.");
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
			if (!earliestBlockTimestamp.isBefore(bipState.getExpirationTimestamp())) {
				return MempoolValidationResult.stateInvalid("BIP expired.");
			}
			// Check if already voted on-chain
			if (bipState.getAllVoters().contains(sender)) {
				return MempoolValidationResult.invalid("Authority has already voted on-chain.");
			}
			// Check L4+ state (from mempool)
			if (isConflictingAdmission(tx, checkMempoolDuplicates,
					mempoolStorage.isBipVotePending(bipHash, sender))) {
				return MempoolValidationResult
						.governanceDuplicate("Authority already has a vote pending in the mempool for this BIP.");
			}
		}

		return MempoolValidationResult.valid(-1L);
	}

	private Wei pendingNativeCost(Address sender, Tx candidate) {
		Wei total = nativeCost(candidate);
		for (MempoolEntry pending : mempoolStorage.getTxsBySender(sender)) {
			Tx tx = pending.getTx();
			if (!Objects.equals(tx.getNonce(), candidate.getNonce())) {
				total = total.add(nativeCost(tx));
			}
		}
		return total;
	}

	private Wei nativeCost(Tx tx) {
		Wei cost = tx.getFee();
		if (tx.getType() == TxType.TRANSFER && Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
			cost = cost.add(tx.getAmount());
		}
		return cost;
	}

	private Wei pendingTokenCost(Address sender, Tx candidate) {
		Wei total = candidate.getAmount();
		for (MempoolEntry pending : mempoolStorage.getTxsBySender(sender)) {
			Tx tx = pending.getTx();
			if (!Objects.equals(tx.getNonce(), candidate.getNonce()) && tx.getType() == TxType.TRANSFER
					&& Objects.equals(tx.getTokenAddress(), candidate.getTokenAddress())) {
				total = total.add(tx.getAmount());
			}
		}
		return total;
	}

	private boolean isConflictingAdmission(Tx candidate, boolean checkMempoolDuplicates, boolean pending) {
		if (!checkMempoolDuplicates || !pending) {
			return false;
		}
		Hash replacedHash = mempoolStorage.getTransactionBySenderAndNonce(candidate.getSender(), candidate.getNonce())
				.map(MempoolEntry::getHash)
				.orElse(null);
		return mempoolStorage.hasGovernanceConflict(new MempoolEntry(candidate), replacedHash);
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
			return stateInvalid(message);
		}

		public static MempoolValidationResult stateInvalid(String message) {
			return new MempoolValidationResult(ValidationStatus.STATE_INVALID, message, -1L);
		}

		public static MempoolValidationResult statelessInvalid(String message) {
			return new MempoolValidationResult(ValidationStatus.STATELESS_INVALID, message, -1L);
		}

		public static MempoolValidationResult feeTooLow(String message) {
			return new MempoolValidationResult(ValidationStatus.FEE_TOO_LOW, message, -1L);
		}

		public static MempoolValidationResult governanceDuplicate(String message) {
			return new MempoolValidationResult(ValidationStatus.GOVERNANCE_DUPLICATE, message, -1L);
		}

		public static MempoolValidationResult transientError(String message) {
			return new MempoolValidationResult(ValidationStatus.TRANSIENT_ERROR, message, -1L);
		}

		public static MempoolValidationResult stale(long currentChainNonce, String message) {
			return new MempoolValidationResult(ValidationStatus.STALE, message, currentChainNonce);
		}

		public boolean isValid() {
			return status == ValidationStatus.VALID;
		}

		public boolean isPermanentlyInvalid() {
			return status != ValidationStatus.VALID && status != ValidationStatus.TRANSIENT_ERROR;
		}
	}

	public enum ValidationStatus {
		VALID,
		STATE_INVALID,
		STATELESS_INVALID,
		FEE_TOO_LOW,
		GOVERNANCE_DUPLICATE,
		STALE,
		TRANSIENT_ERROR
	}

	private enum ValidationMode {
		ADMISSION,
		REVALIDATION
	}
}
