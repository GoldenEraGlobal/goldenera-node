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

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.MiningConsensusRules;
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
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorMiningPolicySetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipValidatorRemovePayload;
import global.goldenera.cryptoj.common.state.AccountBalanceState;
import global.goldenera.cryptoj.common.state.AccountNonceState;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.NetworkParamsState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.TxPayloadVersion;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.node.Constants;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.core.blockchain.events.MempoolTxAddEvent;
import global.goldenera.node.core.blockchain.state.ChainHeadStateCache;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.blockchain.time.ChainClock;
import global.goldenera.node.core.blockchain.validation.TxValidator;
import global.goldenera.node.core.mempool.MempoolManager.MempoolReasonCode;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.processing.MiningEconomicsPayloadRules;
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
	ChainClock chainClock;

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
			Instant earliestBlockTimestamp = chainClock.earliestNextBlockTimestamp(chainTip.getHeader());

			if (tx.getSender() != null) {
				return validateUserTx(tx, worldstate, mode, earliestBlockTimestamp,
						Math.addExact(chainTip.getHeight(), 1));
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
			Instant earliestBlockTimestamp, long candidateBlockHeight) {
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
		MempoolStore.AdmissionConstraints admissionConstraints = null;
		switch (tx.getType()) {
			case TRANSFER:
				AccountBalanceState nativeBalance = worldstate.getBalance(sender, Address.NATIVE_TOKEN);
				Wei projectedNativeBalance = projectedNativeSpendable(
						worldstate, sender, nativeBalance, candidateBlockHeight);
					MempoolStore.ReservationSnapshot nativeReservation = mempoolStorage.nativeReservation(
							sender, tx, projectedNativeBalance);

				if (!tx.getTokenAddress().equals(Address.NATIVE_TOKEN)) {
					// Check custom token balance
					TokenState tokenState = worldstate.getToken(tx.getTokenAddress());
					if (!tokenState.exists()) {
						return MempoolValidationResult.invalid("Token does not exist on-chain.");
					}
					AccountBalanceState tokenBalance = worldstate.getBalance(sender, tx.getTokenAddress());
					MempoolStore.ReservationSnapshot tokenReservation = mempoolStorage.tokenReservation(
							sender, tx.getTokenAddress(), tx, tokenBalance.getBalance());
					if (mode == ValidationMode.ADMISSION && !tokenReservation.affordable()) {
						return MempoolValidationResult.invalid(insufficientFundsMessage(
								"token", tx.getTokenAddress(), tokenReservation));
					}
				}
				if (mode == ValidationMode.ADMISSION && !nativeReservation.affordable()) {
					return insufficientNativeFunds(nativeBalance, nativeReservation,
							insufficientFundsMessage("native", Address.NATIVE_TOKEN, nativeReservation));
				}
				Map<Address, Wei> tokenBalances = new HashMap<>();
				if (!Address.NATIVE_TOKEN.equals(tx.getTokenAddress())) {
					tokenBalances.put(tx.getTokenAddress(),
							worldstate.getBalance(sender, tx.getTokenAddress()).getBalance());
				}
					admissionConstraints = new MempoolStore.AdmissionConstraints(
							projectedNativeBalance, tokenBalances, null);

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
				Wei projectedGovernanceBalance = projectedNativeSpendable(
						worldstate, sender, governanceBalance, candidateBlockHeight);
					MempoolStore.ReservationSnapshot governanceReservation = mempoolStorage.nativeReservation(
							sender, tx, projectedGovernanceBalance);
				if (mode == ValidationMode.ADMISSION && !governanceReservation.affordable()) {
					return insufficientNativeFunds(governanceBalance, governanceReservation,
							"Insufficient native funds for governance fee. "
									+ reservationDiagnostic(governanceReservation));
				}
				if (!worldstate.getAuthority(sender).exists()) {
					return MempoolValidationResult.stateInvalid("Sender is not an authority.");
				}
				MempoolValidationResult governanceResult = validateGovernanceTx(tx, worldstate,
						mode == ValidationMode.ADMISSION, earliestBlockTimestamp, candidateBlockHeight);
				if (!governanceResult.isValid()) {
					return governanceResult;
				}
				MempoolStore.MintSupplyConstraint mintConstraint = null;
				if (tx.getPayload() instanceof TxBipTokenMintPayload mint) {
					TokenState token = worldstate.getToken(mint.getTokenAddress());
					if (token.getMaxSupply() != null) {
						BigInteger maxPending = token.getMaxSupply().subtract(token.getTotalSupply().toBigInteger());
						mintConstraint = new MempoolStore.MintSupplyConstraint(mint.getTokenAddress(), maxPending);
					}
				}
				admissionConstraints = new MempoolStore.AdmissionConstraints(
						projectedGovernanceBalance, Map.of(), mintConstraint);
				break;
			default:
				return MempoolValidationResult.stateInvalid("Unsupported user transaction type: " + tx.getType());
		}

		// All checks passed for this user tx
		log.debug("[VALIDATOR-DEBUG] VALID: tx {} passed all checks, chainNonce={}",
				tx.getHash().toShortLogString(), currentChainNonce);
		return MempoolValidationResult.valid(currentChainNonce, admissionConstraints);
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
			Instant earliestBlockTimestamp, long candidateBlockHeight) {
		if (tx.getType() == TxType.BIP_CREATE) {
			TxPayload payload = tx.getPayload();
			try {
				MiningEconomicsPayloadRules.validateAtHeight(payload, candidateBlockHeight);
			} catch (RuntimeException exception) {
				MempoolReasonCode reasonCode = governanceValidationReason(payload, candidateBlockHeight);
				return MempoolValidationResult.stateInvalid(reasonCode, exception.getMessage());
			}

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
				TxBipValidatorAddPayload validatorAdd = (TxBipValidatorAddPayload) payload;
				Address addr = validatorAdd.getAddress();
				// Check chain
				if (worldstate.getValidator(addr).exists()) {
					return MempoolValidationResult.stateInvalid(
							MempoolReasonCode.INVALID_POLICY_TRANSITION,
							"Validator already exists on-chain.");
				}
				if (validatorAdd.getMiningLimitMode() == MiningLimitMode.LIMITED) {
					long window = effectiveMiningWindow(worldstate.getParams(), candidateBlockHeight);
					long share = validatorAdd.getMaxMiningShareBps();
					if (window * share < 10_000) {
						return MempoolValidationResult.stateInvalid(
								MempoolReasonCode.LIMITED_QUOTA_ZERO,
								"LIMITED policy would allow zero blocks in the configured window.");
					}
					if (worldstate.getParams().getCurrentValidatorCount() == 0) {
						return MempoolValidationResult.stateInvalid(
								MempoolReasonCode.LAST_UNLIMITED_REQUIRED,
								"The first validator must use an UNLIMITED mining policy.");
					}
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
			} else if (payload instanceof TxBipValidatorMiningPolicySetPayload policyPayload) {
				Address addr = policyPayload.getValidatorAddress();
				if (!worldstate.getValidator(addr).exists()) {
					return MempoolValidationResult.stateInvalid(
							MempoolReasonCode.INVALID_POLICY_TRANSITION,
							"Validator does not exist on-chain.");
				}
				if (policyPayload.getMiningLimitMode() == MiningLimitMode.LIMITED) {
					long window = effectiveMiningWindow(worldstate.getParams(), candidateBlockHeight);
					if (window * policyPayload.getMaxMiningShareBps() < 10_000) {
						return MempoolValidationResult.stateInvalid(
								MempoolReasonCode.LIMITED_QUOTA_ZERO,
								"LIMITED policy would allow zero blocks in the configured window.");
					}
				}
				if (isConflictingAdmission(tx, checkMempoolDuplicates,
						mempoolStorage.isValidatorMiningPolicyChangePending(addr))) {
					return MempoolValidationResult.governanceDuplicate(
							"Validator mining policy change is already pending in mempool.");
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
						mempoolStorage.isNetworkParamsChangePending())) {
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
					Hash replacedHash = replacedHash(tx);
					BigInteger pendingMints = mempoolStorage.getPendingTokenMintAmount(
							mintPayload.getTokenAddress(), replacedHash);
					BigInteger newTotalSupply = tokenState.getTotalSupply().toBigInteger()
							.add(pendingMints)
							.add(mintPayload.getAmount().toBigInteger());
					if (newTotalSupply.compareTo(tokenState.getMaxSupply()) > 0) {
						return MempoolValidationResult.invalid(
								"Minting would exceed maxSupply. Current: "
										+ tokenState.getTotalSupply().toBigInteger().toString()
										+ ", Pending: " + pendingMints
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
				if (isConflictingAdmission(tx, checkMempoolDuplicates,
						mempoolStorage.isTokenUpdatePending(updatePayload.getTokenAddress()))) {
					return MempoolValidationResult.governanceDuplicate(
							"TokenUpdate is already pending in mempool for this token.");
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

	private String insufficientFundsMessage(String asset, Address token,
			MempoolStore.ReservationSnapshot reservation) {
		return "Insufficient " + asset + " funds for " + token.toChecksumAddress() + ". "
				+ reservationDiagnostic(reservation);
	}

	private MempoolValidationResult insufficientNativeFunds(AccountBalanceState balance,
			MempoolStore.ReservationSnapshot reservation, String message) {
		MempoolReasonCode reasonCode = reservation.required().compareTo(balance.getBalance()) <= 0
				? MempoolReasonCode.INSUFFICIENT_SPENDABLE_BALANCE
				: null;
		return MempoolValidationResult.stateInvalid(reasonCode, message);
	}

	private Wei projectedNativeSpendable(WorldState worldState, Address sender,
			AccountBalanceState balance, long candidateBlockHeight) {
		Wei maturingReward = worldState.getMiningRewardMaturity(candidateBlockHeight).getRewards().get(sender);
		return balance.getSpendableBalance().addExact(maturingReward == null ? Wei.ZERO : maturingReward);
	}

	private String reservationDiagnostic(MempoolStore.ReservationSnapshot reservation) {
		return "available=" + reservation.available().toBigInteger()
				+ ", reserved=" + reservation.reserved().toBigInteger()
				+ ", replacing=" + reservation.replacing().toBigInteger()
				+ ", candidate=" + reservation.candidate().toBigInteger()
				+ ", required=" + reservation.required().toBigInteger();
	}

	private Hash replacedHash(Tx candidate) {
		return mempoolStorage.getTransactionBySenderAndNonce(candidate.getSender(), candidate.getNonce())
				.map(MempoolEntry::getHash)
				.orElse(null);
	}

	private long effectiveMiningWindow(NetworkParamsState params, long candidateBlockHeight) {
		if (params.getVersion() == NetworkParamsStateVersion.V1
				&& Constants.isForkActive(ForkName.MINING_ECONOMICS, candidateBlockHeight)) {
			return Constants.getSettings().genesisNetworkValidatorMiningWindowBlocks();
		}
		return params.getValidatorMiningWindowBlocks();
	}

	private MempoolReasonCode governanceValidationReason(TxPayload payload, long candidateBlockHeight) {
		if (payload instanceof TxBipNetworkParamsSetPayload networkParams
				&& Constants.isForkActive(ForkName.MINING_ECONOMICS, candidateBlockHeight)
				&& networkParams.getPayloadVersion() == TxPayloadVersion.V2) {
			Long window = networkParams.getValidatorMiningWindowBlocks();
			if (window != null && (window < MiningConsensusRules.MIN_VALIDATOR_MINING_WINDOW_BLOCKS
					|| window > MiningConsensusRules.MAX_VALIDATOR_MINING_WINDOW_BLOCKS)) {
				return MempoolReasonCode.MINING_WINDOW_OUT_OF_RANGE;
			}
			Long vestingBlocks = networkParams.getMiningRewardVestingBlocks();
			if (vestingBlocks != null && (vestingBlocks < 0
					|| vestingBlocks > MiningConsensusRules.MAX_MINING_REWARD_VESTING_BLOCKS)) {
				return MempoolReasonCode.MINING_REWARD_VESTING_OUT_OF_RANGE;
			}
		}
		return payload instanceof TxBipNetworkParamsSetPayload
				? MempoolReasonCode.VALIDATION_STATELESS_INVALID
				: MempoolReasonCode.INVALID_POLICY_TRANSITION;
	}

	private boolean isConflictingAdmission(Tx candidate, boolean checkMempoolDuplicates, boolean pending) {
		if (!checkMempoolDuplicates || !pending) {
			return false;
		}
		return mempoolStorage.hasGovernanceConflict(new MempoolEntry(candidate), replacedHash(candidate));
	}

	// --- Helper Inner Class for Validation Result ---

	@Getter
	public static class MempoolValidationResult {

		private final ValidationStatus status;
		private final MempoolReasonCode reasonCode;
		private final String errorMessage;
		private final long currentChainNonce; // The confirmed nonce from the chain
		private final MempoolStore.AdmissionConstraints admissionConstraints;

		private MempoolValidationResult(ValidationStatus status, MempoolReasonCode reasonCode,
				String errorMessage, long currentChainNonce,
				MempoolStore.AdmissionConstraints admissionConstraints) {
			this.status = status;
			this.reasonCode = reasonCode;
			this.errorMessage = errorMessage;
			this.currentChainNonce = currentChainNonce;
			this.admissionConstraints = admissionConstraints;
		}

		public static MempoolValidationResult valid(long currentChainNonce) {
			return valid(currentChainNonce, null);
		}

		public static MempoolValidationResult valid(long currentChainNonce,
				MempoolStore.AdmissionConstraints admissionConstraints) {
			return new MempoolValidationResult(
					ValidationStatus.VALID, null, null, currentChainNonce, admissionConstraints);
		}

		public static MempoolValidationResult invalid(String message) {
			return stateInvalid(message);
		}

		public static MempoolValidationResult stateInvalid(String message) {
			return stateInvalid(null, message);
		}

		public static MempoolValidationResult stateInvalid(MempoolReasonCode reasonCode, String message) {
			return new MempoolValidationResult(ValidationStatus.STATE_INVALID, reasonCode, message, -1L, null);
		}

		public static MempoolValidationResult statelessInvalid(String message) {
			return new MempoolValidationResult(ValidationStatus.STATELESS_INVALID, null, message, -1L, null);
		}

		public static MempoolValidationResult feeTooLow(String message) {
			return new MempoolValidationResult(ValidationStatus.FEE_TOO_LOW, null, message, -1L, null);
		}

		public static MempoolValidationResult governanceDuplicate(String message) {
			return new MempoolValidationResult(ValidationStatus.GOVERNANCE_DUPLICATE, null, message, -1L, null);
		}

		public static MempoolValidationResult transientError(String message) {
			return new MempoolValidationResult(ValidationStatus.TRANSIENT_ERROR, null, message, -1L, null);
		}

		public static MempoolValidationResult stale(long currentChainNonce, String message) {
			return new MempoolValidationResult(ValidationStatus.STALE, null, message, currentChainNonce, null);
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
