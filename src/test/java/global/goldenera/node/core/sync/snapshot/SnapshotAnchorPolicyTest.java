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
package global.goldenera.node.core.sync.snapshot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;

class SnapshotAnchorPolicyTest {

	@Test
	void trustedHttpPolicyAcceptsArbitraryKnownProductionHead() {
		assertThatCode(() -> new TrustedHttpSnapshotAnchorPolicy().verify(
				777_777, Hash.hash(Bytes.of(7)), mainnetIdentity()))
				.doesNotThrowAnyException();
	}

	@Test
	void hardcodedPolicyStillRejectsArbitraryUntrustedHeight() {
		CheckpointRegistry registry = mock(CheckpointRegistry.class);
		when(registry.isCheckpoint(777_777)).thenReturn(false);

		assertThatThrownBy(() -> new HardcodedCheckpointSnapshotAnchorPolicy(registry).verify(
				777_777, Hash.hash(Bytes.of(7)), mainnetIdentity()))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("hardcoded checkpoint");
	}

	@Test
	void trustedHttpPolicyRejectsUnknownIdentity() {
		StoredChainIdentity unknown = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 0, "mainnet", Hash.ZERO.toHexString(), null);

		assertThatThrownBy(() -> new TrustedHttpSnapshotAnchorPolicy().verify(1, Hash.ZERO, unknown))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("known production chain head");
	}

	private StoredChainIdentity mainnetIdentity() {
		return new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION,
				0,
				"mainnet",
				"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f",
				null);
	}
}
