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
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import global.goldenera.node.core.properties.PeerReputationDbProperties;
import global.goldenera.node.core.storage.peers.PeerReputationColumnFamilies;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class PeerReputationDBConfig {

	PeerReputationDbProperties peerReputationDbProperties;
	PeerReputationColumnFamilies columnFamilies;
	@NonFinal
	RocksDB openedDatabase;

	@Bean(name = "peerReputationDB", destroyMethod = "")
	public RocksDB peerReputationDB() throws RocksDBException, IOException {
		String dbPath = peerReputationDbProperties.getPath();
		Files.createDirectories(Paths.get(dbPath));
		RocksDB.loadLibrary();

		// Lighter configuration for Peer Reputation (less memory intensive than
		// blockchain)
		try (Cache blockCache = new LRUCache(16 * 1024 * 1024L);
				BloomFilter bloomFilter = new BloomFilter(10, false);
				ColumnFamilyOptions cfOpts = new ColumnFamilyOptions();
				DBOptions dbOptions = new DBOptions()) {
			final BlockBasedTableConfig tableOptions = new BlockBasedTableConfig()
					.setBlockCache(blockCache)
					.setFilterPolicy(bloomFilter)
					.setBlockSize(4 * 1024L)
					.setCacheIndexAndFilterBlocks(true);

			cfOpts.setTableFormatConfig(tableOptions)
					.setWriteBufferSize(4 * 1024 * 1024L)
					.setLevelCompactionDynamicLevelBytes(true)
					.setCompressionType(CompressionType.LZ4_COMPRESSION);

			final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
					new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
					new ColumnFamilyDescriptor(
							PeerReputationColumnFamilies.CF_PEER_REPUTATION.getBytes(StandardCharsets.UTF_8),
							cfOpts));

			dbOptions.setCreateIfMissing(true)
					.setCreateMissingColumnFamilies(true)
					.setMaxBackgroundJobs(2);

			final List<ColumnFamilyHandle> handles = new ArrayList<>();

			File dbDir = new File(dbPath);
			if (!dbDir.exists()) {
				dbDir.mkdirs();
			}
			log.info("Opening Peer Reputation RocksDB at path: {}", dbDir.getAbsolutePath());

			RocksDB rocksDB = null;
			try {
				rocksDB = RocksDB.open(dbOptions, dbDir.getAbsolutePath(), cfDescriptors, handles);

				columnFamilies.addHandle("default", handles.get(0));
				columnFamilies.addHandle(PeerReputationColumnFamilies.CF_PEER_REPUTATION, handles.get(1));
				openedDatabase = rocksDB;

				log.info("Peer Reputation RocksDB initialized.");
				return rocksDB;
			} catch (RuntimeException | RocksDBException failure) {
				handles.forEach(ColumnFamilyHandle::close);
				if (rocksDB != null) {
					rocksDB.close();
				}
				throw failure;
			}
		}
	}

	@PreDestroy
	public void close() {
		columnFamilies.close();
		if (openedDatabase != null) {
			openedDatabase.close();
			openedDatabase = null;
		}
	}
}
