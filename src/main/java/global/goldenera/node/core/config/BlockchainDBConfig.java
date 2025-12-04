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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.Filter;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Statistics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Configuration(proxyBeanMethods = false)
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockchainDBConfig {

	BlockchainDbProperties blockchainDbProperties;

	// Define constants for tuning
	private static final long BLOCK_CACHE_SIZE = 512 * 1024 * 1024L; // 512MB Shared Cache
	private static final long WRITE_BUFFER_SIZE = 64 * 1024 * 1024L; // 64MB Memtable
	private static final int MAX_WRITE_BUFFERS = 4;
	private static final int MAX_BACKGROUND_JOBS = 6; // Parallelism for flushing/compaction

	@Bean
	public RocksDbColumnFamilies rocksDbColumnFamilies() {
		return new RocksDbColumnFamilies();
	}

	/**
	 * Configures and opens the main RocksDB instance with production-grade tuning.
	 * <p>
	 * Optimizations applied:
	 * 1. BlockBasedTableConfig with Bloom Filters (crucial for State Trie random
	 * reads).
	 * 2. LRU Cache for blocks to reduce IOPS.
	 * 3. ZSTD compression for lower levels to save disk space without sacrificing
	 * much read speed.
	 * 4. Increased write buffers for better ingestion throughput during sync.
	 */
	@Bean(destroyMethod = "close")
	@Primary
	public RocksDB blockchainDB(RocksDbColumnFamilies columnFamiliesHolder) throws RocksDBException, IOException {
		String dbPath = blockchainDbProperties.getPath();
		Files.createDirectories(Paths.get(dbPath));
		RocksDB.loadLibrary();

		log.info("Configuring RocksDB with High-Performance options...");

		// 1. Setup Shared Cache and Filter Policy
		final Cache sharedCache = new LRUCache(BLOCK_CACHE_SIZE);
		final Filter bloomFilter = new BloomFilter(10, false); // 10 bits per key

		final BlockBasedTableConfig tableOptions = new BlockBasedTableConfig()
				.setBlockCache(sharedCache)
				.setFilterPolicy(bloomFilter)
				.setBlockSize(16 * 1024L) // 16KB blocks are generally better for Point Lookups (State Trie)
				.setCacheIndexAndFilterBlocks(true) // Cache indices/filters in heap to avoid disk seeks
				.setPinL0FilterAndIndexBlocksInCache(true) // Pin L0 to avoid cache trashing
				.setFormatVersion(5);

		// 2. Define General Column Family Options
		// We use a provider method/logic to create options to ensure we don't reuse
		// closed objects if restart happens,
		// though typically this runs once.
		final ColumnFamilyOptions defaultCfOpts = createAdvancedOptions(tableOptions);

		// 3. Define CF Descriptors
		// Note: For CF_BLOCKS (large data), we might ideally want larger block sizes,
		// but sharing the table config is safer for memory management.
		final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
				new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultCfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_STATE_TRIE.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts), // Trie needs fast random access -> Bloom Filters are critical here
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_BLOCKS.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_TX_INDEX.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_METADATA.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_TOKENS.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_AUTHORITIES.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_ENTITY_UNDO_LOG.getBytes(StandardCharsets.UTF_8),
						defaultCfOpts));

		// 4. DB Options (Global settings)
		final Statistics stats = new Statistics();
		final DBOptions dbOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true)
				.setMaxBackgroundJobs(MAX_BACKGROUND_JOBS) // Increase parallel compactions
				.setBytesPerSync(1048576) // Smooth out disk I/O on Linux
				.setStatistics(stats);

		final List<ColumnFamilyHandle> columnFamilyHandles = new ArrayList<>();

		log.info("Opening RocksDB at path: {}", dbPath);
		File dbDir = new File(dbPath);
		if (!dbDir.exists()) {
			dbDir.mkdirs();
		}

		// Open the DB
		RocksDB db = RocksDB.open(dbOptions, dbDir.getAbsolutePath(), cfDescriptors, columnFamilyHandles);

		log.info("Populating ColumnFamily handles...");
		columnFamiliesHolder.addHandle("default", columnFamilyHandles.get(0));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_STATE_TRIE, columnFamilyHandles.get(1));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_BLOCKS, columnFamilyHandles.get(2));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_TX_INDEX, columnFamilyHandles.get(3));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT, columnFamilyHandles.get(4));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_METADATA, columnFamilyHandles.get(5));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_TOKENS, columnFamilyHandles.get(6));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_AUTHORITIES, columnFamilyHandles.get(7));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_ENTITY_UNDO_LOG, columnFamilyHandles.get(8));

		log.info("RocksDB successfully opened. Stats available: {}", (stats != null));

		return db;
	}

	/**
	 * Creates a ColumnFamilyOptions instance with compression and write buffer
	 * tuning.
	 */
	private ColumnFamilyOptions createAdvancedOptions(BlockBasedTableConfig tableConfig) {
		return new ColumnFamilyOptions()
				.setTableFormatConfig(tableConfig)
				.setLevelCompactionDynamicLevelBytes(true) // Helps with space amplification
				.setWriteBufferSize(WRITE_BUFFER_SIZE)
				.setMaxWriteBufferNumber(MAX_WRITE_BUFFERS)
				.setMinWriteBufferNumberToMerge(1)
				.setTargetFileSizeBase(WRITE_BUFFER_SIZE) // Align with write buffer
				.setMaxBytesForLevelBase(WRITE_BUFFER_SIZE * 4)
				// Hybrid Compression: Speed at L0-L1, Space at L2+
				.setCompressionPerLevel(Arrays.asList(
						CompressionType.NO_COMPRESSION,
						CompressionType.NO_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION, // High compression for older data
						CompressionType.ZSTD_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION));
	}
}