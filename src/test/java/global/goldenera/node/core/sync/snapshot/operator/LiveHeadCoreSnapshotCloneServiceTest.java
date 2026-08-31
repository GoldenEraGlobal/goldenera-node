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
package global.goldenera.node.core.sync.snapshot.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncoder;
import global.goldenera.node.core.storage.chainidentity.RocksChainIdentityStore;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentityCodec;

class LiveHeadCoreSnapshotCloneServiceTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void rewindsOnlyConsistentCloneAndLeavesLiveDatabaseUnchangedAcrossReruns() throws Exception {
		try (Fixture fixture = fixture(false)) {
			Hash liveHead = fixture.blocks().getLast().getHash();
			Hash selected = fixture.blocks().get(1).getHash();

			for (int attempt = 0; attempt < 2; attempt++) {
				Path clonePath;
				try (LiveHeadCoreSnapshotClone clone = fixture.service().create(1, selected)) {
					clonePath = clone.databaseDirectory();
					assertThat(clone.height()).isEqualTo(1);
					assertThat(clone.hash()).isEqualTo(selected);
					assertThat(readLatest(clonePath, fixture.properties())).isEqualTo(selected);
				}
				assertThat(clonePath).doesNotExist();
			}
			assertThat(Hash.wrap(fixture.database().get(
					fixture.families().metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH)))
					.isEqualTo(liveHead);
		}
	}

	@Test
	void corruptUndoLogFailsClosedAndCleansClone() throws Exception {
		try (Fixture fixture = fixture(true)) {
			Hash liveHead = fixture.blocks().getLast().getHash();

			assertThatThrownBy(() -> fixture.service().create(1, fixture.blocks().get(1).getHash()))
					.isInstanceOf(Exception.class)
					.hasMessageContaining("Unrecognized token");
			assertThat(Hash.wrap(fixture.database().get(
					fixture.families().metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH)))
					.isEqualTo(liveHead);
			try (var paths = Files.list(temporaryDirectory)) {
				assertThat(paths.map(path -> path.getFileName().toString()).toList())
						.noneMatch(name -> name.contains("-live-head-"));
			}
		}
	}

	private Fixture fixture(boolean corruptUndo) throws Exception {
		BlockchainDbProperties properties = properties(temporaryDirectory.resolve("live-db"));
		BlockchainRocksDbFactory factory = new BlockchainRocksDbFactory(properties);
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = factory.open(Path.of(properties.getPath()), families);
		List<StoredBlock> blocks = chain(4);
		StoredChainIdentity identity = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 0, "mainnet",
				"0x924fd3c5b501e1ccef10ca08cb6b473382d44618533d32339752988e469a516f", null);
		try (WriteOptions options = new WriteOptions().setSync(true); WriteBatch batch = new WriteBatch()) {
			for (StoredBlock block : blocks) {
				batch.put(families.blocks(), block.getHash().toArray(),
						StoredBlockEncoder.INSTANCE.encode(block, StoredBlockVersion.V1).toArray());
				batch.put(families.hashByHeight(), Bytes.ofUnsignedLong(block.getHeight()).toArray(),
						block.getHash().toArray());
			}
			batch.put(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH,
					blocks.getLast().getHash().toArray());
			batch.put(families.metadata(), RocksChainIdentityStore.STORAGE_KEY,
					StoredChainIdentityCodec.encode(identity));
			if (corruptUndo) {
				batch.put(families.entityUndoLog(), blocks.getLast().getHash().toArray(),
						"not-json".getBytes(StandardCharsets.UTF_8));
			}
			database.write(options, batch);
		}
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		LiveHeadCoreSnapshotCloneService service = new LiveHeadCoreSnapshotCloneService(
				database, families, properties, factory, new ReentrantLock(), mapper);
		return new Fixture(properties, database, families, blocks, service);
	}

	private List<StoredBlock> chain(int count) {
		List<StoredBlock> blocks = new ArrayList<>();
		Hash previous = Hash.ZERO;
		BigInteger cumulative = BigInteger.ZERO;
		for (int height = 0; height < count; height++) {
			BlockHeader header = BlockHeaderImpl.builder()
					.version(BlockVersion.V1)
					.height(height)
					.timestamp(Instant.ofEpochSecond(height + 1L))
					.previousHash(previous)
					.txRootHash(Hash.ZERO)
					.stateRootHash(MerkleTrie.EMPTY_TRIE_NODE_HASH)
					.difficulty(BigInteger.ONE)
					.coinbase(Address.ZERO)
					.nonce(height)
					.signature(Signature.ZERO)
					.build();
			Block block = BlockImpl.builder().header(header).txs(List.of()).build();
			cumulative = cumulative.add(BigInteger.ONE);
			StoredBlock stored = StoredBlock.builder()
					.block(block)
					.cumulativeDifficulty(cumulative)
					.receivedAt(header.getTimestamp())
					.receivedFrom(Address.ZERO)
					.connectedSource(height == 0 ? ConnectedSource.GENESIS : ConnectedSource.SYNC)
					.identity(header.getIdentity())
					.computeIndexes()
					.build();
			blocks.add(stored);
			previous = stored.getHash();
		}
		return blocks;
	}

	private Hash readLatest(Path path, BlockchainDbProperties properties) throws Exception {
		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = new BlockchainRocksDbFactory(properties).open(path, families);
		try {
			return Hash.wrap(database.get(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH));
		} finally {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}

	private BlockchainDbProperties properties(Path path) {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(path.toString());
		properties.setRocksdbBlockCacheMb(8);
		properties.setRocksdbWriteBufferMb(4);
		properties.setRocksdbMaxWriteBuffers(2);
		properties.setRocksdbMaxBackgroundJobs(2);
		properties.setRocksdbBlockSizeKb(4);
		properties.setRocksdbDirectReads(false);
		properties.setRocksdbDirectWrites(false);
		properties.setRocksdbBlobEnabled(false);
		return properties;
	}

	private record Fixture(
			BlockchainDbProperties properties,
			RocksDB database,
			RocksDbColumnFamilies families,
			List<StoredBlock> blocks,
			LiveHeadCoreSnapshotCloneService service) implements AutoCloseable {

		@Override
		public void close() {
			families.getHandles().values().forEach(ColumnFamilyHandle::close);
			database.close();
		}
	}
}
