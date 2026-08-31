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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.sandbox.manifest.SandboxManifest;
import global.goldenera.node.core.sandbox.manifest.SandboxManifestContext;
import global.goldenera.node.core.sandbox.runtime.ExecutionDomain;
import global.goldenera.node.core.sandbox.runtime.SandboxRuntimeContext;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.SnapshotAnchorPolicy;

class LiveHeadCloneExportCapabilityTest {

	private static final Hash HASH = Hash.hash(Bytes.of(7));
	private static final String GENESIS =
			"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String FINGERPRINT = "b".repeat(64);

	@TempDir
	Path temporaryDirectory;

	@Test
	void exactInMemoryCloneCapabilityAllowsOnlyItsManifestBoundSandboxAnchor() {
		StoredChainIdentity identity = sandboxIdentity();
		LiveHeadCoreSnapshotClone clone = new LiveHeadCoreSnapshotClone(
				temporaryDirectory, 3, HASH, Hash.ZERO, BigInteger.ONE, identity);
		LiveHeadCloneExportCapability capability = LiveHeadCloneExportCapability.from(clone);
		SnapshotAnchorPolicy policy = capability.anchorPolicy(sandboxRuntime());

		policy.verify(3, HASH, identity);

		assertThatThrownBy(() -> policy.verify(4, HASH, identity))
				.hasMessageContaining("in-memory clone capability");
		assertThatThrownBy(() -> policy.verify(3, Hash.ZERO, identity))
				.hasMessageContaining("in-memory clone capability");
	}

	@Test
	void externallyConfiguredCloneFlagCannotReplaceTheInMemoryCapability() {
		@SuppressWarnings("unchecked")
		ObjectProvider<LiveHeadCloneExportCapability> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);
		SnapshotAnchorPolicy policy = LiveHeadCloneExportCapability.select(provider, sandboxRuntime());

		assertThatThrownBy(() -> policy.verify(3, HASH, sandboxIdentity()))
				.hasMessageContaining("known production chain head");
	}

	private SandboxRuntimeContext sandboxRuntime() {
		SandboxManifestContext context = mock(SandboxManifestContext.class);
		SandboxManifest manifest = mock(SandboxManifest.class);
		SandboxManifest.LegacyCarrier carrier = new SandboxManifest.LegacyCarrier("TESTNET", 1);
		SandboxManifest.Genesis genesis = mock(SandboxManifest.Genesis.class);
		when(genesis.expectedGenesisHash()).thenReturn(GENESIS);
		when(manifest.chainId()).thenReturn("sandbox-capability-test");
		when(manifest.legacyCarrier()).thenReturn(carrier);
		when(manifest.genesis()).thenReturn(genesis);
		when(context.manifest()).thenReturn(manifest);
		when(context.fingerprint()).thenReturn(FINGERPRINT);
		return new SandboxRuntimeContext(ExecutionDomain.SANDBOX, Network.TESTNET, Optional.of(context));
	}

	private StoredChainIdentity sandboxIdentity() {
		return new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				1,
				"sandbox-capability-test",
				GENESIS,
				FINGERPRINT);
	}
}
