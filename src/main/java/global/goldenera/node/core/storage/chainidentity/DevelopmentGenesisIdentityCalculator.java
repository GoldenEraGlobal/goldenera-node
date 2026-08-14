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
package global.goldenera.node.core.storage.chainidentity;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import com.github.benmanes.caffeine.cache.Caffeine;

import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.state.WorldStateSerialization;
import global.goldenera.node.core.state.trie.rocksdb.RocksDBMerkleStorageFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;

/** Calculates a development genesis hash without opening the target RocksDB. */
public final class DevelopmentGenesisIdentityCalculator {

	public String calculate(NetworkSettings settings) {
		Path temporaryDirectory = null;
		try {
			temporaryDirectory = Files.createTempDirectory("goldenera-genesis-preflight-");
			return calculate(settings, temporaryDirectory);
		} catch (Exception e) {
			throw new ChainStorageGuardException(
					"Failed to calculate development genesis in isolated storage", e);
		} finally {
			deleteTemporaryDirectory(temporaryDirectory);
		}
	}

	private String calculate(NetworkSettings settings, Path path) throws Exception {
		RocksDB.loadLibrary();
		try (DBOptions databaseOptions = new DBOptions()
				.setCreateIfMissing(true)
				.setCreateMissingColumnFamilies(true);
				ColumnFamilyOptions defaultOptions = new ColumnFamilyOptions();
				ColumnFamilyOptions trieOptions = new ColumnFamilyOptions()) {
			List<ColumnFamilyDescriptor> descriptors = List.of(
					new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultOptions),
					new ColumnFamilyDescriptor(
							RocksDbColumnFamilies.CF_STATE_TRIE.getBytes(UTF_8), trieOptions));
			List<ColumnFamilyHandle> handles = new ArrayList<>();
			try (RocksDB database = RocksDB.open(
					databaseOptions, path.toString(), descriptors, handles)) {
				RocksDbColumnFamilies families = new RocksDbColumnFamilies();
				families.addHandle("default", handles.get(0));
				families.addHandle(RocksDbColumnFamilies.CF_STATE_TRIE, handles.get(1));
				RocksDBMerkleStorageFactory storage = new RocksDBMerkleStorageFactory(
						database, families, Caffeine.newBuilder().build());
				WorldStateSerialization serialization = new WorldStateSerialization();
				WorldStateFactory worldStateFactory = new WorldStateFactory(
						storage,
						serialization.rootStateSerializer(), serialization.rootStateDeserializer(),
						serialization.balanceSerializer(), serialization.balanceDeserializer(),
						serialization.nonceSerializer(), serialization.nonceDeserializer(),
						serialization.addressAliasSerializer(), serialization.addressAliasDeserializer(),
						serialization.authoritySerializer(), serialization.authorityDeserializer(),
						serialization.validatorSerializer(), serialization.validatorDeserializer(),
						serialization.bipStateSerializer(), serialization.bipStateDeserializer(),
						serialization.networkParamsSerializer(), serialization.networkParamsDeserializer(),
						serialization.miningWindowSerializer(), serialization.miningWindowDeserializer(),
						serialization.tokenSerializer(), serialization.tokenDeserializer());
				return new GenesisCandidateFactory(worldStateFactory)
						.create(settings, 0L).block().getHash().toHexString();
			} finally {
				for (int index = handles.size() - 1; index >= 0; index--) {
					handles.get(index).close();
				}
			}
		}
	}

	private void deleteTemporaryDirectory(Path directory) {
		if (directory == null || Files.notExists(directory)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			throw new ChainStorageGuardException(
					"Failed to remove isolated development genesis storage", e);
		}
	}
}
