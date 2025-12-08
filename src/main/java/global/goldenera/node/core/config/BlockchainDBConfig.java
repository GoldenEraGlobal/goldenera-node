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
import org.rocksdb.PrepopulateBlobCache;
import org.rocksdb.RateLimiter;
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
@AllArgsConstructor
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class BlockchainDBConfig {

	BlockchainDbProperties props;

	@Bean
	public RocksDbColumnFamilies rocksDbColumnFamilies() {
		return new RocksDbColumnFamilies();
	}

	@Bean(destroyMethod = "close")
	@Primary
	public RocksDB blockchainDB(RocksDbColumnFamilies columnFamiliesHolder) throws RocksDBException, IOException {
		String dbPath = props.getPath();
		Files.createDirectories(Paths.get(dbPath));
		RocksDB.loadLibrary();

		// Convert MB to bytes
		final long blockCacheSize = props.getRocksdbBlockCacheMb() * 1024L * 1024L;
		final long writeBufferSize = props.getRocksdbWriteBufferMb() * 1024L * 1024L;
		final long blockSize = props.getRocksdbBlockSizeKb() * 1024L;

		log.info("Configuring RocksDB with tuned options:");
		log.info("  Block Cache: {}MB, Write Buffer: {}MB x {}",
				props.getRocksdbBlockCacheMb(), props.getRocksdbWriteBufferMb(), props.getRocksdbMaxWriteBuffers());
		log.info("  Background Jobs: {}, Direct I/O: reads={}, writes={}",
				props.getRocksdbMaxBackgroundJobs(), props.isRocksdbDirectReads(), props.isRocksdbDirectWrites());

		// ========================
		// 1. Shared Cache and Filter
		// ========================
		final Cache sharedCache = new LRUCache(blockCacheSize);
		final Filter bloomFilter = new BloomFilter(props.getRocksdbBloomFilterBits(), false);

		// ========================
		// 2. Table Config for STATE_TRIE (point lookups - smaller blocks)
		// ========================
		final BlockBasedTableConfig stateTrieTableConfig = new BlockBasedTableConfig()
				.setBlockCache(sharedCache)
				.setFilterPolicy(bloomFilter)
				.setBlockSize(blockSize) // Smaller blocks for random access
				.setCacheIndexAndFilterBlocks(true)
				.setPinL0FilterAndIndexBlocksInCache(true)
				.setFormatVersion(5);

		// ========================
		// 3. Table Config for BLOCKS (large values - larger blocks, NO bloom filter)
		// Point lookup by hash doesn't benefit from bloom filters
		// ========================
		final BlockBasedTableConfig blocksTableConfig = new BlockBasedTableConfig()
				.setBlockCache(sharedCache)
				// NO bloom filter - point lookup by exact hash, no prefix scan
				.setBlockSize(blockSize * 4) // 64KB blocks for large values
				.setCacheIndexAndFilterBlocks(true)
				.setPinL0FilterAndIndexBlocksInCache(true)
				.setFormatVersion(5);

		// ========================
		// 4. Table Config for indexes (TX_INDEX, HASH_BY_HEIGHT - point lookups)
		// ========================
		final BlockBasedTableConfig indexTableConfig = new BlockBasedTableConfig()
				.setBlockCache(sharedCache)
				.setFilterPolicy(bloomFilter)
				.setBlockSize(blockSize)
				.setCacheIndexAndFilterBlocks(true)
				.setPinL0FilterAndIndexBlocksInCache(true)
				.setFormatVersion(5);

		// ========================
		// 5. CF Options per workload type
		// ========================
		final ColumnFamilyOptions stateTrieOpts = createCfOptions(stateTrieTableConfig, writeBufferSize,
				props.getRocksdbMaxWriteBuffers());
		// Special options for CF_BLOCKS with BlobDB for large StoredBlock values (up to
		// 7MB)
		final ColumnFamilyOptions blocksOpts = createBlocksCfOptions(blocksTableConfig, writeBufferSize,
				props.getRocksdbMaxWriteBuffers());
		final ColumnFamilyOptions indexOpts = createCfOptions(indexTableConfig, writeBufferSize,
				props.getRocksdbMaxWriteBuffers());
		final ColumnFamilyOptions defaultOpts = createCfOptions(indexTableConfig, writeBufferSize,
				props.getRocksdbMaxWriteBuffers());

		// ========================
		// 6. CF Descriptors with optimized options
		// ========================
		final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
				new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_STATE_TRIE.getBytes(StandardCharsets.UTF_8),
						stateTrieOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_BLOCKS.getBytes(StandardCharsets.UTF_8),
						blocksOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_TX_INDEX.getBytes(StandardCharsets.UTF_8),
						indexOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT.getBytes(StandardCharsets.UTF_8),
						indexOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_METADATA.getBytes(StandardCharsets.UTF_8),
						defaultOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_TOKENS.getBytes(StandardCharsets.UTF_8),
						defaultOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_AUTHORITIES.getBytes(StandardCharsets.UTF_8),
						defaultOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_ENTITY_UNDO_LOG.getBytes(StandardCharsets.UTF_8),
						defaultOpts));

		// ========================
		// 7. DB Options (Global settings)
		// ========================
		final Statistics stats = new Statistics();
		final DBOptions dbOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true)
				.setMaxBackgroundJobs(props.getRocksdbMaxBackgroundJobs())
				.setBytesPerSync(1048576) // 1MB - smooth disk I/O
				.setStatistics(stats);

		// Direct I/O (Linux optimized)
		if (props.isRocksdbDirectReads()) {
			dbOptions.setUseDirectReads(true);
			log.info("  Direct I/O for reads: ENABLED");
		}
		if (props.isRocksdbDirectWrites()) {
			dbOptions.setUseDirectIoForFlushAndCompaction(true);
			log.info("  Direct I/O for writes: ENABLED");
		}

		// Rate limiter (prevents compaction from starving reads)
		if (props.getRocksdbRateLimitMbPerSec() > 0) {
			final RateLimiter rateLimiter = new RateLimiter(props.getRocksdbRateLimitMbPerSec() * 1024L * 1024L);
			dbOptions.setRateLimiter(rateLimiter);
			log.info("  Rate Limiter: {}MB/s", props.getRocksdbRateLimitMbPerSec());
		}

		// ========================
		// 8. Open Database
		// ========================
		final List<ColumnFamilyHandle> columnFamilyHandles = new ArrayList<>();

		log.info("Opening RocksDB at path: {}", dbPath);
		File dbDir = new File(dbPath);
		if (!dbDir.exists()) {
			dbDir.mkdirs();
		}

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

		log.info("RocksDB successfully opened with {} column families", columnFamilyHandles.size());

		return db;
	}

	/**
	 * Creates ColumnFamilyOptions with compression and write buffer tuning.
	 * Hybrid compression strategy: speed at top levels, space savings at bottom.
	 */
	private ColumnFamilyOptions createCfOptions(BlockBasedTableConfig tableConfig, long writeBufferSize,
			int maxWriteBuffers) {
		return new ColumnFamilyOptions()
				.setTableFormatConfig(tableConfig)
				.setLevelCompactionDynamicLevelBytes(true) // Better space amplification
				.setWriteBufferSize(writeBufferSize)
				.setMaxWriteBufferNumber(maxWriteBuffers)
				.setMinWriteBufferNumberToMerge(1)
				.setTargetFileSizeBase(writeBufferSize) // Align with write buffer
				.setMaxBytesForLevelBase(writeBufferSize * 4)
				// L0-L1: No compression (hot data, fast access)
				// L2-L3: LZ4 (balanced)
				// L4+: ZSTD (cold data, max compression)
				.setCompressionPerLevel(Arrays.asList(
						CompressionType.NO_COMPRESSION,
						CompressionType.NO_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION))
				// Compaction triggers
				.setLevel0FileNumCompactionTrigger(4)
				.setLevel0SlowdownWritesTrigger(20)
				.setLevel0StopWritesTrigger(36);
	}

	/**
	 * Creates ColumnFamilyOptions for CF_BLOCKS with BlobDB enabled.
	 * 
	 * <h3>BlobDB Optimization for Large Values:</h3>
	 * <ul>
	 * <li>Separates large values (StoredBlock up to 7MB) from LSM tree into blob
	 * files</li>
	 * <li>Reduces write amplification during compaction (only keys are compacted,
	 * not values)</li>
	 * <li>Better performance for GCloud PD SSD (sequential blob writes)</li>
	 * <li>ZSTD compression for blob files (best ratio for large data)</li>
	 * </ul>
	 */
	private ColumnFamilyOptions createBlocksCfOptions(BlockBasedTableConfig tableConfig, long writeBufferSize,
			int maxWriteBuffers) {
		final long blobMinBytes = props.getRocksdbBlobMinBytes();
		final long blobFileSize = props.getRocksdbBlobFileSizeMb() * 1024L * 1024L;

		ColumnFamilyOptions opts = new ColumnFamilyOptions()
				.setTableFormatConfig(tableConfig)
				.setLevelCompactionDynamicLevelBytes(true)
				.setWriteBufferSize(writeBufferSize)
				.setMaxWriteBufferNumber(maxWriteBuffers)
				.setMinWriteBufferNumberToMerge(1)
				.setTargetFileSizeBase(writeBufferSize)
				.setMaxBytesForLevelBase(writeBufferSize * 4)
				// L0-L1: No compression (hot data)
				// L2+: LZ4 for SST files (keys/small values only with BlobDB)
				.setCompressionPerLevel(Arrays.asList(
						CompressionType.NO_COMPRESSION,
						CompressionType.NO_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.LZ4_COMPRESSION))
				.setLevel0FileNumCompactionTrigger(4)
				.setLevel0SlowdownWritesTrigger(20)
				.setLevel0StopWritesTrigger(36);

		// Enable BlobDB for large values
		if (props.isRocksdbBlobEnabled()) {
			opts.setEnableBlobFiles(true)
					.setMinBlobSize(blobMinBytes) // Values >= 64KB go to blob files
					.setBlobFileSize(blobFileSize) // 256MB blob files
					.setBlobCompressionType(CompressionType.ZSTD_COMPRESSION) // Best compression for large data
					// Performance optimizations (best practice, not configurable)
					.setPrepopulateBlobCache(PrepopulateBlobCache.PREPOPULATE_BLOB_FLUSH_ONLY) // Cache blobs on flush
					.setBlobCompactionReadaheadSize(4 * 1024 * 1024); // 4MB read-ahead for compaction

			// Enable blob garbage collection
			if (props.isRocksdbBlobGcEnabled()) {
				opts.setEnableBlobGarbageCollection(true)
						.setBlobGarbageCollectionAgeCutoff(props.getRocksdbBlobGcAgeCutoff());
			}

			log.info(
					"  BlobDB for CF_BLOCKS: ENABLED (min={}KB, fileSize={}MB, GC={}, prepopulate=true, readahead=4MB)",
					blobMinBytes / 1024, props.getRocksdbBlobFileSizeMb(), props.isRocksdbBlobGcEnabled());
		}

		return opts;
	}
}