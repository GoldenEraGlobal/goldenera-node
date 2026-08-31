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
package global.goldenera.node.core.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RocksDbMemoryMetricsServiceTest {

	@Test
	void exposesNativeMemoryPropertiesWithoutMultiplyingSharedBlockCache() throws Exception {
		RocksDB database = mock(RocksDB.class);
		RocksDbColumnFamilies families = mock(RocksDbColumnFamilies.class);
		ColumnFamilyHandle blocks = mock(ColumnFamilyHandle.class);
		when(families.blocks()).thenReturn(blocks);
		when(database.getAggregatedLongProperty("rocksdb.cur-size-all-mem-tables")).thenReturn(1234L);
		when(database.getAggregatedLongProperty("rocksdb.estimate-table-readers-mem")).thenReturn(2345L);
		when(database.getLongProperty(blocks, "rocksdb.block-cache-usage")).thenReturn(3456L);
		when(database.getLongProperty(blocks, "rocksdb.block-cache-pinned-usage")).thenReturn(4567L);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new RocksDbMemoryMetricsService(database, families, registry).registerMetrics();

		assertThat(registry.get("blockchain.rocksdb.memtable.bytes").gauge().value()).isEqualTo(1234.0);
		assertThat(registry.get("blockchain.rocksdb.table_readers.bytes").gauge().value()).isEqualTo(2345.0);
		assertThat(registry.get("blockchain.rocksdb.block_cache.bytes").gauge().value()).isEqualTo(3456.0);
		assertThat(registry.get("blockchain.rocksdb.block_cache_pinned.bytes").gauge().value()).isEqualTo(4567.0);
	}
}
