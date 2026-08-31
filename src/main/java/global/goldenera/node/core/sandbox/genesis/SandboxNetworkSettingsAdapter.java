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
package global.goldenera.node.core.sandbox.genesis;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Consensus;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.Genesis;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.NativeToken;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest.RandomX;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;

/** Maps a strict sandbox manifest to the node's existing settings records. */
public final class SandboxNetworkSettingsAdapter {

	public SandboxGenesisConfiguration adapt(SandboxManifestContext context) {
		SandboxManifest manifest = context.manifest();
		Genesis genesis = manifest.genesis();
		Consensus consensus = manifest.consensus();
		NativeToken token = genesis.nativeToken();
		RandomX randomX = manifest.pow().randomX();

		GenesisSettings genesisSettings = new GenesisSettings(
				consensus.maxHeaderSizeBytes(),
				consensus.maxTransactionSizeBytes(),
				consensus.maxBlockSizeBytes(),
				consensus.maxTransactionsPerBlock(),
				consensus.bipExpirationPeriodMs(),
				consensus.bipApprovalThresholdBps(),
				Wei.valueOf(consensus.blockReward()),
				Address.fromHexString(genesis.blockRewardPoolAddress()),
				Wei.valueOf(genesis.initialMintForBlockReward()),
				consensus.targetBlockIntervalMs(),
				consensus.asertHalfLifeBlocks(),
				consensus.minDifficulty(),
				Wei.valueOf(consensus.minTransactionBaseFee()),
					Wei.valueOf(consensus.minTransactionByteFee()),
					consensus.validatorMiningWindowBlocks(),
					consensus.miningRewardVestingBlocks(),
				genesis.authorities().stream().map(Address::fromHexString).toList(),
				Wei.valueOf(genesis.initialMintForAuthority()),
				genesis.validators().stream().map(Address::fromHexString).toList(),
				genesis.timestampMs(),
				genesis.blockDifficulty(),
				token.name(),
				token.ticker(),
				token.decimals(),
				token.website(),
				token.logo(),
				token.userBurnable(),
				randomX.epochLength(),
				randomX.genesisKey(),
				randomX.batchSize());

		Map<Address, Wei> balances = new LinkedHashMap<>();
		genesis.initialBalances().forEach((address, balance) ->
				balances.put(Address.fromHexString(address), Wei.valueOf(balance)));
		validateRequiredAllocations(genesis, balances);

		NetworkSettings networkSettings = new NetworkSettings(
				genesisSettings.maxHeaderSizeInBytes(),
				genesisSettings.maxTxSizeInBytes(),
				genesisSettings.maxBlockSizeInBytes(),
				genesisSettings.maxTxCountPerBlock(),
				genesisSettings.bipExpirationPeriodMs(),
				genesisSettings.bipApprovalThresholdBps(),
				genesisSettings.genesisNetworkBlockReward(),
				genesisSettings.genesisNetworkBlockRewardPoolAddress(),
				genesisSettings.genesisNetworkInitialMintForBlockReward(),
				genesisSettings.genesisNetworkTargetMiningTimeMs(),
				genesisSettings.genesisNetworkAsertHalfLifeBlocks(),
				genesisSettings.genesisNetworkMinDifficulty(),
				genesisSettings.genesisNetworkMinTxBaseFee(),
					genesisSettings.genesisNetworkMinTxByteFee(),
					genesisSettings.genesisNetworkValidatorMiningWindowBlocks(),
					genesisSettings.genesisNetworkMiningRewardVestingBlocks(),
				genesisSettings.genesisAuthorityAddresses(),
				genesisSettings.genesisNetworkInitialMintForAuthority(),
				genesisSettings.genesisValidatorAddresses(),
				balances,
				genesisSettings.genesisBlockTimestamp(),
				genesisSettings.genesisBlockDifficulty(),
				genesisSettings.genesisNativeTokenName(),
				genesisSettings.genesisNativeTokenTicker(),
				genesisSettings.genesisNativeTokenDecimals(),
				genesisSettings.genesisNativeTokenWebsite(),
				genesisSettings.genesisNativeTokenLogo(),
				genesisSettings.genesisNativeTokenUserBurnable(),
				genesisSettings.randomXEpochLength(),
				genesisSettings.randomXGenesisKey(),
				genesisSettings.randomXBatchSize(),
				manifest.forks(),
				Map.of(),
				Map.of(),
				Map.of(),
				Map.of(),
				Map.of());

		return new SandboxGenesisConfiguration(
				manifest.chainId(),
				context.fingerprint(),
				genesis.seed(),
				Hash.fromHexString(genesis.expectedGenesisHash()),
				Network.TESTNET,
				genesisSettings,
				networkSettings,
				balances);
	}

	private void validateRequiredAllocations(Genesis genesis, Map<Address, Wei> balances) {
		Address firstAuthority = Address.fromHexString(genesis.authorities().get(0));
		Address rewardPool = Address.fromHexString(genesis.blockRewardPoolAddress());
		Wei authorityMinimum = Wei.valueOf(genesis.initialMintForAuthority());
		Wei rewardPoolMinimum = Wei.valueOf(genesis.initialMintForBlockReward());
		Wei requiredRewardPoolBalance = rewardPool.equals(firstAuthority)
				? rewardPoolMinimum.addExact(authorityMinimum)
				: rewardPoolMinimum;
		if (balances.getOrDefault(firstAuthority, Wei.ZERO).compareTo(authorityMinimum) < 0) {
			throw new IllegalArgumentException(
					"Sandbox initial balances must fund the first authority by at least initialMintForAuthority");
		}
		if (balances.getOrDefault(rewardPool, Wei.ZERO).compareTo(requiredRewardPoolBalance) < 0) {
			throw new IllegalArgumentException(
					"Sandbox initial balances must fund the reward pool by the declared genesis mint");
		}
	}
}
