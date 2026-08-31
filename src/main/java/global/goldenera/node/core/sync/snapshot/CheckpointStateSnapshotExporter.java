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
package global.goldenera.node.core.sync.snapshot;

import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.FORMAT_VERSION;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_CHUNK_BYTES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_CHUNK_COUNT;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_HEADER_COUNT;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_NODES_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_NODE_BYTES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_TOTAL_BYTES;
import static global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits.MAX_TOTAL_NODES;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.merkletrie.MerkleTrie;
import global.goldenera.merkletrie.Node;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.core.state.WorldStateFactory;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.transport.SnapshotTransportManifest;

/**
 * Explicit, offline snapshot generation service. It is deliberately not a web
 * endpoint: request threads only serve immutable artifacts created beforehand.
 */
public final class CheckpointStateSnapshotExporter {

	private static final String MANIFEST_FILE = "manifest.json";
	private static final String VERSION_URL_PATH = "/api/core/v1/sync/snapshots/checkpoint/versions/";

	private final SnapshotAnchorPolicy anchorPolicy;
	private final ChainQuery chainQuery;
	private final WorldStateFactory worldStateFactory;
	private final StoredChainIdentity chainIdentity;
	private final long randomXEpochLength;
	private final URI publicOrigin;
	private final ObjectMapper objectMapper;

	public CheckpointStateSnapshotExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			WorldStateFactory worldStateFactory,
			StoredChainIdentity chainIdentity,
			long randomXEpochLength,
			URI publicOrigin,
			ObjectMapper objectMapper) {
		this(checkpointRegistry, chainQuery, worldStateFactory, chainIdentity, randomXEpochLength,
				publicOrigin, objectMapper, new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	public CheckpointStateSnapshotExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			WorldStateFactory worldStateFactory,
			StoredChainIdentity chainIdentity,
			long randomXEpochLength,
			URI publicOrigin,
			ObjectMapper objectMapper,
			SnapshotAnchorPolicy anchorPolicy) {
		Objects.requireNonNull(checkpointRegistry, "checkpointRegistry");
		this.anchorPolicy = Objects.requireNonNull(anchorPolicy, "anchorPolicy");
		this.chainQuery = Objects.requireNonNull(chainQuery, "chainQuery");
		this.worldStateFactory = Objects.requireNonNull(worldStateFactory, "worldStateFactory");
		this.chainIdentity = Objects.requireNonNull(chainIdentity, "chainIdentity");
		if (randomXEpochLength <= 0) {
			throw new IllegalArgumentException("RandomX epoch length must be positive");
		}
		this.randomXEpochLength = randomXEpochLength;
		this.publicOrigin = validatePublicOrigin(publicOrigin);
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	/**
	 * Exports into an existing, empty, absolute directory. On every failure all
	 * files owned by this invocation are removed and the directory remains empty.
	 */
	public ExportResult export(long checkpointHeight, Path outputDirectory) {
		Path output = validateEmptyOutputDirectory(outputDirectory);
		List<Path> createdFiles = new ArrayList<>();
		try {
			ExportCheckpoint checkpoint = loadCheckpoint(checkpointHeight);
			SnapshotHeaderSegment segment = loadHeaderSegment(checkpointHeight);
			WorldState worldState = worldStateFactory.createForValidation(checkpoint.stateRoot());
			if (!Hash.wrap(worldState.getMainTrie().getRootHash()).equals(checkpoint.stateRoot())) {
				throw failure("Historical WorldState root does not match the checkpoint header");
			}

			List<SnapshotChunkDescriptor> descriptors;
			List<Path> chunkFiles;
			try (DiskSeenSet seen = DiskSeenSet.create();
					ChunkEmitter emitter = new ChunkEmitter(
							output, createdFiles, checkpointHeight, checkpoint.hash())) {
				emitWorldState(worldState, seen, emitter);
				emitter.finish();
				descriptors = emitter.descriptors();
				chunkFiles = emitter.chunkFiles();
			}

			// Do not publish an artifact whose checkpoint stopped being canonical while it was generated.
			assertSegmentStillCanonical(segment);
			assertCanonical(checkpointHeight, checkpoint.hash());

			CheckpointSnapshotManifest manifest = new CheckpointSnapshotManifest(
					FORMAT_VERSION,
					chainIdentity.carrierNetworkCode(),
					chainIdentity,
					checkpointHeight,
					checkpoint.hash(),
					checkpoint.stateRoot(),
					checkpoint.cumulativeDifficulty(),
					segment,
					descriptors);
			Bytes canonicalBytes = CheckpointSnapshotManifestCodec.canonicalBytes(manifest);
			Hash signingHash = CheckpointSnapshotManifestCodec.signingHash(manifest);
			byte[] envelope = objectMapper.writeValueAsBytes(SnapshotTransportManifest.from(manifest));
			Path manifestFile = writeAtomic(output, MANIFEST_FILE, envelope, createdFiles);
			return new ExportResult(manifest, canonicalBytes, signingHash, manifestFile, chunkFiles);
		} catch (SnapshotExportException e) {
			cleanup(createdFiles);
			throw e;
		} catch (Exception e) {
			cleanup(createdFiles);
			throw failure("Checkpoint snapshot export failed", e);
		}
	}

	private ExportCheckpoint loadCheckpoint(long height) {
		if (height < 0) {
			throw failure("Snapshot height cannot be negative: " + height);
		}
		StoredBlock stored = chainQuery.getStoredBlockByHeight(height)
				.orElseThrow(() -> failure("Canonical checkpoint block is not stored at height " + height));
		if (stored.getBlock() == null || stored.getBlock().getHeader() == null || stored.getHash() == null
				|| stored.getCumulativeDifficulty() == null || stored.getCumulativeDifficulty().signum() <= 0) {
			throw failure("Stored checkpoint is missing block/hash/cumulative difficulty");
		}
		BlockHeader header = stored.getBlock().getHeader();
		if (header.getHeight() != height || header.getStateRootHash() == null
				|| !stored.getHash().equals(header.getHash())) {
			throw failure("Stored checkpoint block metadata is inconsistent");
		}
		assertCanonical(height, stored.getHash());
		anchorPolicy.verify(height, stored.getHash(), chainIdentity);
		return new ExportCheckpoint(stored.getHash(), header.getStateRootHash(), stored.getCumulativeDifficulty());
	}

	private SnapshotHeaderSegment loadHeaderSegment(long checkpointHeight) {
		if (checkpointHeight == Long.MAX_VALUE) {
			throw failure("Checkpoint height is too large");
		}
		long startHeight = requiredRandomXSeedHeight(checkpointHeight + 1);
		long count = Math.addExact(Math.subtractExact(checkpointHeight, startHeight), 1L);
		if (count <= 0 || count > MAX_HEADER_COUNT) {
			throw failure("Required RandomX header segment exceeds configured limits");
		}
		Hash parentHash = Hash.ZERO;
		BigInteger parentDifficulty = BigInteger.ZERO;
		if (startHeight > 0) {
			StoredBlock parent = chainQuery.getStoredBlockHeaderByHeight(startHeight - 1)
					.orElseThrow(() -> failure("Missing local cumulative-work anchor for snapshot header segment"));
			if (parent.getHash() == null || parent.getCumulativeDifficulty() == null
					|| parent.getCumulativeDifficulty().signum() <= 0) {
				throw failure("Local cumulative-work anchor is incomplete");
			}
			assertCanonical(startHeight - 1, parent.getHash());
			parentHash = parent.getHash();
			parentDifficulty = parent.getCumulativeDifficulty();
		}

		List<StoredBlock> storedHeaders = chainQuery.findStoredBlockHeadersByHeightRange(startHeight, checkpointHeight);
		if (storedHeaders.size() != count) {
			throw failure("Canonical header segment is missing heights");
		}
		List<SnapshotHeader> headers = new ArrayList<>(storedHeaders.size());
		Hash previousHash = parentHash;
		BigInteger cumulative = parentDifficulty;
		long expectedHeight = startHeight;
		for (StoredBlock stored : storedHeaders) {
			if (stored == null || stored.getBlock() == null || stored.getBlock().getHeader() == null
					|| stored.getHash() == null || stored.getCumulativeDifficulty() == null) {
				throw failure("Canonical header segment contains incomplete data");
			}
			BlockHeader header = stored.getBlock().getHeader();
			if (header.getHeight() != expectedHeight || !previousHash.equals(header.getPreviousHash())
					|| header.getDifficulty() == null || header.getDifficulty().signum() <= 0
					|| !stored.getHash().equals(header.getHash())) {
				throw failure("Canonical header segment is inconsistent at height " + expectedHeight);
			}
			assertCanonical(expectedHeight, stored.getHash());
			cumulative = cumulative.add(header.getDifficulty());
			if (!cumulative.equals(stored.getCumulativeDifficulty())) {
				throw failure("Canonical cumulative difficulty is inconsistent at height " + expectedHeight);
			}
			headers.add(new SnapshotHeader(stored.getHash(), header, stored.getCumulativeDifficulty()));
			previousHash = stored.getHash();
			expectedHeight++;
		}
		return new SnapshotHeaderSegment(parentHash, parentDifficulty, headers);
	}

	private long requiredRandomXSeedHeight(long firstPostCheckpointHeight) {
		long epoch = firstPostCheckpointHeight / randomXEpochLength;
		return epoch == 0 ? 0 : Math.multiplyExact(epoch - 1, randomXEpochLength);
	}

	private void emitWorldState(WorldState state, DiskSeenSet seen, ChunkEmitter emitter) {
		emitTrie(state.getMainTrie(), seen, emitter);
		emitTrie(state.getBalanceTrie(), seen, emitter);
		emitTrie(state.getNonceTrie(), seen, emitter);
		emitTrie(state.getAuthorityTrie(), seen, emitter);
		emitTrie(state.getValidatorTrie(), seen, emitter);
		emitTrie(state.getAddressAliasTrie(), seen, emitter);
		emitTrie(state.getBipStateTrie(), seen, emitter);
		emitTrie(state.getNetworkParamsTrie(), seen, emitter);
		emitTrie(state.getMiningWindowTrie(), seen, emitter);
		emitTrie(state.getMiningRewardMaturityTrie(), seen, emitter);
		emitTrie(state.getTokenTrie(), seen, emitter);
	}

	private <V> void emitTrie(MerkleTrie<Bytes, V> trie, DiskSeenSet seen, ChunkEmitter emitter) {
		Hash root = Hash.wrap(trie.getRootHash());
		if (root.equals(Hash.wrap(MerkleTrie.EMPTY_TRIE_NODE_HASH))) {
			return; // The canonical empty node is implicit and is never loaded from storage.
		}
		try {
			trie.visitAll(node -> {
				if (node.getHash().equals(root) || node.isReferencedByHash()) {
					emitNode(node, seen, emitter);
				}
			});
		} catch (SnapshotExportException e) {
			throw e;
		} catch (RuntimeException e) {
			throw failure("Historical WorldState trie traversal failed", e);
		}
	}

	private void emitNode(Node<?> node, DiskSeenSet seen, ChunkEmitter emitter) {
		Bytes content = node.getEncodedBytes();
		Hash key = Hash.wrap(node.getHash());
		if (content.isEmpty() || content.size() > MAX_NODE_BYTES || !Hash.hash(content).equals(key)) {
			throw failure("Historical WorldState contains a corrupt or oversized hash-referenced trie node");
		}
		if (seen.add(key)) {
			emitter.write(key, content);
		}
	}

	private void assertSegmentStillCanonical(SnapshotHeaderSegment segment) {
		for (SnapshotHeader header : segment.headers()) {
			assertCanonical(header.header().getHeight(), header.declaredHash());
		}
		if (segment.headers().getFirst().header().getHeight() > 0) {
			assertCanonical(segment.headers().getFirst().header().getHeight() - 1, segment.parentHash());
		}
	}

	private void assertCanonical(long height, Hash expectedHash) {
		Hash canonical = chainQuery.getBlockHashByHeight(height)
				.orElseThrow(() -> failure("Canonical height index is missing height " + height));
		if (!canonical.equals(expectedHash)) {
			throw failure("Chain reorganized while exporting checkpoint snapshot at height " + height);
		}
	}

	private static URI validatePublicOrigin(URI origin) {
		Objects.requireNonNull(origin, "publicOrigin");
		if (!origin.isAbsolute() || origin.getHost() == null || origin.getUserInfo() != null
				|| origin.getQuery() != null || origin.getFragment() != null
				|| !("https".equalsIgnoreCase(origin.getScheme()) || "http".equalsIgnoreCase(origin.getScheme()))) {
			throw new IllegalArgumentException("Snapshot public origin must be an absolute HTTP(S) URI");
		}
		try {
			return new URI(origin.getScheme(), null, origin.getHost(), origin.getPort(), "/", null, null);
		} catch (Exception e) {
			throw new IllegalArgumentException("Snapshot public origin is invalid", e);
		}
	}

	private static Path validateEmptyOutputDirectory(Path directory) {
		Objects.requireNonNull(directory, "outputDirectory");
		if (!directory.isAbsolute() || !directory.equals(directory.normalize())) {
			throw failure("Snapshot output must be a normalized absolute directory");
		}
		try {
			Path current = directory.getRoot();
			for (Path component : directory) {
				current = current.resolve(component);
				if (Files.isSymbolicLink(current)) {
					throw failure("Snapshot output path must not contain symbolic links");
				}
			}
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
					|| !directory.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(directory)) {
				throw failure("Snapshot output must be an existing real directory");
			}
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
				if (entries.iterator().hasNext()) {
					throw failure("Snapshot output directory must be empty");
				}
			}
			return directory;
		} catch (IOException e) {
			throw failure("Cannot validate snapshot output directory", e);
		}
	}

	private static Path writeAtomic(Path directory, String fileName, byte[] content, List<Path> createdFiles)
			throws IOException {
		Path partial = directory.resolve(fileName + ".part");
		Path target = directory.resolve(fileName);
		createdFiles.add(partial);
		try (FileChannel channel = FileChannel.open(partial, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)) {
			var buffer = java.nio.ByteBuffer.wrap(content);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true);
		}
		Files.move(partial, target, ATOMIC_MOVE);
		createdFiles.remove(partial);
		createdFiles.add(target);
		return target;
	}

	private static void cleanup(List<Path> files) {
		for (int index = files.size() - 1; index >= 0; index--) {
			try {
				Files.deleteIfExists(files.get(index));
			} catch (IOException ignored) {
				// Preserve the original export failure. The caller still gets an explicit failure.
			}
		}
	}

	private static SnapshotExportException failure(String message) {
		return new SnapshotExportException(message);
	}

	private static SnapshotExportException failure(String message, Throwable cause) {
		return new SnapshotExportException(message, cause);
	}

	public record ExportResult(
			CheckpointSnapshotManifest manifest,
			Bytes canonicalManifestBytes,
			Hash manifestSigningHash,
			Path manifestFile,
			List<Path> chunkFiles) {
		public ExportResult {
			Objects.requireNonNull(manifest, "manifest");
			canonicalManifestBytes = Bytes.wrap(
					Objects.requireNonNull(canonicalManifestBytes, "canonicalManifestBytes").toArray());
			Objects.requireNonNull(manifestSigningHash, "manifestSigningHash");
			Objects.requireNonNull(manifestFile, "manifestFile");
			chunkFiles = List.copyOf(Objects.requireNonNull(chunkFiles, "chunkFiles"));
		}
	}

	private record ExportCheckpoint(Hash hash, Hash stateRoot, BigInteger cumulativeDifficulty) {
	}

	private final class ChunkEmitter implements AutoCloseable {
		private final Path output;
		private final List<Path> createdFiles;
		private final String chunkUrlPrefix;
		private final List<SnapshotChunkDescriptor> descriptors = new ArrayList<>();
		private final List<Path> chunkFiles = new ArrayList<>();
		private DataOutputStream stream;
		private Digest256 digest;
		private Path partial;
		private int chunkIndex;
		private int nodeCount;
		private long contentBytes;
		private long encodedBytes;
		private long totalNodes;
		private long totalBytes;

		private ChunkEmitter(
				Path output, List<Path> createdFiles, long checkpointHeight, Hash checkpointHash) {
			this.output = output;
			this.createdFiles = createdFiles;
			String version = SnapshotFormatCompatibility.currentVersionName(
					checkpointHeight, checkpointHash);
			this.chunkUrlPrefix = VERSION_URL_PATH + version + "/chunks/";
		}

		private void write(Hash key, Bytes content) {
			long recordBytes = Math.addExact(36L, content.size());
			if (stream != null && (encodedBytes + recordBytes > MAX_CHUNK_BYTES
					|| nodeCount == MAX_NODES_PER_CHUNK)) {
				finishChunk();
			}
			if (stream == null) {
				startChunk();
			}
			if (encodedBytes + recordBytes > MAX_CHUNK_BYTES) {
				throw failure("Trie node cannot fit in a bounded snapshot chunk");
			}
			try {
				byte[] keyBytes = key.toArray();
				byte[] valueBytes = content.toArray();
				stream.write(keyBytes);
				stream.writeInt(valueBytes.length);
				stream.write(valueBytes);
				digest.update(keyBytes);
				updateInt(digest, valueBytes.length);
				digest.update(valueBytes);
				nodeCount++;
				contentBytes = Math.addExact(contentBytes, valueBytes.length);
				encodedBytes = Math.addExact(encodedBytes, recordBytes);
				totalNodes = Math.addExact(totalNodes, 1L);
				totalBytes = Math.addExact(totalBytes, valueBytes.length);
				if (totalNodes > MAX_TOTAL_NODES || totalBytes > MAX_TOTAL_BYTES) {
					throw failure("Snapshot exceeds configured total node or byte limits");
				}
			} catch (IOException e) {
				throw failure("Cannot write snapshot chunk", e);
			}
		}

		private void startChunk() {
			if (chunkIndex >= MAX_CHUNK_COUNT) {
				throw failure("Snapshot exceeds configured chunk count");
			}
			String name = "chunk-%05d.bin".formatted(chunkIndex);
			partial = output.resolve(name + ".part");
			createdFiles.add(partial);
			try {
				FileChannel channel = FileChannel.open(partial, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS);
				stream = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)));
				digest = new Digest256();
				stream.writeInt(chunkIndex);
				updateInt(digest, chunkIndex);
				encodedBytes = Integer.BYTES;
				nodeCount = 0;
				contentBytes = 0;
			} catch (IOException e) {
				throw failure("Cannot create snapshot chunk", e);
			}
		}

		private void finishChunk() {
			if (stream == null) {
				return;
			}
			try {
				stream.close();
				stream = null;
				Path target = output.resolve("chunk-%05d.bin".formatted(chunkIndex));
				Files.move(partial, target, ATOMIC_MOVE);
				createdFiles.remove(partial);
				createdFiles.add(target);
				chunkFiles.add(target);
				descriptors.add(new SnapshotChunkDescriptor(
						chunkIndex,
						"state-%05d".formatted(chunkIndex),
						publicOrigin.resolve(chunkUrlPrefix + chunkIndex).toString(),
						nodeCount,
						contentBytes,
						Hash.wrap(digest.digest())));
				chunkIndex++;
				partial = null;
			} catch (IOException e) {
				throw failure("Cannot finalize snapshot chunk", e);
			}
		}

		private void finish() {
			finishChunk();
		}

		private List<SnapshotChunkDescriptor> descriptors() {
			return List.copyOf(descriptors);
		}

		private List<Path> chunkFiles() {
			return List.copyOf(chunkFiles);
		}

		@Override
		public void close() {
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException ignored) {
					// The export path reports the original failure and removes the partial file.
				} finally {
					stream = null;
				}
			}
		}
	}

	private static final class DiskSeenSet implements AutoCloseable {
		private static final byte[] PRESENT = { 1 };
		private final Path directory;
		private final Options options;
		private final RocksDB database;

		private DiskSeenSet(Path directory, Options options, RocksDB database) {
			this.directory = directory;
			this.options = options;
			this.database = database;
		}

		private static DiskSeenSet create() {
			RocksDB.loadLibrary();
			Path directory = null;
			Options options = null;
			try {
				directory = Files.createTempDirectory("goldenera-snapshot-export-seen-").toRealPath();
				options = new Options().setCreateIfMissing(true);
				return new DiskSeenSet(directory, options, RocksDB.open(options, directory.toString()));
			} catch (IOException | RocksDBException e) {
				if (options != null) {
					options.close();
				}
				deleteDirectoryQuietly(directory);
				throw failure("Cannot create disk-backed snapshot deduplication store", e);
			}
		}

		private boolean add(Hash hash) {
			byte[] key = hash.toArray();
			try {
				if (database.get(key) != null) {
					return false;
				}
				database.put(key, PRESENT);
				return true;
			} catch (RocksDBException e) {
				throw failure("Cannot update disk-backed snapshot deduplication store", e);
			}
		}

		@Override
		public void close() {
			database.close();
			options.close();
			try {
				deleteDirectory(directory);
			} catch (IOException e) {
				throw failure("Cannot remove disk-backed snapshot deduplication store", e);
			}
		}
	}

	private static void updateInt(Digest256 digest, int value) {
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private static void deleteDirectoryQuietly(Path directory) {
		try {
			deleteDirectory(directory);
		} catch (IOException ignored) {
			// Best-effort cleanup during initialization failure.
		}
	}

	private static void deleteDirectory(Path directory) throws IOException {
		if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
