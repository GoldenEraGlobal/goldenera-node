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
package global.goldenera.node.core.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;

class SyncVerificationAccelerationConfigurationTest {

	@Test
	void delegatesLifecycleOnlyWhenSelectedProviderSupportsAcceleration() {
		@SuppressWarnings("unchecked")
		ObjectProvider<ProofOfWorkProvider> provider = mock(ObjectProvider.class);
		RandomXProofOfWorkProvider randomX = mock(RandomXProofOfWorkProvider.class);
		when(provider.getIfAvailable()).thenReturn(randomX);
		SyncVerificationAccelerationPolicy policy = new SyncVerificationAccelerationConfiguration()
				.syncVerificationAccelerationPolicy(provider);

		policy.bulkCatchUpStarted(1L, 10_000L);
		policy.progress(5_000L, 10_000L);
		policy.syncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP);

		verify(randomX).bulkCatchUpStarted(1L, 10_000L);
		verify(randomX).progress(5_000L, 10_000L);
		verify(randomX).syncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP);
	}

	@Test
	void remainsNoOpForNonAcceleratingProvider() {
		@SuppressWarnings("unchecked")
		ObjectProvider<ProofOfWorkProvider> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(mock(ProofOfWorkProvider.class));
		SyncVerificationAccelerationPolicy policy = new SyncVerificationAccelerationConfiguration()
				.syncVerificationAccelerationPolicy(provider);

		policy.bulkCatchUpStarted(1L, 10_000L);
		policy.progress(5_000L, 10_000L);
		policy.syncEnded(SyncVerificationAccelerationPolicy.EndReason.CAUGHT_UP);
	}
}
