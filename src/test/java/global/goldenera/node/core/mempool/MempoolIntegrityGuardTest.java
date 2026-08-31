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
package global.goldenera.node.core.mempool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import global.goldenera.node.core.node.readiness.CoreReadinessFailureReason;
import global.goldenera.node.core.node.readiness.CoreReadinessStage;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadinessTracker;

class MempoolIntegrityGuardTest {

	@Test
	void authoritativeRestoreFailureTerminallyFailsReadinessAndAllSubsequentAccess() {
		CoreRuntimeReadinessTracker readiness = new CoreRuntimeReadinessTracker();
		MempoolIntegrityGuard guard = new MempoolIntegrityGuard(readiness);
		IllegalStateException cause = new IllegalStateException("authoritative restore failed");

		guard.fail(cause);

		assertThat(guard.isFailed()).isTrue();
		assertThat(readiness.status().stage()).isEqualTo(CoreReadinessStage.FAILED);
		assertThat(readiness.status().failureReason()).isEqualTo(CoreReadinessFailureReason.MEMPOOL_RECOVERY);
		assertThatThrownBy(guard::requireHealthy)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("terminally unavailable")
				.hasCause(cause);
	}
}
