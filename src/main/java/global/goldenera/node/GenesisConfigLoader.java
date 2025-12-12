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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.tuweni.units.ethereum.Wei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.Network;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads genesis configuration from JSON files.
 * 
 * File naming convention: genesis-{network}-{profile}.json
 * Examples:
 * - genesis-mainnet-prod.json
 * - genesis-testnet-dev.json
 * 
 * For dev profile, files are git-ignored and each developer creates their own.
 * Template files (.example) are provided as starting points.
 * 
 * Note: This loader only loads GenesisSettings (initial blockchain state).
 * Consensus-critical settings (forks, checkpoints, overrides) are hardcoded in
 * Constants.
 */
@UtilityClass
@Slf4j
public class GenesisConfigLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String GENESIS_PATH_TEMPLATE = "genesis/genesis-%s-%s.json";

    /**
     * Load GenesisSettings from JSON file based on network and profile.
     *
     * @param network
     *            The network (MAINNET, TESTNET)
     * @param profile
     *            The Spring profile (prod, dev)
     * @return Loaded GenesisSettings
     * @throws IllegalStateException
     *             if the file cannot be loaded or parsed
     */
    public static GenesisSettings loadGenesisSettings(Network network, String profile) {
        String networkName = network.name().toLowerCase();
        String fileName = String.format(GENESIS_PATH_TEMPLATE, networkName, profile);

        log.info("Loading genesis configuration from: {}", fileName);

        try (InputStream is = GenesisConfigLoader.class.getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Genesis configuration file not found: " + fileName +
                                ". For dev profile, copy the .example file and remove the .example extension.");
            }

            JsonNode root = OBJECT_MAPPER.readTree(is);
            return parseGenesisSettings(root);

        } catch (IOException e) {
            throw new IllegalStateException("Failed to load genesis configuration from: " + fileName, e);
        }
    }

    private static GenesisSettings parseGenesisSettings(JsonNode root) {
        // Size limits
        JsonNode limits = requireNode(root, "limits");
        long maxHeaderSizeInBytes = requireLong(limits, "maxHeaderSizeInBytes");
        long maxTxSizeInBytes = requireLong(limits, "maxTxSizeInBytes");
        long maxBlockSizeInBytes = requireLong(limits, "maxBlockSizeInBytes");
        long maxTxCountPerBlock = requireLong(limits, "maxTxCountPerBlock");

        // BIP configuration
        JsonNode bip = requireNode(root, "bip");
        long bipExpirationPeriodMs = requireLong(bip, "expirationPeriodMs");
        long bipApprovalThresholdBps = requireLong(bip, "approvalThresholdBps");

        // Network parameters
        JsonNode networkParams = requireNode(root, "networkParams");
        Wei blockReward = Wei.valueOf(new BigInteger(requireString(networkParams, "blockReward")));
        Address blockRewardPoolAddress = Address.fromHexString(requireString(networkParams, "blockRewardPoolAddress"));
        Wei initialMintForBlockReward = Wei
                .valueOf(new BigInteger(requireString(networkParams, "initialMintForBlockReward")));
        long targetMiningTimeMs = requireLong(networkParams, "targetMiningTimeMs");
        long asertHalfLifeBlocks = requireLong(networkParams, "asertHalfLifeBlocks");
        BigInteger minDifficulty = new BigInteger(requireString(networkParams, "minDifficulty"));
        Wei minTxBaseFee = Wei.valueOf(new BigInteger(requireString(networkParams, "minTxBaseFee")));
        Wei minTxByteFee = Wei.valueOf(new BigInteger(requireString(networkParams, "minTxByteFee")));

        // Authorities
        JsonNode authoritiesNode = requireNode(root, "authorities");
        List<Address> authorities = parseAddressList(authoritiesNode);
        Wei initialMintForAuthority = Wei
                .valueOf(new BigInteger(requireString(networkParams, "initialMintForAuthority")));

        // Genesis block
        JsonNode genesisBlock = requireNode(root, "genesisBlock");
        long genesisBlockTimestamp = requireLong(genesisBlock, "timestamp");
        BigInteger genesisBlockDifficulty = new BigInteger(requireString(genesisBlock, "difficulty"));

        // Native token
        JsonNode nativeToken = requireNode(root, "nativeToken");
        String tokenName = requireString(nativeToken, "name");
        String tokenTicker = requireString(nativeToken, "ticker");
        int tokenDecimals = requireInt(nativeToken, "decimals");
        String tokenWebsite = requireString(nativeToken, "website");
        String tokenLogo = requireString(nativeToken, "logo");
        boolean tokenUserBurnable = requireBoolean(nativeToken, "userBurnable");

        // RandomX
        JsonNode randomX = requireNode(root, "randomX");
        long randomXEpochLength = requireLong(randomX, "epochLength");
        String randomXGenesisKey = requireString(randomX, "genesisKey");
        int randomXBatchSize = requireInt(randomX, "batchSize");

        return new GenesisSettings(
                maxHeaderSizeInBytes,
                maxTxSizeInBytes,
                maxBlockSizeInBytes,
                maxTxCountPerBlock,
                bipExpirationPeriodMs,
                bipApprovalThresholdBps,
                blockReward,
                blockRewardPoolAddress,
                initialMintForBlockReward,
                targetMiningTimeMs,
                asertHalfLifeBlocks,
                minDifficulty,
                minTxBaseFee,
                minTxByteFee,
                authorities,
                initialMintForAuthority,
                genesisBlockTimestamp,
                genesisBlockDifficulty,
                tokenName,
                tokenTicker,
                tokenDecimals,
                tokenWebsite,
                tokenLogo,
                tokenUserBurnable,
                randomXEpochLength,
                randomXGenesisKey,
                randomXBatchSize);
    }

    // =============================================
    // HELPER METHODS
    // =============================================

    private static JsonNode requireNode(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalStateException("Missing required field: " + fieldName);
        }
        return node;
    }

    private static String requireString(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull() || !node.isTextual()) {
            throw new IllegalStateException("Missing or invalid string field: " + fieldName);
        }
        return node.asText();
    }

    private static long requireLong(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new IllegalStateException("Missing or invalid long field: " + fieldName);
        }
        return node.asLong();
    }

    private static int requireInt(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new IllegalStateException("Missing or invalid int field: " + fieldName);
        }
        return node.asInt();
    }

    private static boolean requireBoolean(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull() || !node.isBoolean()) {
            throw new IllegalStateException("Missing or invalid boolean field: " + fieldName);
        }
        return node.asBoolean();
    }

    private static List<Address> parseAddressList(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            throw new IllegalStateException("Expected array for authorities");
        }
        return java.util.stream.StreamSupport.stream(arrayNode.spliterator(), false)
                .map(JsonNode::asText)
                .map(Address::fromHexString)
                .collect(Collectors.toList());
    }
}
