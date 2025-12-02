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

import global.goldenera.node.core.properties.PeerReputationDbProperties;
import global.goldenera.node.core.storage.peers.PeerReputationColumnFamilies;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class PeerReputationDBConfig {

	PeerReputationDbProperties peerReputationDbProperties;
	PeerReputationColumnFamilies columnFamilies;

	@Bean(name = "peerReputationDB", destroyMethod = "close")
	public RocksDB peerReputationDB() throws RocksDBException, IOException {
		String dbPath = peerReputationDbProperties.getPath();
		Files.createDirectories(Paths.get(dbPath));
		RocksDB.loadLibrary();

		final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions().optimizeLevelStyleCompaction();

		final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
				new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
				new ColumnFamilyDescriptor(
						PeerReputationColumnFamilies.CF_PEER_REPUTATION.getBytes(StandardCharsets.UTF_8),
						cfOpts));

		final DBOptions dbOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true);

		final List<ColumnFamilyHandle> handles = new ArrayList<>();

		File dbDir = new File(dbPath);
		dbDir.mkdirs();
		log.info("Opening Peer Reputation RocksDB at path: {}", dbDir.getAbsolutePath());

		RocksDB rocksDB = RocksDB.open(dbOptions, dbDir.getAbsolutePath(), cfDescriptors, handles);

		columnFamilies.addHandle("default", handles.get(0));
		columnFamilies.addHandle(PeerReputationColumnFamilies.CF_PEER_REPUTATION, handles.get(1));

		log.info("Peer Reputation RocksDB initialized.");
		return rocksDB;
	}
}
