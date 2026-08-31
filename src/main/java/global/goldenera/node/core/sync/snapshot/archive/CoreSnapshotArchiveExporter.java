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
package global.goldenera.node.core.sync.snapshot.archive;

import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.FORMAT_VERSION;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_BLOCKS_PER_CHUNK;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_CHUNK_BYTES;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_CHUNK_COUNT;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_TOTAL_BLOCKS;
import static global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits.MAX_TOTAL_BYTES;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.utils.TxRootUtil;
import global.goldenera.node.core.blockchain.checkpoint.CheckpointRegistry;
import global.goldenera.node.core.blockchain.storage.ChainQuery;
import global.goldenera.node.core.storage.blockchain.domain.StoredBlock;
import global.goldenera.node.core.storage.chainidentity.StoredChainIdentity;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.SnapshotExportException;
import global.goldenera.node.core.sync.snapshot.SnapshotAnchorPolicy;
import global.goldenera.node.core.sync.snapshot.HardcodedCheckpointSnapshotAnchorPolicy;
import global.goldenera.node.core.sync.snapshot.transport.CoreSnapshotArchiveTransportManifest;

/**
 * Explicit offline FULL archive exporter. Blocks are fetched one canonical
 * height at a time, so neither chain history nor a complete chunk is retained
 * in heap. This service never imports or activates a database.
 */
public final class CoreSnapshotArchiveExporter {

	private static final String MANIFEST_FILE = "archive-manifest.json";

	private final SnapshotAnchorPolicy anchorPolicy;
	private final ChainQuery chainQuery;
	private final StoredChainIdentity chainIdentity;
	private final long maxChunkBytes;
	private final ObjectMapper objectMapper;
	private final CoreSnapshotEntityIndexSource entityIndexSource;
	private final CoreSnapshotEntityIndexExporter entityIndexExporter;

	public CoreSnapshotArchiveExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			StoredChainIdentity chainIdentity,
			ObjectMapper objectMapper) {
		this(checkpointRegistry, chainQuery, chainIdentity, MAX_CHUNK_BYTES, objectMapper,
				CoreSnapshotEntityIndexSource.empty(), new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	public CoreSnapshotArchiveExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			StoredChainIdentity chainIdentity,
			ObjectMapper objectMapper,
			CoreSnapshotEntityIndexSource entityIndexSource) {
		this(checkpointRegistry, chainQuery, chainIdentity, MAX_CHUNK_BYTES, objectMapper, entityIndexSource,
				new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	public CoreSnapshotArchiveExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			StoredChainIdentity chainIdentity,
			ObjectMapper objectMapper,
			CoreSnapshotEntityIndexSource entityIndexSource,
			SnapshotAnchorPolicy anchorPolicy) {
		this(checkpointRegistry, chainQuery, chainIdentity, MAX_CHUNK_BYTES, objectMapper, entityIndexSource,
				anchorPolicy);
	}

	public CoreSnapshotArchiveExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			StoredChainIdentity chainIdentity,
			long maxChunkBytes,
			ObjectMapper objectMapper) {
		this(checkpointRegistry, chainQuery, chainIdentity, maxChunkBytes, objectMapper,
				CoreSnapshotEntityIndexSource.empty(), new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	CoreSnapshotArchiveExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			StoredChainIdentity chainIdentity,
			long maxChunkBytes,
			ObjectMapper objectMapper,
			CoreSnapshotEntityIndexSource entityIndexSource) {
		this(checkpointRegistry, chainQuery, chainIdentity, maxChunkBytes, objectMapper, entityIndexSource,
				new HardcodedCheckpointSnapshotAnchorPolicy(checkpointRegistry));
	}

	CoreSnapshotArchiveExporter(
			CheckpointRegistry checkpointRegistry,
			ChainQuery chainQuery,
			StoredChainIdentity chainIdentity,
			long maxChunkBytes,
			ObjectMapper objectMapper,
			CoreSnapshotEntityIndexSource entityIndexSource,
			SnapshotAnchorPolicy anchorPolicy) {
		Objects.requireNonNull(checkpointRegistry, "checkpointRegistry");
		this.anchorPolicy = Objects.requireNonNull(anchorPolicy, "anchorPolicy");
		this.chainQuery = Objects.requireNonNull(chainQuery, "chainQuery");
		this.chainIdentity = Objects.requireNonNull(chainIdentity, "chainIdentity");
		if (maxChunkBytes <= CoreSnapshotBlockChunkCodec.HEADER_BYTES + Integer.BYTES
				|| maxChunkBytes > MAX_CHUNK_BYTES) {
			throw new IllegalArgumentException("Archive chunk byte limit is outside the supported range");
		}
		this.maxChunkBytes = maxChunkBytes;
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.entityIndexSource = Objects.requireNonNull(entityIndexSource, "entityIndexSource");
		this.entityIndexExporter = new CoreSnapshotEntityIndexExporter();
	}

	public ExportResult export(
			long checkpointHeight,
			CheckpointSnapshotManifest stateManifest,
			Path outputDirectory) {
		Objects.requireNonNull(stateManifest, "stateManifest");
		Path output = validateEmptyOutputDirectory(outputDirectory);
		List<Path> createdFiles = new ArrayList<>();
		try {
			StoredBlock checkpoint = validateCheckpoint(checkpointHeight, stateManifest);
			entityIndexSource.assertCheckpoint(checkpointHeight, checkpoint.getHash());
			List<CoreSnapshotBlockChunkDescriptor> descriptors;
			List<Path> chunkFiles;
			try (ChunkWriter writer = new ChunkWriter(output, createdFiles)) {
				streamCanonicalBlocks(checkpointHeight, stateManifest, writer);
				writer.finish();
				descriptors = writer.descriptors();
				chunkFiles = writer.chunkFiles();
			}
			CoreSnapshotEntityIndexExporter.ExportResult entityExport =
					entityIndexExporter.export(entityIndexSource, output);
			createdFiles.addAll(entityExport.chunkFiles());

			assertCanonical(checkpointHeight, checkpoint.getHash());
			CoreSnapshotArchiveManifest manifest = new CoreSnapshotArchiveManifest(
					FORMAT_VERSION,
					CheckpointSnapshotManifestCodec.signingHash(stateManifest),
					descriptors,
					entityExport.descriptors());
			Bytes canonical = CoreSnapshotArchiveManifestCodec.canonicalBytes(manifest);
			Hash signingHash = CoreSnapshotArchiveManifestCodec.signingHash(manifest);
			byte[] envelope = objectMapper.writeValueAsBytes(CoreSnapshotArchiveTransportManifest.from(manifest));
			Path manifestFile = writeAtomic(output, MANIFEST_FILE, envelope, createdFiles);
			return new ExportResult(
					manifest, canonical, signingHash, manifestFile, chunkFiles, entityExport.chunkFiles());
		} catch (SnapshotExportException e) {
			cleanup(createdFiles);
			throw e;
		} catch (Exception e) {
			cleanup(createdFiles);
			throw failure("Core snapshot archive export failed", e);
		}
	}

	private StoredBlock validateCheckpoint(long checkpointHeight, CheckpointSnapshotManifest stateManifest) {
		if (checkpointHeight < 0 || checkpointHeight == Long.MAX_VALUE
				|| checkpointHeight + 1 > MAX_TOTAL_BLOCKS) {
			throw failure("Checkpoint height exceeds FULL archive limits");
		}
		if (stateManifest.formatVersion() != CheckpointSnapshotLimits.FORMAT_VERSION
				|| stateManifest.checkpointHeight() != checkpointHeight
				|| stateManifest.networkCode() != chainIdentity.carrierNetworkCode()
				|| !stateManifest.chainIdentity().equals(chainIdentity)) {
			throw failure("State manifest identity/checkpoint does not match this archive export");
		}
		anchorPolicy.verify(checkpointHeight, stateManifest.checkpointHash(), chainIdentity);
		StoredBlock checkpoint = chainQuery.getStoredBlockByHeight(checkpointHeight)
				.orElseThrow(() -> failure("Canonical checkpoint StoredBlock is missing"));
		validateFullStoredBlock(checkpoint, checkpointHeight, null, null);
		assertCanonical(checkpointHeight, checkpoint.getHash());
		BlockHeader header = checkpoint.getBlock().getHeader();
		if (!checkpoint.getHash().equals(stateManifest.checkpointHash())
				|| !header.getStateRootHash().equals(stateManifest.checkpointStateRoot())
				|| !checkpoint.getCumulativeDifficulty().equals(stateManifest.checkpointCumulativeDifficulty())) {
			throw failure("Canonical checkpoint StoredBlock does not match the state manifest");
		}
		return checkpoint;
	}

	private void streamCanonicalBlocks(
			long checkpointHeight,
			CheckpointSnapshotManifest stateManifest,
			ChunkWriter writer) {
		Hash previousHash = null;
		BigInteger cumulativeDifficulty = BigInteger.ZERO;
		for (long height = 0; height <= checkpointHeight; height++) {
			long currentHeight = height;
			StoredBlock stored = chainQuery.getStoredBlockByHeight(currentHeight)
					.orElseThrow(() -> failure("Canonical StoredBlock is missing at height " + currentHeight));
			validateFullStoredBlock(stored, height, previousHash, cumulativeDifficulty);
			assertCanonical(height, stored.getHash());
			if (height == 0 && !chainIdentity.genesisHash().equals(stored.getHash().toHexString())) {
				throw failure("Canonical genesis does not match the configured chain identity");
			}
			if (height == checkpointHeight
					&& (!stored.getHash().equals(stateManifest.checkpointHash())
							|| !stored.getBlock().getHeader().getStateRootHash()
									.equals(stateManifest.checkpointStateRoot()))) {
				throw failure("Streamed checkpoint block does not match the state manifest");
			}
			writer.write(height, CoreSnapshotBlockChunkCodec.encodeStoredBlock(stored));
			previousHash = stored.getHash();
			cumulativeDifficulty = stored.getCumulativeDifficulty();
		}
	}

	private void validateFullStoredBlock(
			StoredBlock stored,
			long expectedHeight,
			Hash previousHash,
			BigInteger parentCumulativeDifficulty) {
		if (stored == null || stored.isPartial() || stored.getBlock() == null
				|| stored.getBlock().getHeader() == null || stored.getBlock().getTxs() == null
				|| stored.getHash() == null || stored.getCumulativeDifficulty() == null) {
			throw failure("Incomplete StoredBlock at height " + expectedHeight);
		}
		Block block = stored.getBlock();
		BlockHeader header = block.getHeader();
		List<Tx> transactions = block.getTxs();
		if (block.getHeight() != expectedHeight || header.getHeight() != expectedHeight
				|| !block.getHash().equals(header.getHash()) || !block.getHash().equals(stored.getHash())) {
			throw failure("StoredBlock hash/height mismatch at height " + expectedHeight);
		}
		if (expectedHeight == 0) {
			if (!Hash.ZERO.equals(header.getPreviousHash())) {
				throw failure("Genesis previous hash is not zero");
			}
		} else if (previousHash != null && !previousHash.equals(header.getPreviousHash())) {
			throw failure("Canonical previous-hash link mismatch at height " + expectedHeight);
		}
		if (header.getDifficulty() == null || header.getDifficulty().signum() <= 0) {
			throw failure("StoredBlock has non-positive difficulty at height " + expectedHeight);
		}
		if (parentCumulativeDifficulty != null
				&& !parentCumulativeDifficulty.add(header.getDifficulty())
						.equals(stored.getCumulativeDifficulty())) {
			throw failure("StoredBlock cumulative difficulty mismatch at height " + expectedHeight);
		}
		if (!TxRootUtil.txRootHash(transactions).equals(header.getTxRootHash())) {
			throw failure("StoredBlock transaction root mismatch at height " + expectedHeight);
		}
		if (stored.getBlockSize() != block.getSize()
				|| stored.getTxCount() != transactions.size()
				|| !Objects.equals(stored.getIdentity(), header.getIdentity())
				|| stored.getTransactionHashes() == null
				|| stored.getTransactionSizes() == null
				|| stored.getTransactionSenders() == null
				|| stored.getTransactionHashes().length != transactions.size()
				|| stored.getTransactionSizes().length != transactions.size()
				|| stored.getTransactionSenders().length != transactions.size()) {
			throw failure("StoredBlock metadata mismatch at height " + expectedHeight);
		}
		for (int index = 0; index < transactions.size(); index++) {
			Tx transaction = transactions.get(index);
			if (!Objects.equals(stored.getTransactionHashByIndex(index), transaction.getHash())
					|| stored.getTransactionSizeByIndex(index) != transaction.getSize()
					|| !Objects.equals(stored.getTransactionSenderByIndex(index), transaction.getSender())) {
				throw failure("StoredBlock transaction index mismatch at height " + expectedHeight);
			}
		}
	}

	private void assertCanonical(long height, Hash expectedHash) {
		Hash canonical = chainQuery.getBlockHashByHeight(height)
				.orElseThrow(() -> failure("Canonical height index is missing height " + height));
		if (!canonical.equals(expectedHash)) {
			throw failure("Chain reorganized while exporting FULL archive at height " + height);
		}
	}

	private static Path validateEmptyOutputDirectory(Path directory) {
		Objects.requireNonNull(directory, "outputDirectory");
		if (!directory.isAbsolute() || !directory.equals(directory.normalize())) {
			throw failure("Archive output must be a normalized absolute directory");
		}
		try {
			Path current = directory.getRoot();
			for (Path component : directory) {
				current = current.resolve(component);
				if (Files.isSymbolicLink(current)) {
					throw failure("Archive output path must not contain symbolic links");
				}
			}
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
					|| !directory.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(directory)) {
				throw failure("Archive output must be an existing real directory");
			}
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
				if (entries.iterator().hasNext()) {
					throw failure("Archive output directory must be empty");
				}
			}
			return directory;
		} catch (IOException e) {
			throw failure("Cannot validate archive output directory", e);
		}
	}

	private static Path writeAtomic(Path directory, String fileName, byte[] content, List<Path> createdFiles)
			throws IOException {
		Path partial = directory.resolve(fileName + ".part");
		Path target = directory.resolve(fileName);
		createdFiles.add(partial);
		try (FileChannel channel = FileChannel.open(partial, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)) {
			ByteBuffer buffer = ByteBuffer.wrap(content);
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
				// Preserve the export failure; all owned targets remain explicitly tracked.
			}
		}
	}

	private static Hash hashFile(Path path) throws IOException {
		Digest256 digest = new Digest256();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = new BufferedInputStream(Files.newInputStream(path, READ))) {
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return Hash.wrap(digest.digest());
	}

	private static SnapshotExportException failure(String message) {
		return new SnapshotExportException(message);
	}

	private static SnapshotExportException failure(String message, Throwable cause) {
		return new SnapshotExportException(message, cause);
	}

	public record ExportResult(
			CoreSnapshotArchiveManifest manifest,
			Bytes canonicalManifestBytes,
			Hash manifestSigningHash,
			Path manifestFile,
			List<Path> chunkFiles,
			List<Path> entityChunkFiles) {
		public ExportResult {
			Objects.requireNonNull(manifest, "manifest");
			canonicalManifestBytes = Bytes.wrap(
					Objects.requireNonNull(canonicalManifestBytes, "canonicalManifestBytes").toArray());
			Objects.requireNonNull(manifestSigningHash, "manifestSigningHash");
			Objects.requireNonNull(manifestFile, "manifestFile");
			chunkFiles = List.copyOf(Objects.requireNonNull(chunkFiles, "chunkFiles"));
			entityChunkFiles = List.copyOf(Objects.requireNonNull(entityChunkFiles, "entityChunkFiles"));
		}
	}

	private final class ChunkWriter implements AutoCloseable {
		private final Path output;
		private final List<Path> createdFiles;
		private final List<CoreSnapshotBlockChunkDescriptor> descriptors = new ArrayList<>();
		private final List<Path> chunkFiles = new ArrayList<>();
		private FileChannel channel;
		private DataOutputStream stream;
		private Path partial;
		private int chunkIndex;
		private int blockCount;
		private long firstHeight;
		private long lastHeight;
		private long byteCount;
		private long totalCompressedBytes;
		private long totalUncompressedBytes;

		private ChunkWriter(Path output, List<Path> createdFiles) {
			this.output = output;
			this.createdFiles = createdFiles;
		}

		private void write(long height, Bytes encoded) {
			long recordBytes = Math.addExact(Integer.BYTES, encoded.size());
			if (stream != null && (byteCount + recordBytes > maxChunkBytes
					|| blockCount == MAX_BLOCKS_PER_CHUNK)) {
				finishChunk();
			}
			if (stream == null) {
				startChunk(height);
			}
			if (byteCount + recordBytes > maxChunkBytes) {
				throw failure("Encoded StoredBlock cannot fit in configured archive chunk limit");
			}
			try {
				byte[] bytes = encoded.toArrayUnsafe();
				stream.writeInt(bytes.length);
				stream.write(bytes);
				blockCount++;
				lastHeight = height;
				byteCount = Math.addExact(byteCount, recordBytes);
			} catch (IOException e) {
				throw failure("Cannot write archive block chunk", e);
			}
		}

		private void startChunk(long height) {
			if (chunkIndex >= MAX_CHUNK_COUNT) {
				throw failure("FULL archive exceeds configured chunk count");
			}
			partial = output.resolve("archive-chunk-%05d.bin.raw.part".formatted(chunkIndex));
			createdFiles.add(partial);
			try {
				channel = FileChannel.open(partial, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS);
				stream = new DataOutputStream(Channels.newOutputStream(channel));
				CoreSnapshotBlockChunkCodec.writeHeader(stream, chunkIndex, 0);
				firstHeight = height;
				lastHeight = height;
				blockCount = 0;
				byteCount = CoreSnapshotBlockChunkCodec.HEADER_BYTES;
			} catch (IOException e) {
				throw failure("Cannot create archive block chunk", e);
			}
		}

		private void finishChunk() {
			if (stream == null) {
				return;
			}
			try {
				stream.flush();
				long endPosition = channel.position();
				channel.position(CoreSnapshotBlockChunkCodec.BLOCK_COUNT_OFFSET);
				ByteBuffer count = ByteBuffer.allocate(Integer.BYTES).putInt(blockCount).flip();
				while (count.hasRemaining()) {
					channel.write(count);
				}
				channel.position(endPosition);
				channel.force(true);
				stream.close();
				stream = null;
				channel = null;
				long uncompressedBytes = Files.size(partial);
				if (uncompressedBytes != byteCount || uncompressedBytes > maxChunkBytes) {
					throw failure("Final archive block chunk size is inconsistent");
				}
				Hash uncompressedHash = hashFile(partial);
				Path compressedPartial = output.resolve(
						"archive-chunk-%05d.bin.zst.part".formatted(chunkIndex));
				createdFiles.add(compressedPartial);
				try (InputStream input = new BufferedInputStream(Files.newInputStream(partial, READ));
						OutputStream compressed = Files.newOutputStream(
								compressedPartial, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)) {
					CoreSnapshotCompression.writeZstd(input, compressed);
				}
				try (FileChannel compressedChannel = FileChannel.open(
						compressedPartial, WRITE, LinkOption.NOFOLLOW_LINKS)) {
					compressedChannel.force(true);
				}
				long compressedBytes = Files.size(compressedPartial);
				if (compressedBytes <= 0 || compressedBytes > MAX_CHUNK_BYTES) {
					throw failure("Compressed archive block chunk exceeds byte limits");
				}
				Hash compressedHash = hashFile(compressedPartial);
				Path target = output.resolve("archive-chunk-%05d.bin.zst".formatted(chunkIndex));
				Files.move(compressedPartial, target, ATOMIC_MOVE);
				createdFiles.remove(compressedPartial);
				createdFiles.add(target);
				Files.delete(partial);
				createdFiles.remove(partial);
				totalCompressedBytes = Math.addExact(totalCompressedBytes, compressedBytes);
				totalUncompressedBytes = Math.addExact(totalUncompressedBytes, uncompressedBytes);
				if (totalCompressedBytes > MAX_TOTAL_BYTES || totalUncompressedBytes > MAX_TOTAL_BYTES) {
					throw failure("FULL archive exceeds total byte limit");
				}
				chunkFiles.add(target);
				descriptors.add(new CoreSnapshotBlockChunkDescriptor(
						chunkIndex, firstHeight, lastHeight, blockCount,
						CoreSnapshotChunkCompression.ZSTD,
						compressedBytes, compressedHash, uncompressedBytes, uncompressedHash));
				chunkIndex++;
				partial = null;
			} catch (IOException e) {
				throw failure("Cannot finalize archive block chunk", e);
			}
		}

		private void finish() {
			finishChunk();
			if (descriptors.isEmpty()) {
				throw failure("FULL archive did not contain genesis");
			}
		}

		private List<CoreSnapshotBlockChunkDescriptor> descriptors() {
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
					// The outer failure path removes the partial file.
				} finally {
					stream = null;
					channel = null;
				}
			}
		}
	}
}
