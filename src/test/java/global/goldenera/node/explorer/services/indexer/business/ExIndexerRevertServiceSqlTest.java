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
package global.goldenera.node.explorer.services.indexer.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

import global.goldenera.cryptoj.datatypes.Hash;

class ExIndexerRevertServiceSqlTest {

	@Test
	void partitionedDeletesAndRevertLogQueriesBindBlockHeightAfterHash() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), eq(byte[].class), anyLong())).thenReturn(new byte[32]);
		ExIndexerRevertService service = new ExIndexerRevertService(jdbcTemplate);

		service.revertBlock(Hash.ZERO, 42L);

		List<Invocation> partitionedStatements = mockingDetails(jdbcTemplate).getInvocations().stream()
				.filter(invocation -> invocation.getMethod().getName().equals("update"))
				.filter(invocation -> {
					String sql = invocation.getArgument(0);
					return sql.contains("explorer_revert_log")
							|| sql.contains("DELETE FROM explorer_transfer")
							|| sql.contains("DELETE FROM explorer_tx");
				})
				.toList();

		assertThat(partitionedStatements).isNotEmpty();
		assertThat(partitionedStatements).allSatisfy(invocation -> {
			String sql = invocation.getArgument(0);
			assertThat(sql).contains("block_hash = ?").contains("block_height = ?");
			assertThat((Object) invocation.getArgument(2)).isEqualTo(42L);
		});
	}

	@Test
	void revertingGenesisClearsExplorerStatusInsteadOfLookingUpNegativeHeight() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		ExIndexerRevertService service = new ExIndexerRevertService(jdbcTemplate);

		service.revertBlock(Hash.ZERO, 0L);

		verify(jdbcTemplate).update("DELETE FROM explorer_status WHERE id = 1");
		verify(jdbcTemplate, never()).queryForObject(anyString(), eq(byte[].class), anyLong());
	}
}
