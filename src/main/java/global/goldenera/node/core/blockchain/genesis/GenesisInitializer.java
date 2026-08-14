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
package global.goldenera.node.core.blockchain.genesis;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.MiningWindowStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.MiningLimitMode;
import global.goldenera.cryptoj.enums.state.AuthorityStateVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.cryptoj.utils.BlockHeaderUtil;
import global.goldenera.node.Constants;
import global.goldenera.node.Constants.ForkName;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.storage.chainidentity.ChainIdentityGenesisVerifier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class GenesisInitializer {

	static final long GENESIS_HEIGHT = 0L;

	ChainQuery chainQuery;
	BlockStateTransitions blockStateTransitionService;
	ChainIdentityGenesisVerifier genesisVerifier;

	public void checkAndInitGenesisBlock() throws Exception {
		if (chainQuery.getStoredBlockByHeight(GENESIS_HEIGHT).isPresent()) {
			return;
		}
		var plan = genesisVerifier.verifiedGenesisPlan();
		blockStateTransitionService.connectVerifiedGenesis(plan);
		log.info("Verified genesis initialized. Hash: {}", plan.genesisHash());
	}

	public static void executeGenesisStateExplicitly(WorldState worldState, List<Address> authorities, Instant timestamp,
			NetworkSettings settings) {
		Wei totalSupply = settings.genesisInitialBalances().values().stream()
				.reduce(Wei.ZERO, Wei::addExact);
		List<Address> validators = settings.genesisValidatorAddresses();

		// 1. Network Params
		boolean miningEconomicsAtGenesis = settings.forkActivationBlocks()
				.getOrDefault(ForkName.MINING_ECONOMICS, Long.MAX_VALUE) == GENESIS_HEIGHT;
		var paramsBuilder = NetworkParamsStateImpl.builder()
				.version(miningEconomicsAtGenesis ? NetworkParamsStateVersion.V2 : NetworkParamsStateVersion.V1)
				.blockReward(settings.genesisNetworkBlockReward())
				.targetMiningTimeMs(settings.genesisNetworkTargetMiningTimeMs())
				.blockRewardPoolAddress(settings.genesisNetworkBlockRewardPoolAddress())
				.asertHalfLifeBlocks(settings.genesisNetworkAsertHalfLifeBlocks())
				.asertAnchorHeight(GENESIS_HEIGHT)
				.minDifficulty(settings.genesisNetworkMinDifficulty())
				.minTxBaseFee(settings.genesisNetworkMinTxBaseFee())
				.minTxByteFee(settings.genesisNetworkMinTxByteFee())
				.currentAuthorityCount(authorities.size())
				.currentValidatorCount(validators.size())
				.updatedByTxHash(Hash.ZERO)
				.updatedAtBlockHeight(GENESIS_HEIGHT)
				.updatedAtTimestamp(timestamp);
		if (miningEconomicsAtGenesis) {
			paramsBuilder
					.currentUnlimitedValidatorCount(validators.size())
					.validatorMiningWindowBlocks(settings.genesisNetworkValidatorMiningWindowBlocks());
		}
		NetworkParamsStateImpl params = paramsBuilder.build();
		worldState.setParams(params);
		if (miningEconomicsAtGenesis) {
			worldState.setMiningWindow(MiningWindowStateImpl.empty(
					settings.genesisNetworkValidatorMiningWindowBlocks(), GENESIS_HEIGHT));
		}

		// 2. Native Token
		TokenStateImpl token = TokenStateImpl.builder()
				.version(TokenStateVersion.V1)
				.name(settings.genesisNativeTokenName())
				.smallestUnitName(settings.genesisNativeTokenTicker())
				.numberOfDecimals(settings.genesisNativeTokenDecimals())
				.websiteUrl(settings.genesisNativeTokenWebsite())
				.logoUrl(settings.genesisNativeTokenLogo())
				.userBurnable(settings.genesisNativeTokenUserBurnable())
				.maxSupply(null) // Native token has no max supply
				.totalSupply(totalSupply)
				.originTxHash(Hash.ZERO)
				.updatedByTxHash(Hash.ZERO)
				.updatedAtBlockHeight(GENESIS_HEIGHT)
				.updatedAtTimestamp(timestamp)
				.build();
		worldState.setToken(Address.NATIVE_TOKEN, token);

		// 3. Authorities
		for (Address authority : authorities) {
			AuthorityStateImpl authState = AuthorityStateImpl.builder()
					.version(AuthorityStateVersion.V1)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(GENESIS_HEIGHT)
					.createdAtTimestamp(timestamp)
					.build();
			worldState.addAuthority(authority, authState);
		}

		// 4. Validators
		for (Address validator : validators) {
			var validatorBuilder = ValidatorStateImpl.builder()
					.version(miningEconomicsAtGenesis ? ValidatorStateVersion.V2 : ValidatorStateVersion.V1)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(GENESIS_HEIGHT)
					.createdAtTimestamp(timestamp);
			if (miningEconomicsAtGenesis) {
				validatorBuilder
						.miningLimitMode(MiningLimitMode.UNLIMITED)
						.maxMiningShareBps(0)
						.policyUpdatedByTxHash(Hash.ZERO)
						.policyUpdatedAtBlockHeight(GENESIS_HEIGHT)
						.policyUpdatedAtTimestamp(timestamp);
			}
			ValidatorStateImpl validatorState = validatorBuilder.build();
			worldState.addValidator(validator, validatorState);
		}

		for (Map.Entry<Address, Wei> allocation : settings.genesisInitialBalances().entrySet()) {
			AccountBalanceStateImpl initialBalance = (AccountBalanceStateImpl) worldState
					.getBalance(allocation.getKey(), Address.NATIVE_TOKEN);
			AccountBalanceStateImpl allocatedBalance = initialBalance
					.credit(allocation.getValue(), GENESIS_HEIGHT, timestamp);
			worldState.setBalance(allocation.getKey(), Address.NATIVE_TOKEN, allocatedBalance);
		}
	}

	@Data
	@Builder
	static class GenesisBlockHeaderTemplate implements BlockHeader {
		BlockVersion version;
		long height;
		Instant timestamp;
		Hash previousHash;
		BigInteger difficulty;
		Hash txRootHash;
		Hash stateRootHash;
		Address coinbase;
		long nonce;

		@Override
		public long getNonce() {
			return nonce;
		}

		@Override
		public Hash getHash() {
			return BlockHeaderUtil.hash(this);
		}

		@Override
		public int getSize() {
			return BlockHeaderUtil.size(this);
		}

		@Override
		public Signature getSignature() {
			return Signature.ZERO;
		}

		public Address getIdentity() {
			return Address.ZERO;
		}
	}
}
