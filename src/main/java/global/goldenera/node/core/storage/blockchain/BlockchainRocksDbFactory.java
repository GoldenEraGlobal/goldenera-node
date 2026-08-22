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
package global.goldenera.node.core.storage.blockchain;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

import global.goldenera.node.core.properties.BlockchainDbProperties;
import lombok.extern.slf4j.Slf4j;

/** Opens a blockchain RocksDB using the canonical production column-family layout and tuning. */
@Slf4j
public final class BlockchainRocksDbFactory {

	public static final String DEFAULT_COLUMN_FAMILY = "default";
	public static final List<String> COLUMN_FAMILY_NAMES = List.of(
			DEFAULT_COLUMN_FAMILY,
			RocksDbColumnFamilies.CF_STATE_TRIE,
			RocksDbColumnFamilies.CF_BLOCKS,
			RocksDbColumnFamilies.CF_TX_INDEX,
			RocksDbColumnFamilies.CF_HASH_BY_HEIGHT,
			RocksDbColumnFamilies.CF_METADATA,
			RocksDbColumnFamilies.CF_TOKENS,
			RocksDbColumnFamilies.CF_AUTHORITIES,
			RocksDbColumnFamilies.CF_VALIDATORS,
			RocksDbColumnFamilies.CF_ENTITY_UNDO_LOG,
			RocksDbColumnFamilies.CF_EQUIVOCATIONS);

	private final BlockchainDbProperties properties;

	public BlockchainRocksDbFactory(BlockchainDbProperties properties) {
		this.properties = properties;
	}

	public RocksDB open(Path databasePath, RocksDbColumnFamilies columnFamilies)
			throws IOException, RocksDBException {
		Path normalizedPath = databasePath.toAbsolutePath().normalize();
		if (!columnFamilies.getHandles().isEmpty()) {
			throw new IllegalArgumentException("Column-family holder must be empty before opening RocksDB");
		}
		Files.createDirectories(normalizedPath);
		RocksDB.loadLibrary();

		long blockCacheSize = properties.getRocksdbBlockCacheMb() * 1024L * 1024L;
		long writeBufferSize = properties.getRocksdbWriteBufferMb() * 1024L * 1024L;
		long blockSize = properties.getRocksdbBlockSizeKb() * 1024L;

		log.info("Configuring RocksDB with tuned options:");
		log.info("  Block Cache: {}MB, Write Buffer: {}MB x {}",
				properties.getRocksdbBlockCacheMb(), properties.getRocksdbWriteBufferMb(),
				properties.getRocksdbMaxWriteBuffers());
		log.info("  Background Jobs: {}, Direct I/O: reads={}, writes={}",
				properties.getRocksdbMaxBackgroundJobs(), properties.isRocksdbDirectReads(),
				properties.isRocksdbDirectWrites());

		try (Cache sharedCache = new LRUCache(blockCacheSize);
				Filter bloomFilter = new BloomFilter(properties.getRocksdbBloomFilterBits(), false)) {
			BlockBasedTableConfig stateTrieTableConfig = new BlockBasedTableConfig()
					.setBlockCache(sharedCache)
					.setFilterPolicy(bloomFilter)
					.setBlockSize(blockSize)
					.setCacheIndexAndFilterBlocks(true)
					.setPinL0FilterAndIndexBlocksInCache(true)
					.setFormatVersion(5);

			BlockBasedTableConfig blocksTableConfig = new BlockBasedTableConfig()
					.setBlockCache(sharedCache)
					.setBlockSize(blockSize * 4)
					.setCacheIndexAndFilterBlocks(true)
					.setPinL0FilterAndIndexBlocksInCache(true)
					.setFormatVersion(5);

			BlockBasedTableConfig indexTableConfig = new BlockBasedTableConfig()
					.setBlockCache(sharedCache)
					.setFilterPolicy(bloomFilter)
					.setBlockSize(blockSize)
					.setCacheIndexAndFilterBlocks(true)
					.setPinL0FilterAndIndexBlocksInCache(true)
					.setFormatVersion(5);

			try (ColumnFamilyOptions stateTrieOptions = createColumnFamilyOptions(
						stateTrieTableConfig, writeBufferSize, properties.getRocksdbMaxWriteBuffers());
					ColumnFamilyOptions blocksOptions = createBlocksColumnFamilyOptions(
							blocksTableConfig, writeBufferSize, properties.getRocksdbMaxWriteBuffers());
					ColumnFamilyOptions indexOptions = createColumnFamilyOptions(
							indexTableConfig, writeBufferSize, properties.getRocksdbMaxWriteBuffers());
					ColumnFamilyOptions defaultOptions = createColumnFamilyOptions(
							indexTableConfig, writeBufferSize, properties.getRocksdbMaxWriteBuffers());
					Statistics statistics = new Statistics();
					DBOptions databaseOptions = new DBOptions()
							.setCreateIfMissing(true)
							.setCreateMissingColumnFamilies(true)
							.setMaxBackgroundJobs(properties.getRocksdbMaxBackgroundJobs())
							.setBytesPerSync(1048576)
							.setStatistics(statistics)) {
				List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
						new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_STATE_TRIE.getBytes(UTF_8), stateTrieOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_BLOCKS.getBytes(UTF_8), blocksOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_TX_INDEX.getBytes(UTF_8), indexOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT.getBytes(UTF_8), indexOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_METADATA.getBytes(UTF_8), defaultOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_TOKENS.getBytes(UTF_8), defaultOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_AUTHORITIES.getBytes(UTF_8), defaultOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_VALIDATORS.getBytes(UTF_8), defaultOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_ENTITY_UNDO_LOG.getBytes(UTF_8), defaultOptions),
						new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_EQUIVOCATIONS.getBytes(UTF_8), defaultOptions));

