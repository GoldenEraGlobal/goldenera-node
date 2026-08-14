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
package global.goldenera.node.core.blockchain.pow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.blockchain.crypto.RandomXManager;
import global.goldenera.node.core.blockchain.crypto.RandomXVmLease;

class RandomXProofOfWorkProviderTest {

	@Test
	void miningUsesExistingDatasetBackedRandomXManagerLifecycle() {
		RandomXManager manager = mock(RandomXManager.class);
		RandomXVmLease lease = mock(RandomXVmLease.class);
		byte[] input = { 1, 2, 3 };
		byte[] output = new byte[ProofOfWorkHasher.HASH_LENGTH_BYTES];
		when(manager.createMiningVM()).thenReturn(lease);
		when(lease.calculateHash(same(input))).thenReturn(output);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager);

		provider.prepareForMining(123L);
		try (ProofOfWorkHasher hasher = provider.openMiningHasher()) {
			assertThat(hasher.hash(input)).isSameAs(output);
		}

		verify(manager).prepareMiningResourcesForHeight(123L);
		verify(manager).createMiningVM();
		verify(lease).calculateHash(same(input));
		verify(lease).close();
	}

	@Test
	void verificationUsesExistingCacheOnlyVmAndPreservesSeedResolver() {
		RandomXManager manager = mock(RandomXManager.class);
		RandomXVmLease lease = mock(RandomXVmLease.class);
		Function<Long, Optional<byte[]>> seedResolver = height -> Optional.of(new byte[] { height.byteValue() });
		when(manager.getLightVMForVerification(456L, seedResolver)).thenReturn(lease);
		when(manager.isInitializationInProgress()).thenReturn(true);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager);

		try (ProofOfWorkHasher ignored = provider.openVerificationHasher(456L, seedResolver)) {
			assertThat(provider.isInitializationInProgress()).isTrue();
		}

		verify(manager).getLightVMForVerification(456L, seedResolver);
		verify(lease).close();
	}

	@Test
	void miningInitializationFailureIsReportedAsFatalProofOfWorkFailure() {
		RandomXManager manager = mock(RandomXManager.class);
		doThrow(new IllegalStateException("native initialization failed"))
				.when(manager).prepareMiningResourcesForHeight(789L);
		RandomXProofOfWorkProvider provider = new RandomXProofOfWorkProvider(manager);

		assertThatThrownBy(() -> provider.prepareForMining(789L))
				.isInstanceOf(ProofOfWorkMiningException.class)
				.hasMessageContaining("height 789")
				.hasRootCauseMessage("native initialization failed");
		verify(manager).prepareMiningResourcesForHeight(789L);
	}
}
