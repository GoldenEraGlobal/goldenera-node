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
package global.goldenera.node.core.sync.snapshot.operator;

import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.SnapshotAnchorPolicy;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;
import global.goldenera.node.core.sync.snapshot.TrustedHttpSnapshotAnchorPolicy;

/** In-memory capability installed only by the live-clone child context. */
public final class LiveHeadCloneExportCapability {

	public static final String BEAN_NAME = "liveHeadCloneExportCapability";

	private final long height;
	private final Hash hash;
	private final StoredChainIdentity identity;

	private LiveHeadCloneExportCapability(long height, Hash hash, StoredChainIdentity identity) {
		this.height = height;
		this.hash = Objects.requireNonNull(hash, "hash");
		this.identity = Objects.requireNonNull(identity, "identity");
	}

	static LiveHeadCloneExportCapability from(LiveHeadCoreSnapshotClone clone) {
		Objects.requireNonNull(clone, "clone");
		return new LiveHeadCloneExportCapability(clone.height(), clone.hash(), clone.identity());
	}

	public static SnapshotAnchorPolicy select(
			ObjectProvider<LiveHeadCloneExportCapability> capabilityProvider,
			SandboxRuntimeContext runtimeContext) {
		Objects.requireNonNull(capabilityProvider, "capabilityProvider");
		LiveHeadCloneExportCapability capability = capabilityProvider.getIfAvailable();
		return capability == null
				? new TrustedHttpSnapshotAnchorPolicy()
				: capability.anchorPolicy(runtimeContext);
	}

	public SnapshotAnchorPolicy anchorPolicy(SandboxRuntimeContext runtimeContext) {
		Objects.requireNonNull(runtimeContext, "runtimeContext");
		if (!runtimeContext.isSandbox()) {
			return new TrustedHttpSnapshotAnchorPolicy();
		}
		return (height, hash, identity) -> verifySandboxClone(
				runtimeContext, height, hash, identity);
	}

	private void verifySandboxClone(
			SandboxRuntimeContext runtimeContext,
			long candidateHeight,
			Hash candidateHash,
			StoredChainIdentity candidateIdentity) {
		SandboxManifestContext manifest = runtimeContext.manifestContext().orElseThrow(() ->
				failure("Sandbox live-clone export requires a manifest context"));
		StoredChainIdentity expected = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				manifest.manifest().legacyCarrier().code(),
				manifest.manifest().chainId(),
				manifest.manifest().genesis().expectedGenesisHash(),
				manifest.fingerprint());
		if (candidateHeight != height || !hash.equals(candidateHash)
				|| !identity.equals(candidateIdentity) || !expected.equals(candidateIdentity)) {
			throw failure("Sandbox live-clone snapshot does not match its in-memory clone capability");
		}
	}

	private SnapshotVerificationException failure(String message) {
		return new SnapshotVerificationException(message);
	}
}
