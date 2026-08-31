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
package global.goldenera.node.shared.services.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionalWakeupTest {

	@AfterEach
	void clearTransactionState() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void runsImmediatelyWithoutTransaction() {
		AtomicInteger wakeups = new AtomicInteger();

		TransactionalWakeup.afterCommit(wakeups::incrementAndGet);

		assertThat(wakeups).hasValue(1);
	}

	@Test
	void defersWakeUntilTransactionCommit() {
		AtomicInteger wakeups = new AtomicInteger();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();

		TransactionalWakeup.afterCommit(wakeups::incrementAndGet);

		assertThat(wakeups).hasValue(0);
		List<TransactionSynchronization> synchronizations =
				TransactionSynchronizationManager.getSynchronizations();
		assertThat(synchronizations).hasSize(1);
		synchronizations.forEach(TransactionSynchronization::afterCommit);
		assertThat(wakeups).hasValue(1);
		synchronizations.forEach(synchronization -> synchronization.afterCompletion(0));
	}

	@Test
	void bindsEachWakeupToTheCurrentTransactionSynchronization() {
		AtomicInteger wakeups = new AtomicInteger();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();

		TransactionalWakeup.afterCommit(wakeups::incrementAndGet);
		TransactionalWakeup.afterCommit(wakeups::incrementAndGet);

		List<TransactionSynchronization> synchronizations =
				TransactionSynchronizationManager.getSynchronizations();
		assertThat(synchronizations).hasSize(2);
		synchronizations.forEach(TransactionSynchronization::afterCommit);
		assertThat(wakeups).hasValue(2);
		synchronizations.forEach(synchronization -> synchronization.afterCompletion(0));
	}

	@Test
	void oneFailingWakeupDoesNotPreventOtherCommittedConsumers() {
		AtomicInteger successfulWakeups = new AtomicInteger();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
		TransactionalWakeup.afterCommit(() -> {
			throw new IllegalStateException("consumer stopped");
		});
		TransactionalWakeup.afterCommit(successfulWakeups::incrementAndGet);
		List<TransactionSynchronization> synchronizations =
				TransactionSynchronizationManager.getSynchronizations();

		assertThatCode(() -> synchronizations.forEach(TransactionSynchronization::afterCommit))
				.doesNotThrowAnyException();
		assertThat(successfulWakeups).hasValue(1);
		synchronizations.forEach(synchronization -> synchronization.afterCompletion(0));
	}
}
