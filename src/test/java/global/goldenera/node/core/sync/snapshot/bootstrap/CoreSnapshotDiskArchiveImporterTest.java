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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.ethereum.Wei;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.BlockHeaderImpl;
import global.goldenera.cryptoj.common.BlockImpl;
import global.goldenera.cryptoj.common.state.AuthorityState;
import global.goldenera.cryptoj.common.state.TokenState;
import global.goldenera.cryptoj.common.state.ValidatorState;
import global.goldenera.cryptoj.common.state.impl.TokenStateImpl;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.datatypes.Signature;
import global.goldenera.cryptoj.enums.BlockVersion;
import global.goldenera.cryptoj.enums.state.TokenStateVersion;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.patricia.SimpleMerklePatriciaTrie;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.events.BlockConnectedEvent.ConnectedSource;
import global.goldenera.node.core.properties.BlockchainDbProperties;
import global.goldenera.node.core.storage.blockchain.BlockchainRocksDbFactory;
import global.goldenera.node.core.storage.blockchain.RocksDbColumnFamilies;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent.BlockReward;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.blockchain.serialization.StoredBlockDecoder;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotVerifier;
import global.goldenera.node.core.sync.snapshot.SnapshotChunk;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.SnapshotHeader;
import global.goldenera.node.core.sync.snapshot.SnapshotHeaderSegment;
import global.goldenera.node.core.sync.snapshot.SnapshotNode;
import global.goldenera.node.core.sync.snapshot.SnapshotDiskSpaceBudget;
import global.goldenera.node.core.sync.snapshot.SnapshotVerificationException;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveVerifier;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotChunkCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityIndexExporter;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityIndexSource;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityStateCodec;
import global.goldenera.node.core.sync.snapshot.archive.VerifiedCoreSnapshotArchive;
import global.goldenera.node.core.sync.snapshot.transport.CoreSnapshotArchiveTransportManifest;
import global.goldenera.node.core.sync.snapshot.transport.BinarySnapshotNodeSource;
import global.goldenera.node.core.sync.snapshot.transport.SnapshotTransportManifest;
import global.goldenera.node.core.sync.snapshot.transport.StagedCoreSnapshotArchiveDownload;
import global.goldenera.node.core.sync.snapshot.transport.StagedSnapshotDownload;

class CoreSnapshotDiskArchiveImporterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void buildsClosedCanonicalDatabaseAndPreservesManifestBoundPublisherEvents() throws Exception {
		Fixture fixture = fixture();
		BlockchainDbProperties properties = properties(temporaryDirectory.resolve("live-blockchain"));
		BlockchainRocksDbFactory factory = new BlockchainRocksDbFactory(properties);
		CoreSnapshotDiskArchiveImporter importer =
				new CoreSnapshotDiskArchiveImporter(properties, factory, new ObjectMapper().findAndRegisterModules());

