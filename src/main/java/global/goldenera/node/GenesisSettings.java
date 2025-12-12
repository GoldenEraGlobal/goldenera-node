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
package global.goldenera.node;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;

/**
 * Genesis configuration settings loaded from JSON files.
 * 
 * These settings define the initial state of the blockchain at block 0.
 * For dev profile, developers can customize these in local JSON files.
 * 
 * File naming: genesis-{network}-{profile}.json
 * Examples:
 * - genesis-mainnet-prod.json (versioned, production)
 * - genesis-testnet-dev.json (git-ignored, local development)
 */
public record GenesisSettings(
        // Size limits
        long maxHeaderSizeInBytes,
        long maxTxSizeInBytes,
        long maxBlockSizeInBytes,
        long maxTxCountPerBlock,

        // BIP (Blockchain Improvement Proposal) configuration
        long bipExpirationPeriodMs,
        long bipApprovalThresholdBps,

        // Genesis network parameters
        Wei genesisNetworkBlockReward,
        Address genesisNetworkBlockRewardPoolAddress,
        Wei genesisNetworkInitialMintForBlockReward,
        long genesisNetworkTargetMiningTimeMs,
        long genesisNetworkAsertHalfLifeBlocks,
        BigInteger genesisNetworkMinDifficulty,
        Wei genesisNetworkMinTxBaseFee,
        Wei genesisNetworkMinTxByteFee,

        // Genesis authorities
        List<Address> genesisAuthorityAddresses,
        Wei genesisNetworkInitialMintForAuthority,

        // Genesis block configuration
        long genesisBlockTimestamp,
        BigInteger genesisBlockDifficulty,

        // Native token configuration
        String genesisNativeTokenName,
        String genesisNativeTokenTicker,
        int genesisNativeTokenDecimals,
        String genesisNativeTokenWebsite,
        String genesisNativeTokenLogo,
        boolean genesisNativeTokenUserBurnable,

        // RandomX configuration
        long randomXEpochLength,
        String randomXGenesisKey,
        int randomXBatchSize) {
}
