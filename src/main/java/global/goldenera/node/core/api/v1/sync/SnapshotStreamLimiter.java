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
package global.goldenera.node.core.api.v1.sync;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import global.goldenera.node.core.properties.SnapshotDistributionProperties;

/** Independent concurrency boundary for long-lived snapshot response streams. */
@Component
public final class SnapshotStreamLimiter {

	private final Semaphore permits;

	public SnapshotStreamLimiter(SnapshotDistributionProperties properties) {
		properties.validate();
		this.permits = new Semaphore(properties.getMaxConcurrentStreams(), true);
	}

	public Lease tryAcquire() {
		return permits.tryAcquire() ? new Lease(permits) : null;
	}

	public static final class Lease implements AutoCloseable {

		private final Semaphore permits;
		private final AtomicBoolean closed = new AtomicBoolean();

		private Lease(Semaphore permits) {
			this.permits = permits;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				permits.release();
			}
		}
	}
}
