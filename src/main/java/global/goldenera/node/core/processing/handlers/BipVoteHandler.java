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
package global.goldenera.node.core.processing.handlers;

import static com.google.common.base.Preconditions.checkArgument;

import java.math.BigInteger;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAddressAliasRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityAddPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipAuthorityRemovePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipNetworkParamsSetPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenBurnPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenCreatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenMintPayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipTokenUpdatePayload;
import global.goldenera.cryptoj.common.payloads.bip.TxBipVotePayload;
import global.goldenera.cryptoj.common.state.BipState;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AddressAliasStateImpl;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.BipStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.state.AddressAliasStateVersion;
import global.goldenera.cryptoj.enums.state.AuthorityStateVersion;
import global.goldenera.cryptoj.enums.state.BipStatus;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.processing.TxExecutionContext;
import global.goldenera.node.core.state.WorldState;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BipVoteHandler implements TxHandler {

	@Override
	public TxType getSupportedType() {
		return TxType.BIP_VOTE;
	}

	@Override
	public void execute(TxExecutionContext context) {
		WorldState state = context.getState();
		Tx tx = context.getTx();
		SimpleBlock block = context.getBlock();
		checkArgument(state.getAuthority(tx.getSender()).exists(), "Sender is not an authority");

		Hash bipHash = tx.getReferenceHash();

		BipStateImpl bipState = (BipStateImpl) state.getBip(bipHash);

		validateVoteEligibility(bipState, block, tx.getSender());

		TxBipVotePayload payload = (TxBipVotePayload) tx.getPayload();

		BipStateImpl updatedBip = bipState.registerVote(
				tx.getSender(),
				payload.getType(),
				tx.getHash(),
				block.getHeight(),
				block.getTimestamp());

		if (updatedBip.getStatus() == BipStatus.PENDING) {
			long totalAuthorities = state.getParams().getCurrentAuthorityCount();
			long currentApprovals = updatedBip.getApprovalCount();
			long currentDisapprovals = updatedBip.getDisapprovalCount();
			long requiredVotes = updatedBip.getNumberOfRequiredVotes();

			// Calculate max possible approvals assuming all remaining voters approve
			long maxPossibleApprovals = totalAuthorities - currentDisapprovals;

			if (currentApprovals >= requiredVotes) {
				updatedBip = updatedBip.updateStatus(BipStatus.APPROVED, block.getHeight(), block.getTimestamp());
			} else if (maxPossibleApprovals < requiredVotes) {
				updatedBip = updatedBip.updateStatus(BipStatus.DISAPPROVED, block.getHeight(), block.getTimestamp());
			}
		}

		state.setBip(bipHash, updatedBip);

		if (updatedBip.getStatus() == BipStatus.APPROVED && !updatedBip.isActionExecuted()) {
			executeBipAction(state, updatedBip, bipHash, block, context.getActualBurnAmounts());
		}
	}

	private void validateVoteEligibility(BipState bip, SimpleBlock block, Address sender) {
		checkArgument(bip.exists(), "Cannot vote on non-existent BIP");
		checkArgument(bip.getStatus() == BipStatus.PENDING, "BIP is not in PENDING state");
		checkArgument(block.getTimestamp().isBefore(bip.getExpirationTimestamp()), "BIP expired");
		checkArgument(!bip.getAllVoters().contains(sender), "Authority already voted");
	}

	private void executeBipAction(WorldState state, BipStateImpl approvedBip, Hash bipHash, SimpleBlock block,
			Map<Hash, Wei> actualBurnAmounts) {
		TxPayload payload = approvedBip.getMetadata().getTxPayload();

		BipStateImpl executedState = approvedBip.markAsExecuted(block.getHeight(), block.getTimestamp());
		state.setBip(bipHash, executedState);

		switch (payload.getPayloadType()) {
			case BIP_TOKEN_CREATE -> processTokenCreate(state, approvedBip, block, bipHash);
			case BIP_TOKEN_UPDATE -> processTokenUpdate(state, (TxBipTokenUpdatePayload) payload, block, bipHash);
			case BIP_TOKEN_MINT -> processTokenMint(state, (TxBipTokenMintPayload) payload, block, bipHash);
			case BIP_TOKEN_BURN -> processTokenBurn(state, (TxBipTokenBurnPayload) payload, block, bipHash,
					actualBurnAmounts);
			case BIP_AUTHORITY_ADD -> processAuthorityAdd(state, (TxBipAuthorityAddPayload) payload, block, bipHash);
			case BIP_AUTHORITY_REMOVE -> processAuthorityRemove(state, (TxBipAuthorityRemovePayload) payload, block,
					bipHash);
			case BIP_NETWORK_PARAMS_SET -> processParamsSet(state, (TxBipNetworkParamsSetPayload) payload, block,
					bipHash);
			case BIP_ADDRESS_ALIAS_ADD -> processAliasAdd(state, (TxBipAddressAliasAddPayload) payload, block, bipHash);
			case BIP_ADDRESS_ALIAS_REMOVE -> processAliasRemove(state, (TxBipAddressAliasRemovePayload) payload, block);
			default -> log.warn("BIP Approved but no execution logic for {}", payload.getPayloadType());
		}
	}

	// --- Execution Logic ---

	private void processTokenCreate(WorldState state, BipState bip, SimpleBlock block, Hash bipHash) {
		Address tokenAddr = bip.getMetadata().getDerivedTokenAddress();
		TxBipTokenCreatePayload p = (TxBipTokenCreatePayload) bip.getMetadata().getTxPayload();

		checkArgument(state.checkAndMarkTokenAsUpdated(tokenAddr), "Token created twice in block");
		checkArgument(!state.getToken(tokenAddr).exists(), "Token already exists");

		TokenStateImpl token = TokenStateImpl.builder()
				.version(TokenStateVersion.V1)
				.name(p.getName())
				.smallestUnitName(p.getSmallestUnitName())
				.numberOfDecimals(p.getNumberOfDecimals())
				.websiteUrl(p.getWebsiteUrl())
				.logoUrl(p.getLogoUrl())
				.maxSupply(p.getMaxSupply())
				.userBurnable(p.isUserBurnable())
				.totalSupply(Wei.ZERO)
				.originTxHash(bipHash)
				.updatedByTxHash(bipHash)
				.updatedAtBlockHeight(block.getHeight())
				.updatedAtTimestamp(block.getTimestamp())
				.build();

		state.setToken(tokenAddr, token);
	}

	private void processTokenUpdate(WorldState state, TxBipTokenUpdatePayload p, SimpleBlock block, Hash bipHash) {
		TokenStateImpl old = (TokenStateImpl) state.getToken(p.getTokenAddress());
		checkArgument(old.exists(), "Token not found");

		state.setToken(p.getTokenAddress(), old.updateDetails(p, bipHash, block.getHeight(), block.getTimestamp()));
	}

	private void processTokenMint(WorldState state, TxBipTokenMintPayload p, SimpleBlock block, Hash bipHash) {
		TokenStateImpl token = (TokenStateImpl) state.getToken(p.getTokenAddress());
		checkArgument(token.exists(), "Token not found");

		// Validate maxSupply constraint for custom tokens
		if (token.getMaxSupply() != null) {
			BigInteger newTotalSupply = token.getTotalSupply().toBigInteger().add(p.getAmount().toBigInteger());
			checkArgument(newTotalSupply.compareTo(token.getMaxSupply()) <= 0,
					"Minting would exceed maxSupply. Current: %s, Minting: %s, MaxSupply: %s",
					token.getTotalSupply(), p.getAmount(), token.getMaxSupply());
		}

		state.setToken(p.getTokenAddress(),
				token.mint(p.getAmount(), bipHash, block.getHeight(), block.getTimestamp()));

		Address recipient = p.getRecipient();
		checkArgument(recipient != null, "Mint recipient cannot be null");

		AccountBalanceStateImpl bal = (AccountBalanceStateImpl) state.getBalance(recipient, p.getTokenAddress());
		state.setBalance(recipient, p.getTokenAddress(),
				bal.credit(p.getAmount(), block.getHeight(), block.getTimestamp()));
	}

	private void processTokenBurn(WorldState state, TxBipTokenBurnPayload p, SimpleBlock block, Hash bipHash,
			Map<Hash, Wei> actualBurnAmounts) {
		// 1. Load Token and Balance first to determine availability
		TokenStateImpl token = (TokenStateImpl) state.getToken(p.getTokenAddress());
		checkArgument(token.exists(), "Token not found");

		Address target = p.getSender();
		checkArgument(target != null, "Burn target cannot be null");

		AccountBalanceStateImpl bal = (AccountBalanceStateImpl) state.getBalance(target, p.getTokenAddress());

		// 2. Calculate the actual amount to burn (Cap at user's balance)
		Wei requestedAmount = p.getAmount();
		Wei currentBalance = bal.getBalance();
		Wei actualBurnAmount = requestedAmount;

		if (currentBalance.compareTo(requestedAmount) < 0) {
			// User has less than requested, burn everything they have
			actualBurnAmount = currentBalance;
		}

		actualBurnAmounts.put(bipHash, actualBurnAmount);

		// Optimize: If amount is zero
		if (actualBurnAmount.equals(Wei.ZERO)) {
			return;
		}

		// 3. Update Token Total Supply with the ACTUAL amount
		state.setToken(p.getTokenAddress(),
				token.burn(actualBurnAmount, bipHash, block.getHeight(), block.getTimestamp()));

		// 4. Debit User with the ACTUAL amount
		state.setBalance(target, p.getTokenAddress(),
				bal.debit(actualBurnAmount, block.getHeight(), block.getTimestamp()));
	}

	private void processAuthorityAdd(WorldState state, TxBipAuthorityAddPayload p, SimpleBlock block, Hash bipHash) {
		checkArgument(!state.getAuthority(p.getAddress()).exists(), "Authority exists");

		state.addAuthority(p.getAddress(), AuthorityStateImpl.builder()
				.version(AuthorityStateVersion.V1)
				.originTxHash(bipHash)
				.createdAtBlockHeight(block.getHeight())
				.createdAtTimestamp(block.getTimestamp())
				.build());

		NetworkParamsStateImpl oldParams = (NetworkParamsStateImpl) state.getParams();
		state.setParams(oldParams.incrementAuthorityCount(bipHash, block.getHeight(), block.getTimestamp()));
	}

	private void processAuthorityRemove(WorldState state, TxBipAuthorityRemovePayload p, SimpleBlock block,
			Hash bipHash) {
		checkArgument(state.getAuthority(p.getAddress()).exists(), "Authority missing");
		state.removeAuthority(p.getAddress());

		NetworkParamsStateImpl oldParams = (NetworkParamsStateImpl) state.getParams();
		state.setParams(oldParams.decrementAuthorityCount(bipHash, block.getHeight(), block.getTimestamp()));
	}

	private void processParamsSet(WorldState state, TxBipNetworkParamsSetPayload p, SimpleBlock block, Hash bipHash) {
		checkArgument(!state.isParamsChangedThisBlock(), "Double params change");

		NetworkParamsStateImpl old = (NetworkParamsStateImpl) state.getParams();
		state.setParams(old.updateParams(p, bipHash, block.getHeight(), block.getTimestamp()));
		state.markParamsAsChanged();
	}

	private void processAliasAdd(WorldState state, TxBipAddressAliasAddPayload p, SimpleBlock block, Hash bipHash) {
		checkArgument(!state.getAddressAlias(p.getAlias()).exists(), "Alias exists");

		state.addAddressAlias(p.getAlias(), AddressAliasStateImpl.builder()
				.version(AddressAliasStateVersion.V1)
				.address(p.getAddress())
				.originTxHash(bipHash)
				.createdAtBlockHeight(block.getHeight())
				.createdAtTimestamp(block.getTimestamp())
				.build());
	}

	private void processAliasRemove(WorldState state, TxBipAddressAliasRemovePayload p, SimpleBlock block) {
		checkArgument(state.getAddressAlias(p.getAlias()).exists(), "Alias missing");
		state.removeAddressAlias(p.getAlias());
	}
}