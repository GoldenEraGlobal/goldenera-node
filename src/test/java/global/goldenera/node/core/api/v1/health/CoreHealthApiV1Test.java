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
package global.goldenera.node.core.api.v1.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import global.goldenera.node.core.node.readiness.CoreReadinessFailureReason;
import global.goldenera.node.core.node.readiness.CoreReadinessStage;
import global.goldenera.node.core.node.readiness.CoreRuntimeReadinessTracker;

class CoreHealthApiV1Test {

	private final CoreRuntimeReadinessTracker readiness = new CoreRuntimeReadinessTracker();
	private final CoreHealthApiV1 api = new CoreHealthApiV1(readiness);

	@Test
	void livenessDoesNotDependOnCoreOrExplorerState() {
		assertThat(api.live().getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(api.live().getBody()).isEqualTo(new CoreLivenessDtoV1("UP"));
	}

	@Test
	void readinessIsUnavailableWithTypedCurrentStageUntilCoreReady() {
		assertThat(api.ready().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(api.ready().getBody().stage()).isEqualTo(CoreReadinessStage.STARTING);

		readiness.chainIdentityBound();
		readiness.genesisHeadReady();
		readiness.p2pListenerBound();
		readiness.coreReady();

		assertThat(api.ready().getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(api.ready().getBody().ready()).isTrue();
		assertThat(api.ready().getBody().stage()).isEqualTo(CoreReadinessStage.CORE_READY);
	}

	@Test
	void readinessReportsTerminalFailureReason() {
		readiness.failed(CoreReadinessFailureReason.P2P_BIND);

		assertThat(api.ready().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(api.ready().getBody().reason()).isEqualTo(CoreReadinessFailureReason.P2P_BIND);
	}
}