		try (DiskPreparedCoreSnapshotImport prepared = (DiskPreparedCoreSnapshotImport) importer.prepare(
				fixture.staged(), fixture.verified())) {
			assertThat(prepared.databaseDirectory()).isDirectory();
			assertThat(prepared.databaseDirectory().resolve("CURRENT")).isRegularFile();
			RocksDbColumnFamilies families = new RocksDbColumnFamilies();
			RocksDB database = factory.open(prepared.databaseDirectory(), families);
			try {
				byte[] encoded = database.get(families.blocks(), fixture.verified().checkpointHash().toArray());
				StoredBlock imported = StoredBlockDecoder.INSTANCE.decode(Bytes.wrap(encoded));
				assertThat(imported.getEvents()).containsExactly(
						new BlockReward(Address.ZERO, Address.ZERO, Wei.valueOf(1), null));
				assertThat(imported.getCumulativeDifficulty()).isEqualTo(BigInteger.ONE);
				assertThat(database.get(families.tokens(), Address.NATIVE_TOKEN.toArray())).isNotNull();
				assertThat(database.get(
						families.metadata(), CoreSnapshotCheckpointFloorCodec.STORAGE_KEY)).isNotNull();
			} finally {
				families.getHandles().values().forEach(ColumnFamilyHandle::close);
				database.close();
			}
			Path preparedPath = prepared.databaseDirectory();
			prepared.close();
			assertThat(preparedPath).doesNotExist();
		}
	}

	@Test
	void rejectsInsufficientPeakSpaceBeforeOpeningPreparedRocksDb() throws Exception {
		Fixture fixture = fixture();
		BlockchainDbProperties properties = properties(temporaryDirectory.resolve("low-space-live"));
		BlockchainRocksDbFactory factory = mock(BlockchainRocksDbFactory.class);
		CoreSnapshotDiskArchiveImporter importer = new CoreSnapshotDiskArchiveImporter(
				properties, factory, new ObjectMapper().findAndRegisterModules(),
				new SnapshotDiskSpaceBudget(path -> 0));

		assertThatThrownBy(() -> importer.prepare(fixture.staged(), fixture.verified()))
				.isInstanceOf(SnapshotVerificationException.class)
				.hasMessageContaining("Insufficient peak disk space");
		verifyNoInteractions(factory);
		try (var paths = Files.list(temporaryDirectory)) {
			assertThat(paths.map(path -> path.getFileName().toString()).toList())
					.noneMatch(name -> name.startsWith(".low-space-live-snapshot-prepared-"));
		}
	}

	private Fixture fixture() throws Exception {
		Path staging = Files.createDirectory(temporaryDirectory.resolve("download"));
		TokenState token = TokenStateImpl.builder()
				.version(TokenStateVersion.V1)
				.name("GoldenEra")
				.smallestUnitName("GE")
				.numberOfDecimals(18)
				.userBurnable(true)
				.totalSupply(Wei.valueOf(1))
				.originTxHash(Hash.ZERO)
				.updatedByTxHash(Hash.ZERO)
				.updatedAtBlockHeight(0)
				.updatedAtTimestamp(Instant.EPOCH)
				.build();
		SimpleMerklePatriciaTrie<Bytes, Bytes> tokenTrie = new SimpleMerklePatriciaTrie<>(value -> value);
		tokenTrie.put(Address.NATIVE_TOKEN, CoreSnapshotEntityStateCodec.encodeToken(token));
		Hash tokenRoot = Hash.wrap(tokenTrie.getRootHash());
		SimpleMerklePatriciaTrie<Bytes, Bytes> mainTrie = new SimpleMerklePatriciaTrie<>(value -> value);
		mainTrie.put(WorldStateFactory.KEY_TOKEN, tokenRoot);
		Hash stateRoot = Hash.wrap(mainTrie.getRootHash());
		Map<Hash, Bytes> trieNodes = new LinkedHashMap<>();
		collect(mainTrie, stateRoot, trieNodes);
		collect(tokenTrie, tokenRoot, trieNodes);
		SnapshotChunk stateChunk = new SnapshotChunk(
				0, trieNodes.entrySet().stream()
						.map(entry -> new SnapshotNode(entry.getKey(), entry.getValue()))
						.toList());
		long stateBytes = stateChunk.nodes().stream().mapToLong(node -> node.content().size()).sum();
		SnapshotChunkDescriptor stateDescriptor = new SnapshotChunkDescriptor(
				0, "state-0", "https://snapshot.invalid/chunks/0", stateChunk.nodes().size(), stateBytes,
				CheckpointSnapshotVerifier.chunkContentHash(stateChunk));
		Path stateChunkFile = staging.resolve("chunk-00000.bin");
		try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(stateChunkFile))) {
			output.writeInt(0);
			for (SnapshotNode node : stateChunk.nodes()) {
				output.write(node.key().toArray());
				output.writeInt(node.content().size());
				output.write(node.content().toArrayUnsafe());
			}
		}
		CoreSnapshotEntityIndexSource entitySource = new CoreSnapshotEntityIndexSource() {
			@Override
			public Map<Address, TokenState> tokens() {
				return Map.of(Address.NATIVE_TOKEN, token);
			}

			@Override
			public Map<Address, AuthorityState> authorities() {
				return Map.of();
			}

			@Override
			public Map<Address, ValidatorState> validators() {
				return Map.of();
			}
		};
		Path entityDirectory = Files.createDirectory(staging.resolve("entities"));
		CoreSnapshotEntityIndexExporter.ExportResult entityExport =
				new CoreSnapshotEntityIndexExporter().export(entitySource, entityDirectory);
		BlockHeader header = BlockHeaderImpl.builder()
				.version(BlockVersion.V1)
				.height(0)
				.timestamp(Instant.ofEpochSecond(1))
				.previousHash(Hash.ZERO)
				.txRootHash(Hash.ZERO)
				.stateRootHash(stateRoot)
				.difficulty(BigInteger.ONE)
				.coinbase(Address.ZERO)
				.nonce(0)
				.signature(Signature.ZERO)
				.build();
		Block block = BlockImpl.builder().header(header).txs(List.of()).build();
		StoredBlock publisherBlock = StoredBlock.builder()
				.block(block)
				.cumulativeDifficulty(BigInteger.ONE)
				.receivedAt(header.getTimestamp())
				.receivedFrom(Address.ZERO)
				.connectedSource(ConnectedSource.GENESIS)
				.identity(header.getIdentity())
				.events(List.of(new BlockReward(Address.ZERO, Address.ZERO, Wei.valueOf(1), null)))
				.computeIndexes()
				.build();
		StoredChainIdentity identity = new StoredChainIdentity(
				StoredChainIdentity.CURRENT_FORMAT_VERSION, 1, "testnet", block.getHash().toHexString(), null);
		SnapshotHeader snapshotHeader = new SnapshotHeader(block.getHash(), header, BigInteger.ONE);
		CheckpointSnapshotManifest stateManifest = new CheckpointSnapshotManifest(
				CheckpointSnapshotLimits.FORMAT_VERSION, identity.carrierNetworkCode(), identity, 0,
				block.getHash(), stateRoot, BigInteger.ONE,
				new SnapshotHeaderSegment(Hash.ZERO, BigInteger.ZERO, List.of(snapshotHeader)),
				List.of(stateDescriptor));

		Bytes rawChunk = CoreSnapshotBlockChunkCodec.encodeChunk(0, List.of(publisherBlock));
		ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
		CoreSnapshotCompression.writeZstd(
				new ByteArrayInputStream(rawChunk.toArrayUnsafe()), compressedOutput);
		byte[] compressed = compressedOutput.toByteArray();
		CoreSnapshotBlockChunkDescriptor blockDescriptor = new CoreSnapshotBlockChunkDescriptor(
				0, 0, 0, 1, CoreSnapshotChunkCompression.ZSTD,
				compressed.length, Hash.hash(Bytes.wrap(compressed)), rawChunk.size(), Hash.hash(rawChunk));
		CoreSnapshotArchiveManifest archiveManifest = new CoreSnapshotArchiveManifest(
				CoreSnapshotArchiveLimits.FORMAT_VERSION,
				CheckpointSnapshotManifestCodec.signingHash(stateManifest), List.of(blockDescriptor),
				entityExport.descriptors());

		CheckpointRegistry checkpoints = mock(CheckpointRegistry.class);
		when(checkpoints.isCheckpoint(0)).thenReturn(true);
		when(checkpoints.verifyCheckpoint(0, block.getHash())).thenReturn(true);
		CheckpointSnapshotVerifier stateVerifier = new CheckpointSnapshotVerifier(checkpoints, identity, 10);
		VerifiedCoreSnapshotArchive verified = new CoreSnapshotArchiveVerifier(stateVerifier).verify(
				archiveManifest, stateManifest,
				descriptor -> new BinarySnapshotNodeSource(
						stateChunkFile, descriptor),
				descriptor -> new ByteArrayInputStream(compressed),
				descriptor -> Files.newInputStream(entityExport.chunkFiles().get(descriptor.index())));

		Path blockFile = staging.resolve("archive-chunk-00000.bin");
		Files.write(blockFile, compressed);
		StagedSnapshotDownload state = new StagedSnapshotDownload(
				SnapshotTransportManifest.from(stateManifest), stateManifest, staging,
				staging.resolve("manifest.json"), List.of(stateChunkFile));
		StagedCoreSnapshotArchiveDownload staged = new StagedCoreSnapshotArchiveDownload(
				state, CoreSnapshotArchiveTransportManifest.from(archiveManifest), archiveManifest,
				staging.resolve("archive-manifest.json"), List.of(blockFile), entityExport.chunkFiles());
		return new Fixture(staged, verified);
	}

	private void collect(MerkleTrie<Bytes, Bytes> trie, Hash root, Map<Hash, Bytes> nodes) {
		trie.visitAll(node -> {
			if (node.getHash().equals(root) || node.isReferencedByHash()) {
				nodes.put(Hash.wrap(node.getHash()), node.getEncodedBytes());
			}
		});
	}

	private BlockchainDbProperties properties(Path databasePath) {
		BlockchainDbProperties properties = new BlockchainDbProperties();
		properties.setPath(databasePath.toString());
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
			StagedCoreSnapshotArchiveDownload staged,
			VerifiedCoreSnapshotArchive verified) {
	}
}
