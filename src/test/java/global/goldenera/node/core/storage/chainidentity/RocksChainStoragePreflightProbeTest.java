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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.GenesisConfigLoader;
import global.goldenera.node.GenesisSettings;
import global.goldenera.node.NetworkSettings;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.blockchain.genesis.GenesisCandidateFactory;
import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.state.PersistentWorldStateTestSupport;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncoder;

class RocksChainStoragePreflightProbeTest {

	private static final String MAINNET_GENESIS =
			"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f";
	private static final StoredChainIdentity MAINNET_IDENTITY = new StoredChainIdentity(
			1, 0, "mainnet", MAINNET_GENESIS, null);

	@TempDir
	Path tempDirectory;

	@BeforeAll
	static void loadRocks() {
		RocksDB.loadLibrary();
	}

	@Test
	void absentPathIsEmptyAndIsNotCreated() {
		Path path = tempDirectory.resolve("absent");

		ChainStoragePreflightObservation observation =
				new RocksChainStoragePreflightProbe(path).inspect();

		assertThat(observation.hasChainData()).isFalse();
		assertThat(observation.identityStorageExists()).isFalse();
		assertThat(path).doesNotExist();
	}

	@Test
	void readOnlyInspectionDoesNotMutateFilesOrCreateMissingColumnFamilies() throws Exception {
		Path path = tempDirectory.resolve("read-only");
		try (RocksFixture fixture = RocksFixture.open(path, false)) {
			fixture.putDefault(new byte[] { 1 }, new byte[] { 2 });
		}
		Map<String, String> before = snapshot(path);

		ChainStoragePreflightObservation observation =
				new RocksChainStoragePreflightProbe(path).inspect();

		assertThat(observation.hasChainData()).isTrue();
		assertThat(observation.identityStorageExists()).isFalse();
		assertThat(snapshot(path)).isEqualTo(before);
		assertThat(listFamilies(path)).containsExactly("default");
	}

	@Test
	void acceptsARealDecodedAndInternallyConsistentLegacyGenesis() throws Exception {
		Path path = tempDirectory.resolve("valid");
		Block genesis = mainnetGenesis(tempDirectory.resolve("state-valid"));
		assertThat(genesis.getHash().toHexString()).isEqualTo(MAINNET_GENESIS);
		try (RocksFixture fixture = RocksFixture.open(path, true)) {
			fixture.putGenesis(genesis, genesis.getHash().toArray());
		}

		ChainStoragePreflightObservation observation =
				new RocksChainStoragePreflightProbe(path).inspect();

		assertThat(observation.hasChainData()).isTrue();
		assertThat(observation.identity()).isEmpty();
		assertThat(observation.observedGenesisHash()).contains(MAINNET_GENESIS);
		assertThat(new KnownProductionLegacyStorageVerifier()
				.verifies(MAINNET_IDENTITY, List.of(observation))).isTrue();
	}

	@Test
	void rejectsForgedHeightIndexEvenWhenItResolvesToDecodableBlockBytes() throws Exception {
		Path path = tempDirectory.resolve("forged");
		Block genesis = mainnetGenesis(tempDirectory.resolve("state-forged"));
		byte[] forgedHash = Hash.fromHexString("0x" + "ab".repeat(32)).toArray();
		try (RocksFixture fixture = RocksFixture.open(path, true)) {
			fixture.putGenesis(genesis, forgedHash);
		}

		assertThatThrownBy(() -> new RocksChainStoragePreflightProbe(path).inspect())
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("inconsistent");
	}

	@Test
	void rejectsGenesisIndexWhoseStoredBodyOrHeaderIsMissing() throws Exception {
		Path path = tempDirectory.resolve("missing-block");
		try (RocksFixture fixture = RocksFixture.open(path, true)) {
			fixture.putHeightIndex(Hash.fromHexString(MAINNET_GENESIS).toArray());
		}

		assertThatThrownBy(() -> new RocksChainStoragePreflightProbe(path).inspect())
				.isInstanceOf(ChainStorageGuardException.class)
				.hasMessageContaining("does not resolve to a stored block");
	}

	@Test
	void identityMetadataAloneDoesNotCountAsChainData() throws Exception {
		Path path = tempDirectory.resolve("identity-only");
		try (RocksFixture fixture = RocksFixture.open(path, true)) {
			fixture.putIdentity(MAINNET_IDENTITY);
		}

		ChainStoragePreflightObservation observation =
				new RocksChainStoragePreflightProbe(path).inspect();

		assertThat(observation.identity()).contains(MAINNET_IDENTITY);
		assertThat(observation.hasChainData()).isFalse();
		assertThat(observation.observedGenesisHash()).isEmpty();
	}

