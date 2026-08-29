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
package global.goldenera.node.explorer.services.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import global.goldenera.node.explorer.config.ExplorerAsyncConfig;
import global.goldenera.node.explorer.enums.ExSearchEntityType;
import global.goldenera.node.explorer.repositories.ExAccountBalanceRepository;
import global.goldenera.node.explorer.repositories.ExAddressAliasRepository;
import global.goldenera.node.explorer.repositories.ExAuthorityRepository;
import global.goldenera.node.explorer.repositories.ExBlockHeaderRepository;
import global.goldenera.node.explorer.repositories.ExMemTransferRepository;
import global.goldenera.node.explorer.repositories.ExTokenRepository;
import global.goldenera.node.explorer.repositories.ExTxRepository;
import global.goldenera.node.explorer.repositories.ExValidatorRepository;
import global.goldenera.node.shared.exceptions.GEFailedException;
import global.goldenera.node.shared.exceptions.GEValidationException;

class ExCommonCoreServiceTest {
	private final ExBlockHeaderRepository blocks = mock(ExBlockHeaderRepository.class);
	private final ExTxRepository txs = mock(ExTxRepository.class);
	private final ExAccountBalanceRepository accounts = mock(ExAccountBalanceRepository.class);
	private final ExTokenRepository tokens = mock(ExTokenRepository.class);
	private final ExAddressAliasRepository aliases = mock(ExAddressAliasRepository.class);
	private final ExValidatorRepository validators = mock(ExValidatorRepository.class);
	private final ExAuthorityRepository authorities = mock(ExAuthorityRepository.class);
	private final ExMemTransferRepository mempool = mock(ExMemTransferRepository.class);
	private final ExplorerSearchQueryPlan queryPlan = directQueryPlan();

	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	@Test
	void escapesLikeWildcardsAndLimitsTokenResults() {
		when(tokens.searchTokens("ab\\%\\_", 25)).thenReturn(List.of());
		ExCommonCoreService service = service(Runnable::run, 1_000);

		service.search("AB%_", Set.of(ExSearchEntityType.TOKEN));

		verify(tokens).searchTokens("ab\\%\\_", 25);
	}

	@Test
	void rejectsBlankShortAndOversizedQueriesBeforeSchedulingWork() {
		ExCommonCoreService service = service(Runnable::run, 1_000);

		assertThatThrownBy(() -> service.search(" ", Set.of(ExSearchEntityType.TOKEN)))
				.isInstanceOf(GEValidationException.class);
		assertThatThrownBy(() -> service.search("%", Set.of(ExSearchEntityType.TOKEN)))
				.isInstanceOf(GEValidationException.class);
		assertThatThrownBy(() -> service.search("1".repeat(257), Set.of(ExSearchEntityType.TOKEN)))
				.isInstanceOf(GEValidationException.class);
	}

	@Test
	void repositoryFailureDoesNotInterruptTheRequestThread() {
		when(tokens.searchTokens("gold", 25)).thenThrow(new IllegalStateException("database unavailable"));
		ExCommonCoreService service = service(Runnable::run, 1_000);

		assertThatThrownBy(() -> service.search("gold", Set.of(ExSearchEntityType.TOKEN)))
				.isInstanceOf(GEFailedException.class);
		assertThat(Thread.currentThread().isInterrupted()).isFalse();
	}

	@Test
	void timesOutWhenTheBoundedExecutorCannotCompleteSearchWork() {
		List<Runnable> submitted = new java.util.ArrayList<>();
		Executor stalledExecutor = submitted::add;
		ExCommonCoreService service = service(stalledExecutor, 1);

		assertThatThrownBy(() -> service.search("gold", Set.of(ExSearchEntityType.TOKEN)))
				.isInstanceOf(GEFailedException.class)
				.hasMessageContaining("timed out");
		assertThat(submitted).allMatch(command -> ((FutureTask<?>) command).isCancelled());
	}

	@Test
	void rejectionCancelsTasksSubmittedEarlierInTheSameSearch() {
		List<Runnable> submitted = new java.util.ArrayList<>();
		AtomicInteger attempts = new AtomicInteger();
		Executor rejectSecond = command -> {
			if (attempts.getAndIncrement() > 0) {
				throw new RejectedExecutionException("full");
			}
			submitted.add(command);
		};
		ExCommonCoreService service = service(rejectSecond, 1_000);

		assertThatThrownBy(() -> service.search(
				"0x1111111111111111111111111111111111111111",
				Set.of(ExSearchEntityType.ACCOUNT)))
				.isInstanceOf(GEFailedException.class)
				.hasMessageContaining("capacity exhausted");
		assertThat(submitted).singleElement().satisfies(command ->
				assertThat(((FutureTask<?>) command).isCancelled()).isTrue());
	}

	@Test
	void springSelectsTheQualifiedProductionConstructor() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(ExBlockHeaderRepository.class, () -> blocks);
			context.registerBean(ExTxRepository.class, () -> txs);
			context.registerBean(ExAccountBalanceRepository.class, () -> accounts);
			context.registerBean(ExTokenRepository.class, () -> tokens);
			context.registerBean(ExAddressAliasRepository.class, () -> aliases);
			context.registerBean(ExValidatorRepository.class, () -> validators);
			context.registerBean(ExAuthorityRepository.class, () -> authorities);
			context.registerBean(ExMemTransferRepository.class, () -> mempool);
			context.registerBean(ExplorerSearchQueryPlan.class, () -> queryPlan);
			context.registerBean(
					ExplorerAsyncConfig.EXPLORER_SEARCH_EXECUTOR, Executor.class, () -> Runnable::run);
			context.register(ExCommonCoreService.class);

			context.refresh();

			assertThat(context.getBean(ExCommonCoreService.class)).isNotNull();
		}
	}

	private ExCommonCoreService service(Executor executor, long timeoutMillis) {
		return new ExCommonCoreService(
				blocks, txs, accounts, tokens, aliases, validators, authorities, mempool,
				queryPlan, executor, timeoutMillis);
	}

	private ExplorerSearchQueryPlan directQueryPlan() {
		ExplorerSearchQueryPlan plan = mock(ExplorerSearchQueryPlan.class);
		when(plan.execute(anyLong(), any())).thenAnswer(invocation ->
				((Supplier<?>) invocation.getArgument(1)).get());
		return plan;
	}
}
