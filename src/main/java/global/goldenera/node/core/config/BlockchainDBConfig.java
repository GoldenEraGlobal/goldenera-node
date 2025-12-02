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

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
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

	@Bean
	public RocksDbColumnFamilies rocksDbColumnFamilies() {
		return new RocksDbColumnFamilies();
	}

	@Bean(destroyMethod = "close")
	@Primary
	public RocksDB blockchainDB(RocksDbColumnFamilies columnFamiliesHolder) throws RocksDBException, IOException {
		String dbPath = blockchainDbProperties.getPath();
		Files.createDirectories(Paths.get(dbPath));
		RocksDB.loadLibrary();

		final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions().optimizeLevelStyleCompaction();

		final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
				new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_STATE_TRIE.getBytes(StandardCharsets.UTF_8),
						cfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_BLOCKS.getBytes(StandardCharsets.UTF_8),
						cfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_TX_INDEX.getBytes(StandardCharsets.UTF_8),
						cfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT.getBytes(StandardCharsets.UTF_8),
						cfOpts),
				new ColumnFamilyDescriptor(RocksDbColumnFamilies.CF_METADATA.getBytes(StandardCharsets.UTF_8), cfOpts));

		final DBOptions dbOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true);

		final List<ColumnFamilyHandle> columnFamilyHandles = new ArrayList<>();

		log.info("Opening RocksDB at path: {}", dbPath);
		File dbDir = new File(dbPath);
		dbDir.mkdirs();

		RocksDB db = RocksDB.open(dbOptions, dbDir.getAbsolutePath(), cfDescriptors, columnFamilyHandles);

		log.info("Populating ColumnFamily handles...");
		columnFamiliesHolder.addHandle("default", columnFamilyHandles.get(0));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_STATE_TRIE, columnFamilyHandles.get(1));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_BLOCKS, columnFamilyHandles.get(2));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_TX_INDEX, columnFamilyHandles.get(3));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT, columnFamilyHandles.get(4));
		columnFamiliesHolder.addHandle(RocksDbColumnFamilies.CF_METADATA, columnFamilyHandles.get(5));

		log.info("RocksDB successfully opened and configured.");
		return db;
	}
}