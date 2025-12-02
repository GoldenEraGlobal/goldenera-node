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
package global.goldenera.node.shared.config;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BipVoteType;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.cryptoj.enums.TxPayloadType;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.cryptoj.enums.TxVersion;
import global.goldenera.node.shared.enums.state.AccountBalanceStateVersion;
import global.goldenera.node.shared.enums.state.AccountNonceStateVersion;
import global.goldenera.node.shared.enums.state.AddressAliasStateVersion;
import global.goldenera.node.shared.enums.state.AuthorityStateVersion;
import global.goldenera.node.shared.enums.state.BipStateMetadataVersion;
import global.goldenera.node.shared.enums.state.BipStateVersion;
import global.goldenera.node.shared.enums.state.NetworkParamsStateVersion;
import global.goldenera.node.shared.enums.state.TokenStateVersion;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public class GlobalTypeRegistry {

    public static final Set<Class<? extends Enum<?>>> CODE_ENUMS = Set.of(
            BipVoteType.class,
            BlockVersion.class,
            TxVersion.class,
            Network.class,
            TxPayloadType.class,
            TxType.class,
            AccountBalanceStateVersion.class,
            AccountNonceStateVersion.class,
            AddressAliasStateVersion.class,
            AuthorityStateVersion.class,
            BipStateMetadataVersion.class,
            BipStateVersion.class,
            NetworkParamsStateVersion.class,
            TokenStateVersion.class);

    @Getter
    @RequiredArgsConstructor
    public static class StringTypeAdapter<T> {
        private final Class<T> type;
        private final Function<String, T> fromString;
        private final Function<T, String> toString;
    }

    public static final List<StringTypeAdapter<?>> STRING_ADAPTERS = List.of(
            // 1. Wei (Decimal String)
            new StringTypeAdapter<>(Wei.class,
                    s -> (s == null || s.isBlank()) ? null : Wei.valueOf(new BigInteger(s)),
                    Wei::toDecimalString),

            // 2. Hash (Hex String)
            new StringTypeAdapter<>(Hash.class,
                    Hash::fromHexString,
                    Hash::toHexString),

            // 3. Address (Hex String)
            new StringTypeAdapter<>(Address.class,
                    Address::fromHexString,
                    Address::toChecksumAddress),

            // 4. Signature (Hex String Wrapped)
            new StringTypeAdapter<>(Signature.class,
                    s -> Signature.wrap(Bytes.fromHexString(s)),
                    Signature::toHexString),

            // 5. Bytes (Hex String)
            new StringTypeAdapter<>(Bytes.class,
                    Bytes::fromHexString,
                    Bytes::toHexString),

            // 6. Bytes32 (Hex String)
            new StringTypeAdapter<>(Bytes32.class,
                    Bytes32::fromHexString,
                    Bytes32::toHexString));
}