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
package global.goldenera.node.explorer.storage.chainidentity;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExplorerRuntimeReadiness {
	private static final Logger log = LoggerFactory.getLogger(ExplorerRuntimeReadiness.class);

	private final AtomicReference<ExplorerReadinessStatus> status = new AtomicReference<>(
			new ExplorerReadinessStatus(ExplorerReadinessState.STARTING,
					"Explorer database identity has not been verified", Instant.now()));
	private final CopyOnWriteArrayList<ExplorerReadinessListener> listeners = new CopyOnWriteArrayList<>();

	public ExplorerReadinessStatus status() {
		return status.get();
	}

	public boolean isReady() {
		return status().ready();
	}

	public void registerListener(ExplorerReadinessListener listener) {
		listeners.addIfAbsent(listener);
		listener.onReadinessChanged(status());
	}

	public void unregisterListener(ExplorerReadinessListener listener) {
		listeners.remove(listener);
	}

	public void ready() {
		update(ExplorerReadinessState.READY,
				"Explorer database identity and checkpoint state match authoritative core storage");
	}

	public void rebuilding(String detail) {
		update(ExplorerReadinessState.REBUILDING, detail);
	}

	public void failed(ExplorerReadinessState state, String detail) {
		if (state == ExplorerReadinessState.READY || state == ExplorerReadinessState.STARTING) {
			throw new IllegalArgumentException("Failure state must be terminal and not ready");
		}
		update(state, detail);
	}

	private void update(ExplorerReadinessState state, String detail) {
		ExplorerReadinessStatus updated = new ExplorerReadinessStatus(state, detail, Instant.now());
		status.set(updated);
		for (ExplorerReadinessListener listener : listeners) {
			try {
				listener.onReadinessChanged(updated);
			} catch (RuntimeException failure) {
				log.error("Explorer readiness listener failed while entering {}", state, failure);
			}
		}
	}
}