	private Block mainnetGenesis(Path statePath) throws Exception {
		GenesisSettings genesis = GenesisConfigLoader.loadGenesisSettings(Network.MAINNET, "prod");
		NetworkSettings settings = NetworkSettings.fromGenesisSettings(genesis, Network.MAINNET, "prod");
		try (PersistentWorldStateTestSupport storage = new PersistentWorldStateTestSupport(statePath)) {
			return new GenesisCandidateFactory(storage.factory()).create(settings, 0L).block();
		}
	}

	private Map<String, String> snapshot(Path root) throws Exception {
		Map<String, String> result = new TreeMap<>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).toList()) {
				result.put(root.relativize(path).toString(),
						HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
								.digest(Files.readAllBytes(path))));
			}
		}
		return result;
	}

	private List<String> listFamilies(Path path) throws Exception {
		try (Options options = new Options().setCreateIfMissing(false)) {
			return RocksDB.listColumnFamilies(options, path.toString()).stream()
					.map(bytes -> new String(bytes, UTF_8))
					.toList();
		}
	}

	private static final class RocksFixture implements AutoCloseable {
		private final RocksDB database;
		private final List<ColumnFamilyHandle> handles;
		private final List<ColumnFamilyOptions> familyOptions;
		private final DBOptions databaseOptions;
		private final ColumnFamilyHandle defaultFamily;
		private final ColumnFamilyHandle blocks;
		private final ColumnFamilyHandle heights;
		private final ColumnFamilyHandle metadata;

		private RocksFixture(
				RocksDB database,
				List<ColumnFamilyHandle> handles,
				List<ColumnFamilyOptions> familyOptions,
				DBOptions databaseOptions,
				ColumnFamilyHandle defaultFamily,
				ColumnFamilyHandle blocks,
				ColumnFamilyHandle heights,
				ColumnFamilyHandle metadata) {
			this.database = database;
			this.handles = handles;
			this.familyOptions = familyOptions;
			this.databaseOptions = databaseOptions;
			this.defaultFamily = defaultFamily;
			this.blocks = blocks;
			this.heights = heights;
			this.metadata = metadata;
		}

		private static RocksFixture open(Path path, boolean withChainFamilies) throws Exception {
			List<byte[]> names = new ArrayList<>();
			names.add(RocksDB.DEFAULT_COLUMN_FAMILY);
			if (withChainFamilies) {
				names.add(RocksDbColumnFamilies.CF_BLOCKS.getBytes(UTF_8));
				names.add(RocksDbColumnFamilies.CF_HASH_BY_HEIGHT.getBytes(UTF_8));
				names.add(RocksDbColumnFamilies.CF_METADATA.getBytes(UTF_8));
			}
			List<ColumnFamilyOptions> familyOptions = names.stream()
					.map(ignored -> new ColumnFamilyOptions())
					.toList();
			List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
			for (int index = 0; index < names.size(); index++) {
				descriptors.add(new ColumnFamilyDescriptor(names.get(index), familyOptions.get(index)));
			}
			List<ColumnFamilyHandle> handles = new ArrayList<>();
			DBOptions options = new DBOptions()
					.setCreateIfMissing(true)
					.setCreateMissingColumnFamilies(true);
			RocksDB database = RocksDB.open(options, path.toString(), descriptors, handles);
			return new RocksFixture(
					database, handles, familyOptions, options, handles.get(0),
					withChainFamilies ? handles.get(1) : null,
					withChainFamilies ? handles.get(2) : null,
					withChainFamilies ? handles.get(3) : null);
		}

		private void putDefault(byte[] key, byte[] value) throws Exception {
			database.put(defaultFamily, key, value);
		}

		private void putIdentity(StoredChainIdentity identity) throws Exception {
			database.put(metadata, RocksChainIdentityStore.STORAGE_KEY,
					StoredChainIdentityCodec.encode(identity));
		}

		private void putHeightIndex(byte[] hash) throws Exception {
			database.put(heights, Bytes.ofUnsignedLong(0).toArray(), hash);
		}

		private void putGenesis(Block genesis, byte[] indexedHash) throws Exception {
			StoredBlock stored = StoredBlock.builder()
					.block(genesis)
					.cumulativeDifficulty(genesis.getHeader().getDifficulty())
					.receivedAt(Instant.ofEpochMilli(genesis.getHeader().getTimestamp().toEpochMilli()))
					.receivedFrom(Address.ZERO)
					.connectedSource(ConnectedSource.GENESIS)
					.identity(genesis.getHeader().getIdentity())
					.computeIndexes()
					.build();
			byte[] encoded = StoredBlockEncoder.INSTANCE.encode(stored, StoredBlockVersion.V1).toArray();
			database.put(blocks, indexedHash, encoded);
			putHeightIndex(indexedHash);
		}

		@Override
		public void close() {
			for (int index = handles.size() - 1; index >= 0; index--) {
				handles.get(index).close();
			}
			database.close();
			familyOptions.forEach(ColumnFamilyOptions::close);
			databaseOptions.close();
		}
	}
}
