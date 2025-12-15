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
import java.util.Collections;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Service;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.state.impl.AccountBalanceStateImpl;
import global.goldenera.cryptoj.common.state.impl.AuthorityStateImpl;
import global.goldenera.cryptoj.common.state.impl.NetworkParamsStateImpl;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.common.state.impl.ValidatorStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.state.AuthorityStateVersion;
import global.goldenera.cryptoj.enums.state.NetworkParamsStateVersion;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.cryptoj.enums.state.ValidatorStateVersion;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.Constants;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.state.BlockStateTransitions;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.shared.exceptions.GEFailedException;
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
	WorldStateFactory worldStateFactory;

	public void checkAndInitGenesisBlock() throws Exception {
		if (chainQuery.getStoredBlockByHeight(GENESIS_HEIGHT).isPresent()) {
			return;
		}
		log.warn("Genesis block missing. Initializing from Hardcoded Constants...");
		NetworkSettings settings = Constants.getSettings();
		Instant timestamp = Instant.ofEpochMilli(settings.genesisBlockTimestamp());
		List<Address> authorities = settings.genesisAuthorityAddresses();

		if (authorities.isEmpty()) {
			throw new GEFailedException("Cannot initialize genesis block. Initial genesis authorities are empty");
		}

		WorldState worldState = worldStateFactory.createForValidation(MerkleTrie.EMPTY_TRIE_NODE_HASH);
		executeGenesisStateExplicitly(worldState, authorities, timestamp);

		Hash stateRootHash = worldState.calculateRootHash();
		Hash txRootHash = Hash.ZERO;

		GenesisBlockHeaderTemplate header = GenesisBlockHeaderTemplate.builder()
				.version(BlockVersion.V1)
				.height(GENESIS_HEIGHT)
				.timestamp(timestamp)
				.previousHash(Hash.ZERO)
				.difficulty(settings.genesisBlockDifficulty())
				.txRootHash(txRootHash)
				.stateRootHash(stateRootHash)
				.coinbase(Address.ZERO)
				.build();

		Block genesisBlock = BlockImpl.builder().header(header).txs(Collections.emptyList()).build();

		blockStateTransitionService.connectBlock(
				genesisBlock,
				worldState,
				ConnectedSource.GENESIS,
				null,
				authorities.get(0),
				timestamp);
		log.info("Genesis initialized. Hash: {}", genesisBlock.getHash());
	}

	private void executeGenesisStateExplicitly(WorldState worldState, List<Address> authorities, Instant timestamp) {
		NetworkSettings settings = Constants.getSettings();
		Wei totalSupply = settings.genesisNetworkInitialMintForAuthority()
				.addExact(settings.genesisNetworkInitialMintForBlockReward());

		// 1. Network Params
		NetworkParamsStateImpl params = NetworkParamsStateImpl.builder()
				.version(NetworkParamsStateVersion.V1)
				.blockReward(settings.genesisNetworkBlockReward())
				.targetMiningTimeMs(settings.genesisNetworkTargetMiningTimeMs())
				.blockRewardPoolAddress(settings.genesisNetworkBlockRewardPoolAddress())
				.asertHalfLifeBlocks(settings.genesisNetworkAsertHalfLifeBlocks())
				.asertAnchorHeight(GENESIS_HEIGHT)
				.minDifficulty(settings.genesisNetworkMinDifficulty())
				.minTxBaseFee(settings.genesisNetworkMinTxBaseFee())
				.minTxByteFee(settings.genesisNetworkMinTxByteFee())
				.currentAuthorityCount(authorities.size())
				.updatedByTxHash(Hash.ZERO)
				.updatedAtBlockHeight(GENESIS_HEIGHT)
				.updatedAtTimestamp(timestamp)
				.build();
		worldState.setParams(params);

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
		List<Address> validators = settings.genesisValidatorAddresses();
		for (Address validator : validators) {
			ValidatorStateImpl validatorState = ValidatorStateImpl.builder()
					.version(ValidatorStateVersion.V1)
					.originTxHash(Hash.ZERO)
					.createdAtBlockHeight(GENESIS_HEIGHT)
					.createdAtTimestamp(timestamp)
					.build();
			worldState.addValidator(validator, validatorState);
		}

		Address firstAuthorityAddress = authorities.get(0);
		Address blockRewardPoolAddress = settings.genesisNetworkBlockRewardPoolAddress();

		// Credit first authority with initial mint (credit() returns new immutable
		// object)
		AccountBalanceStateImpl firstAuthorityBalance = (AccountBalanceStateImpl) worldState
				.getBalance(firstAuthorityAddress, Address.NATIVE_TOKEN);
		AccountBalanceStateImpl newFirstAuthorityBalance = firstAuthorityBalance
				.credit(settings.genesisNetworkInitialMintForAuthority(), GENESIS_HEIGHT, timestamp);
		worldState.setBalance(firstAuthorityAddress, Address.NATIVE_TOKEN, newFirstAuthorityBalance);

		// Credit block reward pool with initial mint
		// Note: If blockRewardPoolAddress == firstAuthorityAddress, we need to get the
		// updated balance
		AccountBalanceStateImpl blockRewardPoolBalance = (AccountBalanceStateImpl) worldState
				.getBalance(blockRewardPoolAddress, Address.NATIVE_TOKEN);
		AccountBalanceStateImpl newBlockRewardPoolBalance = blockRewardPoolBalance
				.credit(settings.genesisNetworkInitialMintForBlockReward(), GENESIS_HEIGHT, timestamp);
		worldState.setBalance(blockRewardPoolAddress, Address.NATIVE_TOKEN, newBlockRewardPoolBalance);
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

		@Override
		public long getNonce() {
			return 0;
		}

		@Override
		public Hash getHash() {
			return global.goldenera.cryptoj.utils.BlockHeaderUtil.hash(this);
		}

		@Override
		public int getSize() {
			return global.goldenera.cryptoj.utils.BlockHeaderUtil.size(this);
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