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
package global.goldenera.node.core.node.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CoreRuntimeReadinessTrackerTest {

	@Test
	void advancesThroughEveryStageAndOnlyThenBecomesReady() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.register(CoreRuntimeReadinessTracker.class);
			context.refresh();
			CoreRuntimeReadiness readiness = context.getBean(CoreRuntimeReadiness.class);

			CoreRuntimeReadinessTracker tracker = context.getBean(CoreRuntimeReadinessTracker.class);
			assertThat(readiness.status().stage()).isEqualTo(CoreReadinessStage.STARTING);
			tracker.chainIdentityBound();
			tracker.genesisHeadReady();
			tracker.p2pListenerBound();
			assertThat(readiness.isReady()).isFalse();
			tracker.coreReady();
			tracker.coreReady();

			assertThat(readiness.isReady()).isTrue();
			assertThat(readiness.status().stage()).isEqualTo(CoreReadinessStage.CORE_READY);
			assertThat(context.getBeansOfType(CoreRuntimeReadiness.class)).hasSize(1);
		}
	}

	@Test
	void rejectsSkippedOrBackwardTransitions() {
		CoreRuntimeReadinessTracker tracker = new CoreRuntimeReadinessTracker();

		assertThatThrownBy(tracker::genesisHeadReady)
				.isInstanceOf(IllegalStateException.class);
		tracker.chainIdentityBound();
		assertThatThrownBy(tracker::coreReady)
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> tracker.failed(null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void failureIsTerminalAndKeepsTheFirstTypedReason() {
		CoreRuntimeReadinessTracker tracker = new CoreRuntimeReadinessTracker();
		tracker.chainIdentityBound();
		tracker.failed(CoreReadinessFailureReason.GENESIS_HEAD);
		tracker.failed(CoreReadinessFailureReason.P2P_BIND);

		assertThat(tracker.status().stage()).isEqualTo(CoreReadinessStage.FAILED);
		assertThat(tracker.status().failureReason()).isEqualTo(CoreReadinessFailureReason.GENESIS_HEAD);
		assertThat(tracker.isReady()).isFalse();
		assertThatThrownBy(tracker::genesisHeadReady)
				.isInstanceOf(IllegalStateException.class);
	}
}