				if (properties.isRocksdbDirectReads()) {
					databaseOptions.setUseDirectReads(true);
					log.info("  Direct I/O for reads: ENABLED");
				}
				if (properties.isRocksdbDirectWrites()) {
					databaseOptions.setUseDirectIoForFlushAndCompaction(true);
					log.info("  Direct I/O for writes: ENABLED");
				}
				RateLimiter rateLimiter = null;
				try {
					if (properties.getRocksdbRateLimitMbPerSec() > 0) {
						rateLimiter = new RateLimiter(properties.getRocksdbRateLimitMbPerSec() * 1024L * 1024L);
						databaseOptions.setRateLimiter(rateLimiter);
						log.info("  Rate Limiter: {}MB/s", properties.getRocksdbRateLimitMbPerSec());
					}

					return openDatabase(normalizedPath, columnFamilies, databaseOptions, descriptors);
				} finally {
					if (rateLimiter != null) {
						rateLimiter.close();
					}
				}
			}
		}
	}

	private RocksDB openDatabase(
			Path databasePath,
			RocksDbColumnFamilies columnFamilies,
			DBOptions databaseOptions,
			List<ColumnFamilyDescriptor> descriptors) throws RocksDBException {
		List<ColumnFamilyHandle> handles = new ArrayList<>();
		RocksDB database = null;
		boolean opened = false;
		try {
			log.info("Opening RocksDB at path: {}", databasePath);
			database = RocksDB.open(databaseOptions, databasePath.toString(), descriptors, handles);
			populateHandles(columnFamilies, handles);
			opened = true;
			log.info("RocksDB successfully opened with {} column families", handles.size());
			return database;
		} finally {
			if (!opened) {
				for (int index = handles.size() - 1; index >= 0; index--) {
					handles.get(index).close();
				}
				if (database != null) {
					database.close();
				}
			}
		}
	}

	private void populateHandles(RocksDbColumnFamilies columnFamilies, List<ColumnFamilyHandle> handles) {
		if (handles.size() != COLUMN_FAMILY_NAMES.size()) {
			throw new IllegalStateException("RocksDB returned an unexpected column-family handle count");
		}
		log.info("Populating ColumnFamily handles...");
		for (int index = 0; index < COLUMN_FAMILY_NAMES.size(); index++) {
			columnFamilies.addHandle(COLUMN_FAMILY_NAMES.get(index), handles.get(index));
		}
	}

	private ColumnFamilyOptions createColumnFamilyOptions(
			BlockBasedTableConfig tableConfig,
			long writeBufferSize,
			int maxWriteBuffers) {
		return new ColumnFamilyOptions()
				.setTableFormatConfig(tableConfig)
				.setLevelCompactionDynamicLevelBytes(true)
				.setWriteBufferSize(writeBufferSize)
				.setMaxWriteBufferNumber(maxWriteBuffers)
				.setMinWriteBufferNumberToMerge(1)
				.setTargetFileSizeBase(writeBufferSize)
				.setMaxBytesForLevelBase(writeBufferSize * 4)
				.setCompressionPerLevel(Arrays.asList(
						CompressionType.NO_COMPRESSION,
						CompressionType.NO_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.LZ4_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION,
						CompressionType.ZSTD_COMPRESSION))
				.setLevel0FileNumCompactionTrigger(4)
				.setLevel0SlowdownWritesTrigger(20)
				.setLevel0StopWritesTrigger(36);
	}

	private ColumnFamilyOptions createBlocksColumnFamilyOptions(
			BlockBasedTableConfig tableConfig,
			long writeBufferSize,
			int maxWriteBuffers) {
		long blobMinBytes = properties.getRocksdbBlobMinBytes();
		long blobFileSize = properties.getRocksdbBlobFileSizeMb() * 1024L * 1024L;
		ColumnFamilyOptions options = new ColumnFamilyOptions()
				.setTableFormatConfig(tableConfig)
				.setLevelCompactionDynamicLevelBytes(true)
				.setWriteBufferSize(writeBufferSize)
				.setMaxWriteBufferNumber(maxWriteBuffers)
				.setMinWriteBufferNumberToMerge(1)
				.setTargetFileSizeBase(writeBufferSize)
				.setMaxBytesForLevelBase(writeBufferSize * 4)
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

		if (properties.isRocksdbBlobEnabled()) {
			options.setEnableBlobFiles(true)
					.setMinBlobSize(blobMinBytes)
					.setBlobFileSize(blobFileSize)
					.setBlobCompressionType(CompressionType.ZSTD_COMPRESSION)
					.setPrepopulateBlobCache(PrepopulateBlobCache.PREPOPULATE_BLOB_FLUSH_ONLY)
					.setBlobCompactionReadaheadSize(4 * 1024 * 1024);
			if (properties.isRocksdbBlobGcEnabled()) {
				options.setEnableBlobGarbageCollection(true)
						.setBlobGarbageCollectionAgeCutoff(properties.getRocksdbBlobGcAgeCutoff());
			}
			log.info(
					"  BlobDB for CF_BLOCKS: ENABLED (min={}KB, fileSize={}MB, GC={}, prepopulate=true, readahead=4MB)",
					blobMinBytes / 1024, properties.getRocksdbBlobFileSizeMb(),
					properties.isRocksdbBlobGcEnabled());
		}
		return options;
	}
}
