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

import static lombok.AccessLevel.PRIVATE;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.stereotype.Service;

import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class RocksDbMemoryMetricsService {

	RocksDB database;
	RocksDbColumnFamilies columnFamilies;
	MeterRegistry registry;

	@PostConstruct
	public void registerMetrics() {
		registerAggregated("blockchain.rocksdb.memtable.bytes", "rocksdb.cur-size-all-mem-tables");
		registerAggregated("blockchain.rocksdb.table_readers.bytes", "rocksdb.estimate-table-readers-mem");
		registerForFamily("blockchain.rocksdb.block_cache.bytes", columnFamilies.blocks(),
				"rocksdb.block-cache-usage");
		registerForFamily("blockchain.rocksdb.block_cache_pinned.bytes", columnFamilies.blocks(),
				"rocksdb.block-cache-pinned-usage");
		registerAggregated("blockchain.rocksdb.running_flushes", "rocksdb.num-running-flushes");
		registerAggregated("blockchain.rocksdb.running_compactions", "rocksdb.num-running-compactions");
	}

	private void registerAggregated(String metricName, String propertyName) {
		registry.gauge(metricName, database, ignored -> readAggregated(propertyName));
	}

	private void registerForFamily(String metricName, ColumnFamilyHandle family, String propertyName) {
		registry.gauge(metricName, database, ignored -> readForFamily(family, propertyName));
	}

	private double readAggregated(String propertyName) {
		try {
			return database.getAggregatedLongProperty(propertyName);
		} catch (RocksDBException | RuntimeException failure) {
			log.debug("Unable to read RocksDB property {}", propertyName, failure);
			return Double.NaN;
		}
	}

	private double readForFamily(ColumnFamilyHandle family, String propertyName) {
		try {
			return database.getLongProperty(family, propertyName);
		} catch (RocksDBException | RuntimeException failure) {
			log.debug("Unable to read RocksDB property {}", propertyName, failure);
			return Double.NaN;
		}
	}
}
