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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;

/** Fully adapted sandbox genesis inputs. No classpath defaults are represented. */
public record SandboxGenesisConfiguration(
		String chainId,
		String manifestFingerprint,
		String genesisSeed,
		Hash expectedGenesisHash,
		Network legacyCarrier,
		GenesisSettings genesisSettings,
		NetworkSettings networkSettings,
		Map<Address, Wei> initialBalances) {

	public SandboxGenesisConfiguration {
		chainId = Objects.requireNonNull(chainId, "chainId");
		manifestFingerprint = Objects.requireNonNull(manifestFingerprint, "manifestFingerprint");
		genesisSeed = Objects.requireNonNull(genesisSeed, "genesisSeed");
		expectedGenesisHash = Objects.requireNonNull(expectedGenesisHash, "expectedGenesisHash");
		legacyCarrier = Objects.requireNonNull(legacyCarrier, "legacyCarrier");
		genesisSettings = Objects.requireNonNull(genesisSettings, "genesisSettings");
		networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
		initialBalances = Collections.unmodifiableMap(new LinkedHashMap<>(initialBalances));
	}
}
