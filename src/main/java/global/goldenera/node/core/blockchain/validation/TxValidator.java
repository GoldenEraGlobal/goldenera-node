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

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.node.Constants;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.utils.ValidatorUtil;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
public class TxValidator {

	/**
	 * Stateless validation: Checks internal consistency of the transaction.
	 * Does not require DB access.
	 */
	public void validateStateless(@NonNull Tx tx) {
		try {
			// 1. Basic Size & Limits (Cheap)
			if (tx.getSize() > Constants.MAX_TX_SIZE_IN_BYTES) {
				throw new GEValidationException("Tx too large: " + tx.getSize());
			}

			// 2. Economy Checks (Cheap)
			if (tx.getFee().compareTo(Wei.ZERO) < 0) {
				throw new GEValidationException("Negative fee");
			}
			if (tx.getAmount() != null && tx.getAmount().compareTo(Wei.ZERO) < 0) {
				throw new GEValidationException("Negative amount");
			}
			if (tx.getNonce() != null && tx.getNonce() < 0) {
				throw new GEValidationException("Negative nonce");
			}

			// 3. Payload Specific Validation (Cheap)
			validatePayload(tx);

			// 4. Structure Validation based on Type (Cheap to Medium)
			// Note: BIP_VOTE check might trigger sender recovery, but others won't.
			validateFieldsByType(tx);

			// 5. Signature & Sender Validity (Expensive - triggers ecrecover)
			if (tx.getSender() == null) {
				throw new GEValidationException("Invalid signature: Sender could not be recovered");
			}

			// 6. Sanity Checks (Depends on Sender)
			if (tx.getSender().equals(tx.getRecipient())) {
				throw new GEValidationException("Self-transfer not allowed");
			}

		} catch (IllegalArgumentException e) {
			throw new GEValidationException(e.getMessage());
		}
	}

	private void validatePayload(Tx tx) {
		if (tx.getPayload() == null || tx.getPayload().getPayloadType() == null) {
			return;
		}

		switch (tx.getPayload().getPayloadType()) {
			case BIP_TOKEN_CREATE:
				if (tx.getPayload() instanceof TxBipTokenCreatePayload p) {
					validateTokenName(p.getName());
					validateTokenName(p.getSmallestUnitName());
					validateTokenDecimals(p.getNumberOfDecimals());
					ValidatorUtil.Url.url(p.getWebsiteUrl());
					ValidatorUtil.Url.url(p.getLogoUrl());
					validateMaxSupply(p.getMaxSupply());
				}
				break;
			case BIP_TOKEN_UPDATE:
				if (tx.getPayload() instanceof TxBipTokenUpdatePayload p) {
					if (p.getTokenAddress() == null) {
						throw new GEValidationException("Token address cannot be null for update");
					}
					// null values during update mean the old value is kept
					if (p.getName() != null) {
						validateTokenName(p.getName());
					}
					if (p.getSmallestUnitName() != null) {
						validateTokenName(p.getSmallestUnitName());
					}
					ValidatorUtil.Url.url(p.getWebsiteUrl());
					ValidatorUtil.Url.url(p.getLogoUrl());
				}
				break;
			case BIP_TOKEN_MINT:
				if (tx.getPayload() instanceof TxBipTokenMintPayload p) {
					if (p.getTokenAddress() == null) {
						throw new GEValidationException("Token address cannot be null for mint");
					}
					validateAmount(p.getAmount());
					if (p.getRecipient() == null) {
						throw new GEValidationException("Mint recipient cannot be null");
					}
				}
				break;
			case BIP_TOKEN_BURN:
				if (tx.getPayload() instanceof TxBipTokenBurnPayload p) {
					if (p.getTokenAddress() == null) {
						throw new GEValidationException("Token address cannot be null for burn");
					}
					validateAmount(p.getAmount());
					if (p.getSender() == null) {
						throw new GEValidationException("Burn target (sender) cannot be null");
					}
				}
				break;
			case BIP_AUTHORITY_ADD:
				if (tx.getPayload() instanceof TxBipAuthorityAddPayload p) {
					if (p.getAddress() == null) {
						throw new GEValidationException("Authority address cannot be null");
					}
				}
				break;
			case BIP_AUTHORITY_REMOVE:
				if (tx.getPayload() instanceof TxBipAuthorityRemovePayload p) {
					if (p.getAddress() == null) {
						throw new GEValidationException("Authority address cannot be null");
					}
				}
				break;
			case BIP_ADDRESS_ALIAS_ADD:
				if (tx.getPayload() instanceof TxBipAddressAliasAddPayload p) {
					validateAddressAlias(p.getAlias());
					if (p.getAddress() == null) {
						throw new GEValidationException("Alias address cannot be null");
					}
				}
				break;
			case BIP_ADDRESS_ALIAS_REMOVE:
				if (tx.getPayload() instanceof TxBipAddressAliasRemovePayload p) {
					validateAddressAlias(p.getAlias());
				}
				break;
			case BIP_NETWORK_PARAMS_SET:
				if (tx.getPayload() instanceof TxBipNetworkParamsSetPayload p) {
					validateBlockReward(p.getBlockReward());
					validateMiningTargetTime(p.getTargetMiningTimeMs());
					validateMinTxFee(p.getMinTxBaseFee(), "minTxBaseFee");
					validateMinTxFee(p.getMinTxByteFee(), "minTxByteFee");
					validateMinDifficulty(p.getMinDifficulty());
					validateAsertHalfLifeBlocks(p.getAsertHalfLifeBlocks());
				}
				break;
			default:
				break;
		}
	}

