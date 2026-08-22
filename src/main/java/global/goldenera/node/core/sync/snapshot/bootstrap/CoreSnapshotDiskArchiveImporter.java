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
package global.goldenera.node.core.sync.snapshot.bootstrap;

import static global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource.GENESIS;
import static global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource.SYNC;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.Comparator;

import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.node.core.enums.StoredBlockVersion;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockEncoder;
import global.goldenera.node.core.storage.chainidentity.RocksChainIdentityStore;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentityCodec;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.SnapshotNode;
import global.goldenera.node.core.sync.snapshot.SnapshotNodeSource;
import global.goldenera.node.core.sync.snapshot.SnapshotDiskSpaceBudget;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityEntry;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityStateCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityType;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;

/** Builds a complete closed sibling RocksDB from an already verified FULL snapshot. */
public final class CoreSnapshotDiskArchiveImporter implements CoreSnapshotArchiveImporter {

	static final int MAX_BATCH_ENTRIES = 10_000;
	static final long MAX_BATCH_BYTES = 64L * 1024 * 1024;

	private final BlockchainDbProperties databaseProperties;
	private final BlockchainRocksDbFactory rocksDbFactory;
	private final ObjectMapper objectMapper;
	private final SnapshotDiskSpaceBudget diskSpaceBudget;

	public CoreSnapshotDiskArchiveImporter(
			BlockchainDbProperties databaseProperties,
			BlockchainRocksDbFactory rocksDbFactory,
			ObjectMapper objectMapper) {
		this(databaseProperties, rocksDbFactory, objectMapper, SnapshotDiskSpaceBudget.system());
	}

	CoreSnapshotDiskArchiveImporter(
			BlockchainDbProperties databaseProperties,
			BlockchainRocksDbFactory rocksDbFactory,
			ObjectMapper objectMapper,
			SnapshotDiskSpaceBudget diskSpaceBudget) {
		this.databaseProperties = Objects.requireNonNull(databaseProperties, "databaseProperties");
		this.rocksDbFactory = Objects.requireNonNull(rocksDbFactory, "rocksDbFactory");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.diskSpaceBudget = Objects.requireNonNull(diskSpaceBudget, "diskSpaceBudget");
	}

	public CoreSnapshotDiskArchiveImporter(
			BlockchainDbProperties databaseProperties,
			ObjectMapper objectMapper) {
		this(databaseProperties, new BlockchainRocksDbFactory(databaseProperties), objectMapper);
	}

