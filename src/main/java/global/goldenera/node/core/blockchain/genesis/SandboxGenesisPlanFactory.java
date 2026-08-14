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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory.GenesisCandidate;
import global.goldenera.node.core.sandbox.genesis.SandboxGenesisConfiguration;
import global.goldenera.node.core.sandbox.genesis.SandboxNetworkSettingsAdapter;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;

/** Builds and verifies a sandbox genesis entirely from a strict manifest. */
public final class SandboxGenesisPlanFactory {

	private static final byte[] NONCE_DOMAIN =
			"goldenera:sandbox-chain-id:v1\0".getBytes(StandardCharsets.US_ASCII);

	private final GenesisCandidateFactory candidateFactory;
	private final SandboxNetworkSettingsAdapter settingsAdapter;

	public SandboxGenesisPlanFactory(
			GenesisCandidateFactory candidateFactory,
			SandboxNetworkSettingsAdapter settingsAdapter) {
		this.candidateFactory = candidateFactory;
		this.settingsAdapter = settingsAdapter;
	}

	/**
	 * Builds the candidate in memory and verifies its hash before it can be
	 * handed to persistence integration.
	 */
	public SandboxGenesisPlan createVerified(SandboxManifestContext manifestContext) {
		SandboxGenesisPlan plan = createCandidate(manifestContext);
		Hash expected = plan.configuration().expectedGenesisHash();
		Hash actual = plan.genesisBlock().getHash();
		if (!actual.equals(expected)) {
			throw new SandboxGenesisException(
					"Sandbox genesis hash mismatch: expected " + expected + ", calculated " + actual);
		}
		return plan;
	}

	/** Calculates the deterministic candidate hash for manifest authoring tools. */
	public Hash calculateGenesisHash(SandboxManifestContext manifestContext) {
		return createCandidate(manifestContext).genesisBlock().getHash();
	}

	private SandboxGenesisPlan createCandidate(SandboxManifestContext manifestContext) {
		SandboxGenesisConfiguration configuration = settingsAdapter.adapt(manifestContext);
		GenesisCandidate candidate = candidateFactory.create(
				configuration.networkSettings(),
				deriveNonce(configuration.chainId(), configuration.genesisSeed()));
		return new SandboxGenesisPlan(configuration, candidate.worldState(), candidate.block());
	}

	private long deriveNonce(String chainId, String seed) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(NONCE_DOMAIN);
			byte[] chainIdBytes = chainId.getBytes(StandardCharsets.UTF_8);
			digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(chainIdBytes.length).array());
			digest.update(chainIdBytes);
			digest.update(HexFormat.of().parseHex(seed));
			return ByteBuffer.wrap(digest.digest()).getLong() & Long.MAX_VALUE;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}
}
