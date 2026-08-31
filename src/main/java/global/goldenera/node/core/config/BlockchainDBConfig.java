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
package global.goldenera.node.core.config;

import static lombok.AccessLevel.PRIVATE;

import java.io.IOException;
import java.nio.file.Path;

import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.sync.snapshot.bootstrap.CoreSnapshotPreOpenInitializer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

/**
 * RocksDB configuration with production-grade tuning.
 * All settings are externalized to BlockchainDbProperties for runtime
 * configuration.
 * 
 * <h3>Optimizations applied:</h3>
 * <ul>
 * <li>BlockBasedTableConfig with Bloom Filters (crucial for State Trie random
 * reads)</li>
 * <li>LRU Cache for blocks to reduce IOPS</li>
 * <li>Hybrid compression: NO_COMPRESSION at L0-L1 for speed, LZ4 at L2-L3, ZSTD
 * at L4+ for space</li>
 * <li>Per-CF optimization: STATE_TRIE uses smaller blocks for point lookups,
 * BLOCKS uses larger blocks</li>
 * <li>Direct I/O to bypass OS page cache (Linux optimized)</li>
 * <li>Rate limiter to prevent compaction from starving reads</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockchainDBConfig {

	BlockchainDbProperties props;
	@NonFinal
	RocksDB openedDatabase;
	@NonFinal
	RocksDbColumnFamilies openedColumnFamilies;

	@Bean
	public RocksDbColumnFamilies rocksDbColumnFamilies() {
		return new RocksDbColumnFamilies();
	}

	@Bean(destroyMethod = "")
	@Primary
	public RocksDB blockchainDB(
			RocksDbColumnFamilies columnFamiliesHolder,
			CoreSnapshotPreOpenInitializer snapshotPreOpenInitializer)
			throws RocksDBException, IOException {
		openedColumnFamilies = columnFamiliesHolder;
		openedDatabase = new BlockchainRocksDbFactory(props).open(Path.of(props.getPath()), columnFamiliesHolder);
		return openedDatabase;
	}

	@PreDestroy
	public void close() {
		if (openedColumnFamilies != null) {
			openedColumnFamilies.close();
			openedColumnFamilies = null;
		}
		if (openedDatabase != null) {
			openedDatabase.close();
			openedDatabase = null;
		}
	}
}