	@Override
	public PreparedCoreSnapshotImport prepare(
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verifiedArchive) throws Exception {
		validateCapability(staged, verifiedArchive);
		Path target = Path.of(databaseProperties.getPath()).toAbsolutePath().normalize();
		Path parent = Objects.requireNonNull(target.getParent(), "Blockchain database parent");
		diskSpaceBudget.requirePreparedDatabase(
				parent, staged.stateSnapshot().domainManifest(), staged.archiveManifest());
		Files.createDirectories(parent);
		Path preparedDirectory = Files.createTempDirectory(
				parent, "." + target.getFileName() + "-snapshot-prepared-").toRealPath();
		if (!preparedDirectory.getParent().equals(parent.toRealPath())) {
			deleteDirectory(preparedDirectory);
			throw new IllegalStateException("Prepared snapshot database is not a same-filesystem sibling");
		}

		RocksDbColumnFamilies families = new RocksDbColumnFamilies();
		RocksDB database = null;
		boolean complete = false;
		try {
			database = rocksDbFactory.open(preparedDirectory, families);
			long stateNodeCount;
			ImportCounts blocks;
			ImportCounts entities;
			try (BoundedBatchWriter writer = new BoundedBatchWriter(database)) {
				stateNodeCount = importState(staged, families, writer);
				blocks = importBlocks(staged, verifiedArchive, families, writer);
				entities = importEntities(staged, verifiedArchive, families, writer);
				writer.flush();
				writeFinalMetadata(database, families, staged, verifiedArchive);
			}
			closeDatabase(database, families);
			database = null;
			if (!Files.isRegularFile(preparedDirectory.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalStateException("Prepared RocksDB has no CURRENT file after close");
			}
			postflightReadOnly(
					preparedDirectory, staged, verifiedArchive, stateNodeCount, blocks, entities);
			complete = true;
			return new DiskPreparedCoreSnapshotImport(preparedDirectory, verifiedArchive);
		} finally {
			if (database != null) {
				closeDatabase(database, families);
			}
			if (!complete) {
				deleteDirectory(preparedDirectory);
			}
		}
	}

	private void postflightReadOnly(
			Path preparedDirectory,
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified,
			long stateNodeCount,
			ImportCounts blocks,
			ImportCounts entities) throws Exception {
		try (ReadOnlyPreparedDatabase prepared = ReadOnlyPreparedDatabase.open(preparedDirectory)) {
			postflight(
					prepared.database(), prepared.families(), staged, verified,
					stateNodeCount, blocks, entities);
		}
	}

	private void validateCapability(
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified) {
		Objects.requireNonNull(staged, "staged");
		Objects.requireNonNull(verified, "verifiedArchive");
		if (!verified.activationEligible()
				|| !CheckpointSnapshotManifestCodec.signingHash(staged.stateSnapshot().domainManifest())
						.equals(verified.stateManifestSigningHash())
				|| !CoreSnapshotArchiveManifestCodec.signingHash(staged.archiveManifest())
						.equals(verified.archiveManifestSigningHash())
				|| staged.archiveManifest().blockChunks().size() != verified.chunkCount()
				|| staged.archiveManifest().entityChunks().size() != verified.entityChunkCount()
				|| staged.blockChunkFiles().size() != verified.chunkCount()
				|| staged.entityChunkFiles().size() != verified.entityChunkCount()) {
			throw new IllegalArgumentException("Prepared import requires the exact verified archive capability");
		}
		long declaredNodes = staged.stateSnapshot().domainManifest().chunks().stream()
				.mapToLong(descriptor -> descriptor.nodeCount()).sum();
		if (declaredNodes != verified.stateNodeCount()) {
			throw new IllegalArgumentException("Verified state-node capability does not match its manifest");
		}
	}

	private long importState(
			StagedCoreSnapshotArchiveDownload staged,
			RocksDbColumnFamilies families,
			BoundedBatchWriter writer) throws Exception {
		long totalNodes = 0;
		for (SnapshotChunkDescriptor descriptor : staged.stateSnapshot().domainManifest().chunks()) {
			Digest256 digest = new Digest256();
			updateInt(digest, descriptor.index());
			long bytes = 0;
			int nodes = 0;
			try (SnapshotNodeSource source = staged.stateSnapshot().chunkSource().open(descriptor)) {
				while (source.hasNext()) {
					SnapshotNode node = source.next();
					if (!Hash.hash(node.content()).equals(node.key())) {
						throw new IllegalStateException("Trie node changed after snapshot verification");
					}
					nodes++;
					bytes = Math.addExact(bytes, node.content().size());
					digest.update(node.key().toArray());
					updateInt(digest, node.content().size());
					digest.update(node.content().toArrayUnsafe());
					writer.put(families.stateTrie(), node.key().toArray(), node.content().toArray(),
							Hash.SIZE + node.content().size());
				}
			}
			if (nodes != descriptor.nodeCount() || bytes != descriptor.byteCount()
					|| !Hash.wrap(digest.digest()).equals(descriptor.contentHash())) {
				throw new IllegalStateException("State chunk changed after snapshot verification: "
						+ descriptor.index());
			}
			totalNodes = Math.addExact(totalNodes, nodes);
		}
		if (totalNodes != staged.stateSnapshot().domainManifest().chunks().stream()
				.mapToLong(descriptor -> descriptor.nodeCount()).sum()) {
			throw new IllegalStateException("Imported state node total differs from its manifest");
		}
		return totalNodes;
	}

	private ImportCounts importBlocks(
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified,
			RocksDbColumnFamilies families,
			BoundedBatchWriter writer) throws Exception {
		long expectedHeight = 0;
		Hash previousHash = null;
		BigInteger cumulativeDifficulty = BigInteger.ZERO;
		long transactionCount = 0;
		for (CoreSnapshotBlockChunkDescriptor descriptor : staged.archiveManifest().blockChunks()) {
			try (InputStream input = Files.newInputStream(
					staged.blockChunkFiles().get(descriptor.index()), LinkOption.NOFOLLOW_LINKS);
					CoreSnapshotBlockChunkCodec.Reader reader =
							CoreSnapshotBlockChunkCodec.openCompressed(input, descriptor)) {
				while (reader.hasNext()) {
					StoredBlock publisherBlock = reader.next();
					Block block = publisherBlock.getBlock();
					Hash hash = block.getHash();
					if (block.getHeight() != expectedHeight || !hash.equals(publisherBlock.getHash())
							|| expectedHeight == 0 && !Hash.ZERO.equals(block.getHeader().getPreviousHash())
							|| expectedHeight > 0 && !Objects.equals(previousHash, block.getHeader().getPreviousHash())
							|| !TxRootUtil.txRootHash(block.getTxs()).equals(block.getHeader().getTxRootHash())
							|| block.getHeader().getDifficulty() == null
							|| block.getHeader().getDifficulty().signum() <= 0) {
						throw new IllegalStateException("Block archive changed at height " + expectedHeight);
					}
					cumulativeDifficulty = cumulativeDifficulty.add(block.getHeader().getDifficulty());
					if (!cumulativeDifficulty.equals(publisherBlock.getCumulativeDifficulty())) {
						throw new IllegalStateException("Cumulative difficulty changed at height " + expectedHeight);
					}
					StoredBlock sanitized = StoredBlock.builder()
							.block(block)
							.cumulativeDifficulty(cumulativeDifficulty)
							.receivedAt(block.getHeader().getTimestamp())
							.receivedFrom(Address.ZERO)
							.connectedSource(expectedHeight == 0 ? GENESIS : SYNC)
							.identity(block.getHeader().getIdentity())
							// Events are archive-manifest-bound operational data. They never
							// participate in consensus, state-root or cumulative-work checks.
							.events(publisherBlock.getEvents())
							.computeIndexes()
							.build();
					Bytes encoded = StoredBlockEncoder.INSTANCE.encode(sanitized, StoredBlockVersion.V1);
					writer.put(families.blocks(), hash.toArray(), encoded.toArray(), Hash.SIZE + encoded.size());
					byte[] heightKey = Bytes.ofUnsignedLong(expectedHeight).toArray();
					writer.put(families.hashByHeight(), heightKey, hash.toArray(), heightKey.length + Hash.SIZE);
					for (Tx transaction : block.getTxs()) {
						writer.put(families.txIndex(), transaction.getHash().toArray(), hash.toArray(), Hash.SIZE * 2);
						transactionCount = Math.addExact(transactionCount, 1L);
					}
					previousHash = hash;
					expectedHeight++;
				}
				reader.finish();
			}
		}
		if (expectedHeight != verified.blockCount()
				|| expectedHeight != verified.checkpointHeight() + 1
				|| !Objects.equals(previousHash, verified.checkpointHash())
				|| !cumulativeDifficulty.equals(verified.checkpointCumulativeDifficulty())) {
			throw new IllegalStateException("Imported blocks did not reach the verified checkpoint");
		}
		return new ImportCounts(expectedHeight, transactionCount);
	}

	private ImportCounts importEntities(
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified,
			RocksDbColumnFamilies families,
			BoundedBatchWriter writer) throws Exception {
		long entries = 0;
		for (CoreSnapshotEntityChunkDescriptor descriptor : staged.archiveManifest().entityChunks()) {
			try (InputStream opened = Files.newInputStream(
					staged.entityChunkFiles().get(descriptor.index()), LinkOption.NOFOLLOW_LINKS);
					CoreSnapshotCompression.VerifiedInputStream verifiedInput =
							CoreSnapshotCompression.openVerifiedZstd(
									opened,
									descriptor.compressedByteCount(), descriptor.compressedContentHash(),
									descriptor.uncompressedByteCount(), descriptor.uncompressedContentHash());
					CoreSnapshotEntityChunkCodec.Reader reader =
							CoreSnapshotEntityChunkCodec.open(verifiedInput, descriptor)) {
				while (reader.hasNext()) {
					CoreSnapshotEntityEntry entry = reader.next();
					Object state = CoreSnapshotEntityStateCodec.decodeCanonical(
							descriptor.entityType(), entry.canonicalState());
					byte[] value = objectMapper.writeValueAsBytes(state);
					writer.put(entityFamily(families, descriptor.entityType()),
							entry.address().toArray(), value, Address.SIZE + value.length);
					entries = Math.addExact(entries, 1L);
				}
				reader.finish();
				verifiedInput.finish();
			}
		}
		if (entries != verified.entityEntryCount()) {
			throw new IllegalStateException("Imported entity count differs from verified sidecar");
		}
		return new ImportCounts(entries, 0);
	}

	private ColumnFamilyHandle entityFamily(
			RocksDbColumnFamilies families, CoreSnapshotEntityType type) {
		return switch (type) {
			case TOKEN -> families.tokens();
			case AUTHORITY -> families.authorities();
			case VALIDATOR -> families.validators();
		};
	}

	private void writeFinalMetadata(
			RocksDB database,
			RocksDbColumnFamilies families,
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified) throws RocksDBException {
		CoreSnapshotCheckpointFloor floor = new CoreSnapshotCheckpointFloor(
				verified.checkpointHeight(), verified.checkpointHash(), verified.checkpointStateRoot(),
				verified.checkpointCumulativeDifficulty(), verified.stateManifestSigningHash(),
				verified.archiveManifestSigningHash());
		try (WriteBatch metadata = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
			metadata.put(families.metadata(), RocksChainIdentityStore.STORAGE_KEY,
					StoredChainIdentityCodec.encode(staged.stateSnapshot().domainManifest().chainIdentity()));
			metadata.put(families.metadata(), CoreSnapshotCheckpointFloorCodec.STORAGE_KEY,
					CoreSnapshotCheckpointFloorCodec.encode(floor));
			metadata.put(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH,
					verified.checkpointHash().toArray());
			database.write(options, metadata);
		}
	}

	private void postflight(
			RocksDB database,
			RocksDbColumnFamilies families,
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified,
			long importedStateNodes,
			ImportCounts blocks,
			ImportCounts entities) throws RocksDBException {
		byte[] latest = database.get(families.metadata(), RocksDbColumnFamilies.KEY_LATEST_BLOCK_HASH);
		byte[] floorBytes = database.get(families.metadata(), CoreSnapshotCheckpointFloorCodec.STORAGE_KEY);
		byte[] identity = database.get(families.metadata(), RocksChainIdentityStore.STORAGE_KEY);
		CoreSnapshotCheckpointFloor floor = CoreSnapshotCheckpointFloorCodec.decode(floorBytes);
		if (!verified.checkpointHash().equals(Hash.wrap(latest))
				|| !floor.equals(new CoreSnapshotCheckpointFloor(
						verified.checkpointHeight(), verified.checkpointHash(), verified.checkpointStateRoot(),
						verified.checkpointCumulativeDifficulty(), verified.stateManifestSigningHash(),
						verified.archiveManifestSigningHash()))
				|| !StoredChainIdentityCodec.decode(identity)
						.equals(staged.stateSnapshot().domainManifest().chainIdentity())) {
			throw new IllegalStateException("Prepared snapshot metadata postflight failed");
		}

		byte[] checkpointBlock = database.get(families.blocks(), verified.checkpointHash().toArray());
		StoredBlock checkpoint = checkpointBlock == null ? null
				: StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(checkpointBlock));
		if (checkpoint == null || !checkpoint.getHash().equals(verified.checkpointHash())
				|| !checkpoint.getCumulativeDifficulty().equals(verified.checkpointCumulativeDifficulty())
				|| !checkpoint.getBlock().getHeader().getStateRootHash().equals(verified.checkpointStateRoot())) {
			throw new IllegalStateException("Prepared checkpoint block postflight failed");
		}
		if (!verified.checkpointStateRoot().equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)
				&& database.get(families.stateTrie(), verified.checkpointStateRoot().toArray()) == null) {
			throw new IllegalStateException("Prepared checkpoint trie root is missing");
		}
		ImportCounts canonicalIndexes = verifyCanonicalIndexes(
				database, families, verified.checkpointHeight(), verified.checkpointCumulativeDifficulty());
		long transactionCount = countEntries(database.newIterator(families.txIndex()));
		long entityCount = countEntries(database.newIterator(families.tokens()))
				+ countEntries(database.newIterator(families.authorities()))
				+ countEntries(database.newIterator(families.validators()));
		long stateNodeCount = countEntries(database.newIterator(families.stateTrie()));
		if (canonicalIndexes.primaryCount() != blocks.primaryCount()
				|| canonicalIndexes.secondaryCount() != blocks.secondaryCount()
				|| transactionCount != blocks.secondaryCount()
				|| stateNodeCount != importedStateNodes
				|| stateNodeCount != verified.stateNodeCount()
				|| entityCount != entities.primaryCount()) {
			throw new IllegalStateException("Prepared snapshot index cardinality postflight failed");
		}
	}

	private ImportCounts verifyCanonicalIndexes(
			RocksDB database,
			RocksDbColumnFamilies families,
			long checkpointHeight,
			BigInteger checkpointDifficulty) throws RocksDBException {
		long expectedHeight = 0;
		long transactionCount = 0;
		Hash previousHash = null;
		BigInteger cumulativeDifficulty = BigInteger.ZERO;
		try (RocksIterator iterator = database.newIterator(families.hashByHeight())) {
			iterator.seekToFirst();
			while (iterator.isValid()) {
				byte[] expectedKey = Bytes.ofUnsignedLong(expectedHeight).toArray();
				byte[] encodedBlock = database.get(families.blocks(), iterator.value());
				if (!Arrays.equals(iterator.key(), expectedKey)
						|| iterator.value().length != Hash.SIZE
						|| encodedBlock == null) {
					throw new IllegalStateException("Prepared canonical height index is inconsistent");
				}
				StoredBlock stored = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(encodedBlock));
				Hash hash = Hash.wrap(iterator.value());
				Block block = stored.getBlock();
				cumulativeDifficulty = cumulativeDifficulty.add(block.getHeader().getDifficulty());
				if (stored.getHeight() != expectedHeight || !stored.getHash().equals(hash)
						|| expectedHeight == 0 && !Hash.ZERO.equals(block.getHeader().getPreviousHash())
						|| expectedHeight > 0 && !Objects.equals(previousHash, block.getHeader().getPreviousHash())
						|| !cumulativeDifficulty.equals(stored.getCumulativeDifficulty())
						|| !TxRootUtil.txRootHash(block.getTxs()).equals(block.getHeader().getTxRootHash())) {
					throw new IllegalStateException("Prepared canonical block is inconsistent at " + expectedHeight);
				}
				for (Tx transaction : block.getTxs()) {
					byte[] indexedBlock = database.get(families.txIndex(), transaction.getHash().toArray());
					if (!Arrays.equals(indexedBlock, hash.toArray())) {
						throw new IllegalStateException("Prepared transaction index is inconsistent");
					}
					transactionCount = Math.addExact(transactionCount, 1L);
				}
				previousHash = hash;
				expectedHeight++;
				iterator.next();
			}
			iterator.status();
		}
		if (expectedHeight != checkpointHeight + 1
				|| !cumulativeDifficulty.equals(checkpointDifficulty)) {
			throw new IllegalStateException("Prepared canonical height index is incomplete");
		}
		return new ImportCounts(expectedHeight, transactionCount);
	}

	private long countEntries(RocksIterator iterator) throws RocksDBException {
		try (iterator) {
			long count = 0;
			for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
				count = Math.addExact(count, 1L);
			}
			iterator.status();
			return count;
		}
	}

	private void closeDatabase(RocksDB database, RocksDbColumnFamilies families) {
		families.getHandles().values().forEach(ColumnFamilyHandle::close);
		database.close();
	}

	private void deleteDirectory(Path directory) throws IOException {
		if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void updateInt(Digest256 digest, int value) {
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private record ImportCounts(long primaryCount, long secondaryCount) {
	}

	static final class BoundedBatchWriter implements AutoCloseable {

		private final RocksDB database;
		private final WriteOptions options = new WriteOptions().setSync(false);
		private WriteBatch batch = new WriteBatch();
		private int entries;
		private long bytes;

		BoundedBatchWriter(RocksDB database) {
			this.database = database;
		}

		void put(ColumnFamilyHandle family, byte[] key, byte[] value, long encodedBytes)
				throws RocksDBException {
			if (encodedBytes <= 0 || encodedBytes > MAX_BATCH_BYTES) {
				throw new IllegalArgumentException("Prepared import entry exceeds batch byte limit");
			}
			if (entries > 0 && (entries >= MAX_BATCH_ENTRIES || encodedBytes > MAX_BATCH_BYTES - bytes)) {
				flush();
			}
			batch.put(family, key, value);
			entries++;
			bytes = Math.addExact(bytes, encodedBytes);
		}

		void flush() throws RocksDBException {
			if (entries == 0) {
				return;
			}
			database.write(options, batch);
			batch.close();
			batch = new WriteBatch();
			entries = 0;
			bytes = 0;
		}

		@Override
		public void close() throws RocksDBException {
			try {
				flush();
			} finally {
				batch.close();
				options.close();
			}
		}
	}

	private static final class ReadOnlyPreparedDatabase implements AutoCloseable {

		private final RocksDB database;
		private final RocksDbColumnFamilies families;
		private final List<ColumnFamilyHandle> handles;
		private final List<ColumnFamilyOptions> familyOptions;
		private final DBOptions databaseOptions;

		private ReadOnlyPreparedDatabase(
				RocksDB database,
				RocksDbColumnFamilies families,
				List<ColumnFamilyHandle> handles,
				List<ColumnFamilyOptions> familyOptions,
				DBOptions databaseOptions) {
			this.database = database;
			this.families = families;
			this.handles = handles;
			this.familyOptions = familyOptions;
			this.databaseOptions = databaseOptions;
		}

		static ReadOnlyPreparedDatabase open(Path directory) throws RocksDBException {
			List<ColumnFamilyOptions> options = new ArrayList<>();
			List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
			for (String name : BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES) {
				ColumnFamilyOptions familyOptions = new ColumnFamilyOptions();
				options.add(familyOptions);
				byte[] encodedName = BlockchainRocksDbFactory.DEFAULT_COLUMN_FAMILY.equals(name)
						? RocksDB.DEFAULT_COLUMN_FAMILY : name.getBytes(UTF_8);
				descriptors.add(new ColumnFamilyDescriptor(encodedName, familyOptions));
			}
			DBOptions databaseOptions = new DBOptions().setCreateIfMissing(false);
			List<ColumnFamilyHandle> handles = new ArrayList<>();
			RocksDB database = null;
			try {
				database = RocksDB.openReadOnly(
						databaseOptions, directory.toString(), descriptors, handles);
				RocksDbColumnFamilies families = new RocksDbColumnFamilies();
				for (int index = 0; index < BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES.size(); index++) {
					families.addHandle(BlockchainRocksDbFactory.COLUMN_FAMILY_NAMES.get(index), handles.get(index));
				}
				return new ReadOnlyPreparedDatabase(database, families, handles, options, databaseOptions);
			} catch (RocksDBException | RuntimeException e) {
				handles.forEach(ColumnFamilyHandle::close);
				if (database != null) {
					database.close();
				}
				options.forEach(ColumnFamilyOptions::close);
				databaseOptions.close();
				throw e;
			}
		}

		RocksDB database() {
			return database;
		}

		RocksDbColumnFamilies families() {
			return families;
		}

		@Override
		public void close() {
			handles.forEach(ColumnFamilyHandle::close);
			database.close();
			familyOptions.forEach(ColumnFamilyOptions::close);
			databaseOptions.close();
		}
	}
}
