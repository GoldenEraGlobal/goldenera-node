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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class MasterChainLockTest {

	@Test
	void actionsRunOnlyAfterTheOutermostHolderReleasesTheLock() {
		MasterChainLock lock = new MasterChainLock();
		List<Boolean> lockedWhenRun = new ArrayList<>();
		lock.lock();
		lock.lock();
		lock.afterRelease(() -> lockedWhenRun.add(lock.isLocked()));

		lock.unlock();
		assertThat(lockedWhenRun).isEmpty();

		lock.unlock();
		assertThat(lockedWhenRun).containsExactly(false);
	}

	@Test
	void failingActionDoesNotFailTheReleaseOrSkipLaterActions() {
		MasterChainLock lock = new MasterChainLock();
		List<String> executed = new ArrayList<>();
		lock.lock();
		lock.afterRelease(() -> {
			throw new IllegalStateException("listener bug");
		});
		lock.afterRelease(() -> executed.add("second"));

		assertThatCode(lock::unlock).doesNotThrowAnyException();
		assertThat(executed).containsExactly("second");
		assertThat(lock.isLocked()).isFalse();
	}

	@Test
	void errorInAnActionStillRunsLaterActionsAndIsRethrownAfterwards() {
		MasterChainLock lock = new MasterChainLock();
		List<String> executed = new ArrayList<>();
		lock.lock();
		lock.afterRelease(() -> {
			throw new AssertionError("fatal listener failure");
		});
		lock.afterRelease(() -> executed.add("second"));

		assertThatThrownBy(lock::unlock).isInstanceOf(AssertionError.class);
		assertThat(executed).containsExactly("second");
		assertThat(lock.isLocked()).isFalse();
	}

	@Test
	void actionsCommittedByDifferentThreadsAreDeliveredInCommitOrder() throws Exception {
		MasterChainLock lock = new MasterChainLock();
		List<String> delivered = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch firstDeliveryStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstDelivery = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> commit(lock, () -> {
				firstDeliveryStarted.countDown();
				awaitQuietly(releaseFirstDelivery);
				delivered.add("first");
			}));
			assertThat(firstDeliveryStarted.await(1, TimeUnit.SECONDS)).isTrue();

			executor.submit(() -> commit(lock, () -> delivered.add("second"))).get(1, TimeUnit.SECONDS);
			assertThat(delivered).isEmpty();

			releaseFirstDelivery.countDown();
			first.get(1, TimeUnit.SECONDS);
			assertThat(delivered).containsExactly("first", "second");
		} finally {
			releaseFirstDelivery.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void commitInsideAListenerIsDeliveredAfterTheCurrentAction() {
		MasterChainLock lock = new MasterChainLock();
		List<String> delivered = new ArrayList<>();

		commit(lock, () -> {
			delivered.add("outer-start");
			commit(lock, () -> delivered.add("nested"));
			delivered.add("outer-end");
		});

		assertThat(delivered).containsExactly("outer-start", "outer-end", "nested");
	}

	@Test
	void deferringWithoutHoldingTheLockIsRejected() {
		assertThatThrownBy(() -> new MasterChainLock().afterRelease(() -> {
		})).isInstanceOf(IllegalStateException.class);
	}

	private static void commit(MasterChainLock lock, Runnable afterRelease) {
		lock.lock();
		try {
			lock.afterRelease(afterRelease);
		} finally {
			lock.unlock();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
