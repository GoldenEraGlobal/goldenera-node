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
package global.goldenera.node.core.sandbox.authoring;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Path;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.blockchain.genesis.SandboxGenesisPlanFactory;
import global.goldenera.node.core.sandbox.genesis.SandboxNetworkSettingsAdapter;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestLoader;
import global.goldenera.node.core.state.IsolatedWorldStateStorage;

/**
 * Explicit offline authoring seam for turning a strict draft into a verified,
 * canonical sandbox manifest. This type is not a Spring component.
 */
public final class SandboxManifestAuthoringService {

	private static final String EXPECTED_HASH_JSON_PREFIX = "\"expectedGenesisHash\":\"";

	private final SandboxManifestLoader loader;
	private final SecureSandboxManifestFiles files;

	public SandboxManifestAuthoringService() {
		this(new SandboxManifestLoader(), new SecureSandboxManifestFiles());
	}

	SandboxManifestAuthoringService(SandboxManifestLoader loader, SecureSandboxManifestFiles files) {
		this.loader = loader;
		this.files = files;
	}

	/**
	 * Authors and atomically publishes a new manifest without replacing any
	 * existing path.
	 */
	public SandboxManifestAuthoringResult author(Path draft, Path output) {
		Path checkedDraft = files.requireStrictPath(draft, "Draft manifest");
		Path checkedOutput = files.requireStrictPath(output, "Output manifest");
		if (checkedDraft.equals(checkedOutput)) {
			throw new SandboxManifestAuthoringException("Draft and output manifest paths must differ");
		}
		SandboxManifestContext draftContext = loader.loadAuthoringDraftBytes(files.readDraft(checkedDraft));
		try {
			Hash genesisHash;
			SandboxManifestContext verifiedContext;
			try (IsolatedWorldStateStorage storage =
					IsolatedWorldStateStorage.temporary("goldenera-sandbox-manifest-author-")) {
				SandboxGenesisPlanFactory genesisFactory = new SandboxGenesisPlanFactory(
						new GenesisCandidateFactory(storage.worldStateFactory()),
						new SandboxNetworkSettingsAdapter());
				genesisHash = genesisFactory.calculateGenesisHash(draftContext);
				byte[] finalBytes = replaceAuthoringPlaceholder(
						draftContext.canonicalJson(), genesisHash.toHexString());
				verifiedContext = loader.loadAuthoredManifestBytes(finalBytes);
				genesisFactory.createVerified(verifiedContext);
			}
			files.publish(checkedOutput, verifiedContext.canonicalJson());
			return new SandboxManifestAuthoringResult(
					checkedOutput,
					genesisHash.toHexString(),
					verifiedContext.fingerprint(),
					verifiedContext.canonicalJson());
		} catch (SandboxManifestAuthoringException e) {
			throw e;
		} catch (Exception e) {
			throw new SandboxManifestAuthoringException("Failed to author sandbox manifest", e);
		}
	}

	private byte[] replaceAuthoringPlaceholder(byte[] canonicalDraft, String genesisHash) {
		String source = new String(canonicalDraft, UTF_8);
		String placeholder = EXPECTED_HASH_JSON_PREFIX
				+ SandboxManifestLoader.AUTHORING_GENESIS_HASH_PLACEHOLDER + '"';
		String replacement = EXPECTED_HASH_JSON_PREFIX + genesisHash + '"';
		int first = source.indexOf(placeholder);
		if (first < 0 || source.indexOf(placeholder, first + placeholder.length()) >= 0) {
			throw new SandboxManifestAuthoringException(
					"Canonical draft must contain exactly one authoring genesis hash placeholder");
		}
		return source.replace(placeholder, replacement).getBytes(UTF_8);
	}

}