	private void validateFieldsByType(Tx tx) {
		switch (tx.getType()) {
			case TRANSFER:
				checkArgument(tx.getNonce() != null, "Transfer tx requires a valid nonce");
				checkArgument(tx.getRecipient() != null, "Transfer tx must have a recipient");
				checkArgument(tx.getAmount() != null, "Transfer amount must be positive");
				checkArgument(tx.getTokenAddress() != null, "Transfer tx must have a token address");
				checkArgument(tx.getReferenceHash() == null, "Transfer tx must not have a referenceHash");
				break;
			case BIP_CREATE:
				checkArgument(tx.getNonce() != null, "BIP_CREATE tx requires a valid nonce");
				checkArgument(tx.getRecipient() == null, "BIP_CREATE tx must not have a recipient");
				checkArgument(tx.getAmount() == null, "BIP_CREATE tx must not have an amount");
				checkArgument(tx.getPayload() != null, "BIP_CREATE tx must have a payload");
				checkArgument(tx.getReferenceHash() == null, "BIP_CREATE tx must not have a referenceHash");
				break;
			case BIP_VOTE:
				checkArgument(tx.getSender() != null, "BIP_VOTE tx must have a sender (an authority)");
				checkArgument(tx.getNonce() != null, "BIP_VOTE tx requires a valid nonce");
				checkArgument(tx.getRecipient() == null, "BIP_VOTE tx must not have a recipient");
				checkArgument(tx.getAmount() == null, "BIP_VOTE tx must not have an amount");
				checkArgument(tx.getPayload() != null, "BIP_VOTE tx must have a payload (the vote)");
				checkArgument(tx.getReferenceHash() != null,
						"BIP_VOTE tx must have a referenceHash (to the BIP_CREATE tx)");
				break;
			default:
				throw new GEValidationException("Unknown or unsupported TxType: " + tx.getType());
		}
	}

	// ------------------------------------------------------------------------------------------
	// Consensus Rules & Validation Logic
	// ------------------------------------------------------------------------------------------

	private void validateTokenName(String name) {
		if (name == null || !name.matches("[A-Z0-9_]{1,16}")) {
			throw new GEValidationException("Invalid token name: " + name);
		}
		if (name.startsWith("_") || name.endsWith("_")) {
			throw new GEValidationException("Token name can not start or end with '_'.");
		}
	}

	private void validateTokenDecimals(Integer decimals) {
		if (decimals == null || decimals < 0 || decimals > 18) {
			throw new GEValidationException("Invalid decimals: " + decimals);
		}
	}

	private void validateAddressAlias(String alias) {
		if (alias == null || !alias.matches("[a-z0-9_]{1,64}")) {
			throw new GEValidationException("Invalid address alias: " + alias);
		}
	}

	private void validateBlockReward(Wei blockReward) {
		if (blockReward != null && blockReward.compareTo(Wei.ZERO) < 0) {
			throw new GEValidationException("Block reward must be positive");
		}
	}

	private void validateMiningTargetTime(Long miningTargetTime) {
		if (miningTargetTime != null && miningTargetTime < 5000) { // Min 5 seconds
			throw new GEValidationException("Mining target time too low.");
		}
	}

	private void validateMinTxFee(Wei fee, String fieldName) {
		if (fee != null && fee.compareTo(Wei.ZERO) < 0) {
			throw new GEValidationException(fieldName + " must be non-negative");
		}
	}

	private void validateMinDifficulty(BigInteger minDifficulty) {
		if (minDifficulty != null && minDifficulty.compareTo(BigInteger.ZERO) < 0) {
			throw new GEValidationException("minDifficulty must be non-negative");
		}
	}

	private void validateAsertHalfLifeBlocks(Long halfLife) {
		if (halfLife != null && halfLife < 1) {
			throw new GEValidationException("asertHalfLifeBlocks must be at least 1");
		}
	}

	private void validateMaxSupply(BigInteger maxSupply) {
		if (maxSupply != null && maxSupply.compareTo(BigInteger.ZERO) <= 0) {
			throw new GEValidationException("maxSupply must be greater than zero if specified");
		}
	}

	private void validateAmount(Wei amount) {
		if (amount == null || amount.compareTo(Wei.ZERO) <= 0) {
			throw new GEValidationException("Amount must be greater than zero.");
		}
	}
}