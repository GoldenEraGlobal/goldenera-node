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

import static lombok.AccessLevel.PRIVATE;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ExIndexerPartitionService {

	static final long PARTITION_SIZE = 1_000_000;

	static final List<String> PARTITIONED_TABLES = List.of(
			"explorer_tx",
			"explorer_transfer",
			"explorer_revert_log");

	JdbcTemplate jdbcTemplate;
	AtomicLong lastEnsuredPartitionIndex = new AtomicLong(-1L);

	@Transactional
	public synchronized void ensurePartitionsExist(long currentHeight) {
		long currentPartitionIndex = currentHeight / PARTITION_SIZE;
		if (currentPartitionIndex <= lastEnsuredPartitionIndex.get()) {
			return;
		}
		createPartitionsForIndex(currentPartitionIndex);
		createPartitionsForIndex(currentPartitionIndex + 1);
		lastEnsuredPartitionIndex.set(currentPartitionIndex);
	}

	private void createPartitionsForIndex(long partitionIndex) {
		long start = partitionIndex * PARTITION_SIZE;
		long end = start + PARTITION_SIZE;

		for (String tableName : PARTITIONED_TABLES) {
			String partitionName = String.format("%s_p%d", tableName, partitionIndex);

			String sql = String.format(
					"CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM (%d) TO (%d)",
					partitionName, tableName, start, end);

			jdbcTemplate.execute(sql);
		}
	}
}
