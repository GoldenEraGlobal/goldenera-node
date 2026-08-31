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

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/** Fail-closed process-local view of successful core bootstrap completion. */
@Component
public final class CoreRuntimeReadinessTracker implements CoreRuntimeReadiness {

	private final AtomicReference<CoreReadinessStatus> status = new AtomicReference<>(
			CoreReadinessStatus.starting());

	@Override
	public boolean isReady() {
		return status.get().ready();
	}

	@Override
	public CoreReadinessStatus status() {
		return status.get();
	}

	public void chainIdentityBound() {
		advance(CoreReadinessStage.CHAIN_IDENTITY_BOUND);
	}

	public void genesisHeadReady() {
		advance(CoreReadinessStage.GENESIS_HEAD_READY);
	}

	public void p2pListenerBound() {
		advance(CoreReadinessStage.P2P_LISTENER_BOUND);
	}

	public void coreReady() {
		advance(CoreReadinessStage.CORE_READY);
	}

	public void failed(CoreReadinessFailureReason reason) {
		Objects.requireNonNull(reason, "reason");
		while (true) {
			CoreReadinessStatus current = status.get();
			if (current.stage() == CoreReadinessStage.FAILED) {
				return;
			}
			CoreReadinessStatus failed = new CoreReadinessStatus(
					CoreReadinessStage.FAILED, reason, Instant.now());
			if (status.compareAndSet(current, failed)) {
				return;
			}
		}
	}

	private void advance(CoreReadinessStage requested) {
		while (true) {
			CoreReadinessStatus current = status.get();
			if (current.stage() == requested) {
				return;
			}
			if (current.stage() == CoreReadinessStage.FAILED) {
				throw new IllegalStateException("Core readiness is terminally failed");
			}
			if (requested.ordinal() != current.stage().ordinal() + 1) {
				throw new IllegalStateException("Invalid core readiness transition from "
						+ current.stage() + " to " + requested);
			}
			CoreReadinessStatus next = new CoreReadinessStatus(requested, null, Instant.now());
			if (status.compareAndSet(current, next)) {
				return;
			}
		}
	}
}
