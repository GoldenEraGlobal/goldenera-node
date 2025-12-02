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
import java.util.Map;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.utils.Amounts;
import global.goldenera.node.shared.utils.VersionUtil;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {

    // Node config
    public static final String NODE_VERSION = VersionUtil.getApplicationVersion();
    public static final long P2P_PROTOCOL_VERSION = 1;

    // Directory config
    public static final String DIRECTORY_HOST = "https://directory.goldenera.global";
    public static final Address DIRECTORY_IDENTITY_ADDRESS = Address
            .fromHexString("0xecb9d8f3e8b3f6f961065f4d942df8a2bedec2f4");

    // General config
    public static final long MAX_HEADER_SIZE = 600; // 600 bytes
    public static final long MAX_TX_SIZE_IN_BYTES = 100_000; // 100KB
    public static final long MAX_BLOCK_SIZE_IN_BYTES = 5_000_000; // 5MB
    public static final long BIP_EXPIRATION_PERIOD_MS = 86400000; // 24 hours
    public static final long BIP_APPROVAL_THRESHOLD_BPS = 5100; // 51%

    // Network parameters
    public static final Wei GENESIS_NETWORK_BLOCK_REWARD = Amounts.tokens(1);
    public static final Address GENESIS_NETWORK_BLOCK_REWARD_POOL_ADDRESS = Address
            .fromHexString("0x4B5C9d7CcB07466d658c2a67740b2D3D80D3CA76");
    public static final long GENESIS_NETWORK_TARGET_MINING_TIME_MS = 30000; // 30 seconds
    public static final long GENESIS_NETWORK_ASERT_HALF_LIFE_BLOCKS = 20;
    public static final BigInteger GENESIS_NETWORK_MIN_DIFFICULTY = BigInteger.valueOf(800);
    public static final Wei GENESIS_NETWORK_MIN_TX_BASE_FEE = Amounts.tokensDecimal("0.0001");
    public static final Wei GENESIS_NETWORK_MIN_TX_BYTE_FEE = Amounts.tokensDecimal("0.000001");

    // Genesis authorities
    public static final List<Address> GENESIS_AUTHORITY_ADDRESSES = List.of(
            Address.fromHexString("0x4B5C9d7CcB07466d658c2a67740b2D3D80D3CA76"));

    // Block
    public static final long GENESIS_BLOCK_TIMESTAMP = 1764710290434L;
    public static final BigInteger GENESIS_BLOCK_DIFFICULTY = GENESIS_NETWORK_MIN_DIFFICULTY;

    // Native token
    public static final String GENESIS_NATIVE_TOKEN_NAME = "GoldenEraCoin";
    public static final String GENESIS_NATIVE_TOKEN_TICKER = "GEC";
    public static final int GENESIS_NATIVE_TOKEN_DECIMALS = 9;
    public static final String GENESIS_NATIVE_TOKEN_WEBSITE = "https://goldenera.global";
    public static final String GENESIS_NATIVE_TOKEN_LOGO = "https://goldenera.global/TokenTicker/logo.png";
    public static final boolean GENESIS_NATIVE_TOKEN_USER_BURNABLE = false;

    // Fork config
    public static final Map<ForkName, Long> FORK_ACTIVATION_BLOCKS = Map.of(
            ForkName.GENESIS, 0L);

    // Checkpoints
    public static final Map<Long, Hash> BLOCK_CHECKPOINTS = Map.of();

    // RandomX
    public static final long RANDOMX_EPOCH_LENGTH = 2048;
    public static final String RANDOMX_GENESIS_KEY = "GLDNR_RNDX_GENESIS_KEY";
    public static final int RANDOMX_BATCH_SIZE = 32768;

    public enum ForkName {
        GENESIS;
    }

    public static boolean isForkActive(ForkName fork, long blockHeight) {
        Long activationHeight = FORK_ACTIVATION_BLOCKS.get(fork);
        if (activationHeight == null) {
            return false;
        }
        return blockHeight >= activationHeight;
    }
}