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

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes one search branch with a PostgreSQL-local statement timeout. */
@Component
public class ExplorerSearchQueryPlan {

	private static final String SET_STATEMENT_TIMEOUT =
			"SELECT set_config('statement_timeout', ?, true)";

	private final PlatformTransactionManager transactionManager;
	private final JdbcTemplate jdbcTemplate;

	public ExplorerSearchQueryPlan(
			PlatformTransactionManager transactionManager,
			JdbcTemplate jdbcTemplate) {
		this.transactionManager = transactionManager;
		this.jdbcTemplate = jdbcTemplate;
	}

	public <T> T execute(long timeoutMillis, Supplier<T> query) {
		if (timeoutMillis < 1L) {
			throw new IllegalArgumentException("Search statement timeout must be positive");
		}
		Objects.requireNonNull(query, "query");
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setReadOnly(true);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.setTimeout(Math.toIntExact(Math.max(1L,
				TimeUnit.MILLISECONDS.toSeconds(timeoutMillis - 1L) + 1L)));
		return transaction.execute(status -> {
			jdbcTemplate.queryForObject(
					SET_STATEMENT_TIMEOUT,
					String.class,
					timeoutMillis + "ms");
			return query.get();
		});
	}
}
