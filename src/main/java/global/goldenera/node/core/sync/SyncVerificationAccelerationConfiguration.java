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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import global.goldenera.node.core.blockchain.pow.ProofOfWorkProvider;
import global.goldenera.node.core.blockchain.pow.RandomXProofOfWorkProvider;

@Configuration(proxyBeanMethods = false)
public class SyncVerificationAccelerationConfiguration {

	@Bean
	@ConditionalOnMissingBean(SyncVerificationAccelerationPolicy.class)
	SyncVerificationAccelerationPolicy syncVerificationAccelerationPolicy(
			ObjectProvider<ProofOfWorkProvider> provider) {
		return new SyncVerificationAccelerationPolicy() {
			@Override
			public void bulkCatchUpStarted(long localHeight, long targetHeight) {
				ProofOfWorkProvider selected = provider.getIfAvailable();
				if (selected instanceof RandomXProofOfWorkProvider randomX) {
					randomX.bulkCatchUpStarted(localHeight, targetHeight);
				} else if (selected instanceof SyncVerificationAccelerationPolicy policy) {
					policy.bulkCatchUpStarted(localHeight, targetHeight);
				}
			}

			@Override
			public void progress(long localHeight, long targetHeight) {
				ProofOfWorkProvider selected = provider.getIfAvailable();
				if (selected instanceof RandomXProofOfWorkProvider randomX) {
					randomX.progress(localHeight, targetHeight);
				} else if (selected instanceof SyncVerificationAccelerationPolicy policy) {
					policy.progress(localHeight, targetHeight);
				}
			}

			@Override
			public void syncEnded(EndReason reason) {
				ProofOfWorkProvider selected = provider.getIfAvailable();
				if (selected instanceof RandomXProofOfWorkProvider randomX) {
					randomX.syncEnded(reason);
				} else if (selected instanceof SyncVerificationAccelerationPolicy policy) {
					policy.syncEnded(reason);
				}
			}
		};
	}
}
