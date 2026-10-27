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
package global.goldenera.node.core.node;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;

// A thread never runs chain lifecycle listeners inside its own hold of the chain lock, even a nested one, and
// listeners see changes in commit order even when different threads commit them. Delivery may run on another
// committer's thread, so an Error thrown by a listener surfaces on that thread.
@Slf4j
public class MasterChainLock extends ReentrantLock {

	// Filled only by the lock holder, so the queue order is the commit order.
	private final Queue<Runnable> afterRelease = new ConcurrentLinkedQueue<>();
	private final ReentrantLock delivery = new ReentrantLock();

	public void afterRelease(Runnable action) {
		if (!isHeldByCurrentThread()) {
			throw new IllegalStateException("Master chain lock is not held by the current thread");
		}
		afterRelease.add(action);
	}

	@Override
	public void unlock() {
		boolean outermost = getHoldCount() == 1;
		super.unlock();
		if (outermost) {
			runAfterReleaseActions();
		}
	}

	// One thread delivers at a time; a thread that finds delivery busy leaves its actions to that deliverer,
	// which re-checks the queue after it stops delivering. A listener that commits again is drained by its own
	// outer delivery loop, so later events never overtake the event being delivered.
	private void runAfterReleaseActions() {
		if (delivery.isHeldByCurrentThread()) {
			return;
		}
		Error fatal = null;
		while (!afterRelease.isEmpty() && delivery.tryLock()) {
			try {
				for (Runnable action; (action = afterRelease.poll()) != null;) {
					try {
						action.run();
					} catch (RuntimeException failure) {
						// The chain change is already committed; a failing listener must not fail its caller.
						log.error("Chain lifecycle action failed after the master chain lock was released", failure);
					} catch (Error failure) {
						if (fatal == null) {
							fatal = failure;
						} else {
							fatal.addSuppressed(failure);
						}
					}
				}
			} finally {
				delivery.unlock();
			}
		}
		if (fatal != null) {
			throw fatal;
		}
	}
}
