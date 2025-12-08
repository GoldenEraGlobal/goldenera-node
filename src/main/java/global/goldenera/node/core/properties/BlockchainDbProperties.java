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
package global.goldenera.node.core.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ge.core.blockchain.db", ignoreUnknownFields = false)
public class BlockchainDbProperties {

	// ========================
	// Database Path
	// ========================
	@NonNull
	String path;

	// ========================
	// RocksDB Tuning
	// ========================

	/**
	 * RocksDB block cache size in MB. Shared across all column families.
	 * Higher values improve read performance but consume more memory.
	 * Default: 512MB
	 */
	int rocksdbBlockCacheMb = 512;

	/**
	 * RocksDB write buffer (memtable) size in MB per column family.
	 * Larger buffers improve write throughput but increase memory usage.
	 * Default: 64MB
	 */
	int rocksdbWriteBufferMb = 64;

	/**
	 * Maximum number of write buffers per column family.
	 * More buffers allow continued writes during flushes.
	 * Default: 4
	 */
	int rocksdbMaxWriteBuffers = 4;

	/**
	 * Number of background jobs for compaction and flushing.
	 * Should be set based on available CPU cores (typically cores/2 to cores).
	 * Default: 6
	 */
	int rocksdbMaxBackgroundJobs = 6;

	/**
	 * Block size in KB for SST files.
	 * Smaller blocks are better for point lookups (state trie),
	 * larger blocks are better for sequential scans (block data).
	 * Default: 16KB
	 */
	int rocksdbBlockSizeKb = 16;

	/**
	 * Bloom filter bits per key.
	 * Higher values reduce false positives but use more memory.
	 * 10 bits gives ~1% false positive rate.
	 * Default: 10
	 */
	int rocksdbBloomFilterBits = 10;

	/**
	 * Enable direct I/O for reads (bypasses OS page cache).
	 * Useful when RocksDB cache is larger than what OS can provide.
	 * Recommended for production Linux servers.
	 * Default: true (optimized for Linux/Docker)
	 */
	boolean rocksdbDirectReads = true;

	/**
	 * Enable direct I/O for flush and compaction operations.
	 * Reduces double-buffering overhead on Linux.
	 * Default: true (optimized for Linux/Docker)
	 */
	boolean rocksdbDirectWrites = true;

	/**
	 * Rate limit for background I/O in MB/s (0 = unlimited).
	 * Prevents compaction from starving foreground reads.
	 * Default: 0 (unlimited)
	 */
	int rocksdbRateLimitMbPerSec = 0;

	// ========================
	// BlobDB for Large Values (StoredBlock up to 7MB)
	// ========================

	/**
	 * Enable BlobDB for CF_BLOCKS column family.
	 * BlobDB stores large values separately from LSM tree, improving
	 * write amplification and compaction performance for large values.
	 * Recommended: true for StoredBlock storage.
	 * Default: true
	 */
	boolean rocksdbBlobEnabled = true;

	/**
	 * Minimum value size to store in blob files (bytes).
	 * Values smaller than this are stored inline in SST files.
	 * 64KB is good threshold - most headers are smaller, full blocks are larger.
	 * Default: 65536 (64KB)
	 */
	int rocksdbBlobMinBytes = 65536;

	/**
	 * Blob file size in MB.
	 * Larger files reduce metadata overhead but increase space amplification.
	 * Default: 256MB (good for GCloud PD SSD)
	 */
	int rocksdbBlobFileSizeMb = 256;

	/**
	 * Enable blob garbage collection during compaction.
	 * Reclaims space from deleted/overwritten blobs.
	 * Default: true
	 */
	boolean rocksdbBlobGcEnabled = true;

	/**
	 * Blob garbage collection age cutoff (0.0 - 1.0).
	 * Only GC blobs in files where this fraction of blobs are garbage.
	 * 0.25 = GC when 25%+ of file is garbage.
	 * Default: 0.25
	 */
	double rocksdbBlobGcAgeCutoff = 0.25;

	// ========================
	// Application Cache (Caffeine)
	// ========================

	/**
	 * Block cache size in MB (full StoredBlock objects).
	 * Caches recently accessed blocks for fast retrieval.
	 * Default: 256MB
	 */
	int cacheBlockMb = 256;

	/**
	 * Trie node cache size in MB (WorldState MPT nodes).
	 * Critical for state access performance.
	 * Default: 256MB
	 */
	int cacheTrieNodeMb = 256;

	/**
	 * Transaction cache size in MB.
	 * Caches recently accessed transactions.
	 * Default: 128MB
	 */
	int cacheTxMb = 128;

	/**
	 * Header cache maximum entries (partial StoredBlock without tx body).
	 * Used for header-only operations.
	 * Default: 50000
	 */
	int cacheHeaderMaxEntries = 50_000;

	/**
	 * Height-to-hash cache maximum entries.
	 * Maps block height to block hash.
	 * Default: 100000
	 */
	int cacheHeightMaxEntries = 100_000;

	/**
	 * Cache entry expiration time in minutes.
	 * Default: 60 (1 hour)
	 */
	int cacheExpireMinutes = 60;

}
