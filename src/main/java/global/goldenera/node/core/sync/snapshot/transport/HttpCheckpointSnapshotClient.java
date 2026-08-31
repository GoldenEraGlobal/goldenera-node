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
package global.goldenera.node.core.sync.snapshot.transport;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.storage.chainidentity.KnownProductionChainIdentityRegistry;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifest;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotVerifier;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveLimits;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifest;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotArchiveManifestCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotBlockChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotChunkCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotCompression;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkCodec;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityLimits;
import global.goldenera.node.core.sync.snapshot.archive.CoreSnapshotEntityType;
import global.goldenera.node.shared.properties.GeneralProperties;
import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class HttpCheckpointSnapshotClient implements CheckpointSnapshotTransport {

	public static final String MANIFEST_PATH = "/api/core/v1/sync/snapshots/checkpoint/manifest";
	public static final String CHUNKS_PATH = "/api/core/v1/sync/snapshots/checkpoint/chunks/";
	public static final String ARCHIVE_MANIFEST_PATH =
			"/api/core/v1/sync/snapshots/checkpoint/archive/manifest";
	public static final String ARCHIVE_CHUNKS_PATH =
			"/api/core/v1/sync/snapshots/checkpoint/archive/chunks/";
	public static final String ARCHIVE_ENTITY_CHUNKS_PATH =
			"/api/core/v1/sync/snapshots/checkpoint/archive/entities/";
	public static final String VERSIONS_PATH = "/api/core/v1/sync/snapshots/checkpoint/versions/";

	private final ObjectMapper objectMapper;
	private final SnapshotDistributionProperties properties;
	private final GeneralProperties generalProperties;
	private final OkHttpClient httpClient;
	private final ManifestPreflight manifestPreflight;
	private static final long WORKER_SHUTDOWN_SECONDS = 30;
	private static final int RATE_LIMIT_MAX_ATTEMPTS = 8;
	private static final long RATE_LIMIT_MAX_DELAY_SECONDS = 60;
	private static final long MIN_FREE_SPACE_RESERVE = 1024L * 1024;
	private static final long MAX_FREE_SPACE_RESERVE = 64L * 1024 * 1024;

	@Autowired
	public HttpCheckpointSnapshotClient(
			ObjectMapper objectMapper,
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties,
			ObjectProvider<CheckpointSnapshotVerifier> verifierProvider) {
		this(objectMapper, properties, generalProperties, client(properties), manifest -> {
			if (!KnownProductionChainIdentityRegistry.isKnownProductionIdentity(manifest.chainIdentity())) {
				throw new SnapshotTransportException(
						"Snapshot manifest is not an exact known production chain identity");
			}
			CheckpointSnapshotVerifier verifier = verifierProvider.getIfAvailable();
			if (verifier == null) {
				throw new SnapshotTransportException("Snapshot manifest verifier is unavailable");
			}
			verifier.verifyManifestMetadataForTransport(manifest);
		});
	}

	HttpCheckpointSnapshotClient(
			ObjectMapper objectMapper,
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties) {
		this(objectMapper, properties, generalProperties, client(properties), manifest -> { });
	}

	HttpCheckpointSnapshotClient(
			ObjectMapper objectMapper,
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties,
			ManifestPreflight manifestPreflight) {
		this(objectMapper, properties, generalProperties, client(properties), manifestPreflight);
	}

	HttpCheckpointSnapshotClient(
			ObjectMapper objectMapper,
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties,
			OkHttpClient httpClient) {
		this(objectMapper, properties, generalProperties, httpClient, manifest -> { });
	}

	HttpCheckpointSnapshotClient(
			ObjectMapper objectMapper,
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties,
			OkHttpClient httpClient,
			ManifestPreflight manifestPreflight) {
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.generalProperties = generalProperties;
		this.httpClient = httpClient;
		this.manifestPreflight = manifestPreflight;
	}

	public StagedSnapshotDownload stageFromFirstTrustedSource() {
		properties.validate();
		if (!properties.isBootstrapEnabled()) {
			throw failure("Snapshot bootstrap is disabled");
		}
		Network network = generalProperties.getNetwork();
		List<URI> sources = properties.trustedSources(network);
		if (sources.isEmpty()) {
			throw failure("No trusted snapshot source configured for " + network);
		}
		SnapshotTransportException lastFailure = null;
		for (URI source : sources) {
			Path directory = null;
			try {
				directory = properties.getStagingDirectory() == null
						? Files.createTempDirectory("goldenera-checkpoint-snapshot-")
						: Files.createTempDirectory(prepareDirectory(properties.getStagingDirectory()), "checkpoint-");
				return stage(source, directory);
			} catch (IOException | SnapshotTransportException e) {
				lastFailure = e instanceof SnapshotTransportException transportException
						? transportException
						: failure("Could not stage snapshot from " + source, e);
				deleteOwnedStaging(directory);
			}
		}
		throw aggregateFailure("All trusted snapshot sources failed for " + network, lastFailure);
	}

	/**
	 * Stages both checkpoint state and the complete canonical block archive from
	 * one trusted origin. A state-only response is never returned from this API.
	 */
	public StagedCoreSnapshotArchiveDownload stageFullArchiveFromFirstTrustedSource() {
		properties.validate();
		if (!properties.isBootstrapEnabled()) {
			throw failure("Snapshot bootstrap is disabled");
		}
		Network network = generalProperties.getNetwork();
		List<URI> sources = properties.trustedSources(network);
		if (sources.isEmpty()) {
			throw failure("No trusted snapshot source configured for " + network);
		}
		SnapshotTransportException lastFailure = null;
		List<FullArchiveCandidate> candidates = new ArrayList<>();
		for (URI source : sources) {
			try {
				candidates.add(inspectFullArchiveCandidate(source));
			} catch (SnapshotTransportException e) {
				lastFailure = e;
			}
		}
		if (candidates.isEmpty()) {
			throw aggregateFailure("All trusted full core snapshot manifests failed for " + network, lastFailure);
		}

		List<TrustedSnapshotCandidate> selectedAnchors = selectHighestTrustedGroup(candidates.stream()
				.map(candidate -> new TrustedSnapshotCandidate(
						candidate.source(), candidate.stateManifest().checkpointHeight(),
						candidate.stateManifest().checkpointHash()))
				.toList());
		long highestCheckpoint = selectedAnchors.getFirst().height();
		Hash highestCheckpointHash = selectedAnchors.getFirst().hash();
		List<FullArchiveCandidate> selected = candidates.stream()
				.filter(candidate -> candidate.stateManifest().checkpointHeight() == highestCheckpoint
						&& candidate.stateManifest().checkpointHash().equals(highestCheckpointHash))
				.toList();

		for (FullArchiveCandidate candidate : selected) {
			Path directory = null;
			boolean persistentCache = properties.getStagingDirectory() != null;
			try {
				directory = persistentCache
						? prepareResumeCacheDirectory(network, candidate)
						: Files.createTempDirectory("goldenera-full-core-snapshot-");
				StagedCoreSnapshotArchiveDownload staged = stageFullArchive(candidate, directory);
				verifyStagedFormatHeaders(staged);
				return staged;
			} catch (IOException | SnapshotTransportException e) {
				lastFailure = e instanceof SnapshotTransportException transportException
						? transportException
						: failure("Could not stage full core snapshot from " + candidate.source(), e);
				if (!persistentCache) {
					deleteOwnedStaging(directory);
				}
			}
		}
		throw aggregateFailure("All trusted sources for checkpoint " + highestCheckpoint + " failed", lastFailure);
	}

	private void verifyStagedFormatHeaders(StagedCoreSnapshotArchiveDownload staged) {
		try {
			int archiveFormat = staged.archiveManifest().formatVersion();
			for (CoreSnapshotBlockChunkDescriptor descriptor : staged.archiveManifest().blockChunks()) {
				try (InputStream input = staged.blockChunkSource().open(descriptor);
						CoreSnapshotBlockChunkCodec.Reader ignored = CoreSnapshotBlockChunkCodec.openCompressed(
								input, descriptor, archiveFormat)) {
					// The constructor validates the signed descriptor binding and format header.
				}
			}
			for (CoreSnapshotEntityChunkDescriptor descriptor : staged.archiveManifest().entityChunks()) {
				try (InputStream input = staged.entityChunkSource().open(descriptor);
						InputStream verified = CoreSnapshotCompression.openVerifiedZstd(
								input, descriptor.compressedByteCount(), descriptor.compressedContentHash(),
								descriptor.uncompressedByteCount(), descriptor.uncompressedContentHash());
						CoreSnapshotEntityChunkCodec.Reader ignored = CoreSnapshotEntityChunkCodec.open(
								verified, descriptor, archiveFormat)) {
					// The constructor validates the signed descriptor binding and format header.
				}
			}
		} catch (Exception e) {
			try {
				staged.close();
			} catch (IOException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw failure("Staged core snapshot chunk format is incompatible", e);
		}
	}

	static List<TrustedSnapshotCandidate> selectHighestTrustedGroup(List<TrustedSnapshotCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			throw new IllegalArgumentException("Trusted snapshot candidates cannot be empty");
		}
		long highest = candidates.stream().mapToLong(TrustedSnapshotCandidate::height).max().orElseThrow();
		Hash selectedHash = candidates.stream()
				.filter(candidate -> candidate.height() == highest)
				.map(TrustedSnapshotCandidate::hash)
				.findFirst()
				.orElseThrow();
		return candidates.stream()
				.filter(candidate -> candidate.height() == highest && candidate.hash().equals(selectedHash))
				.toList();
	}

	StagedCoreSnapshotArchiveDownload stageFullArchive(URI trustedSource, Path stagingDirectory) {
		properties.validate();
		if (!properties.isBootstrapEnabled()) {
			throw failure("Snapshot bootstrap is disabled");
		}
		return stageFullArchive(inspectFullArchiveCandidate(trustedSource), stagingDirectory);
	}

	private FullArchiveCandidate inspectFullArchiveCandidate(URI trustedSource) {
		URI source = validateAllowedSource(trustedSource);
		try {
			byte[] stateManifestBytes = downloadManifest(source, MANIFEST_PATH, "Snapshot manifest");
			SnapshotTransportManifest stateEnvelope = objectMapper.readValue(
					stateManifestBytes, SnapshotTransportManifest.class);
			CheckpointSnapshotManifest stateManifest = stateEnvelope.decodeAndVerify();
			manifestPreflight.verify(stateManifest);
			List<ResolvedChunk> stateChunks = validateManifest(source, stateManifest);

			String versionBase = VERSIONS_PATH + snapshotVersion(stateManifest) + "/";
			byte[] manifestBytes;
			boolean versioned;
			try {
				manifestBytes = downloadManifest(
						source, versionBase + "archive/manifest", "Versioned archive manifest");
				versioned = true;
			} catch (SnapshotTransportException unavailableVersionedEndpoint) {
				// Backward-compatible fallback for trusted nodes which have not enabled
				// immutable version endpoints yet.
				manifestBytes = downloadManifest(source, ARCHIVE_MANIFEST_PATH, "Archive manifest");
				versioned = false;
			}
			CoreSnapshotArchiveTransportManifest envelope = objectMapper.readValue(
					manifestBytes, CoreSnapshotArchiveTransportManifest.class);
			CoreSnapshotArchiveManifest archiveManifest = envelope.decodeAndVerify();
			List<ResolvedChunk> chunks = validateArchiveManifest(
					source, stateManifest, archiveManifest,
					versioned ? versionBase + "archive/chunks/" : ARCHIVE_CHUNKS_PATH);
			List<ResolvedChunk> entityChunks = validateEntityManifest(
					source, archiveManifest,
					versioned ? versionBase + "archive/entities/" : ARCHIVE_ENTITY_CHUNKS_PATH);
			return new FullArchiveCandidate(
					source, stateManifestBytes, stateEnvelope, stateManifest, stateChunks,
					manifestBytes, envelope, archiveManifest, chunks, entityChunks);
		} catch (SnapshotTransportException e) {
			throw e;
		} catch (Exception e) {
			throw failure("Full core snapshot manifest inspection failed for " + source + ": "
					+ e.getMessage(), e);
		}
	}

	private String snapshotVersion(CheckpointSnapshotManifest manifest) {
		return SnapshotFormatCompatibility.currentVersionName(
				manifest.checkpointHeight(), manifest.checkpointHash());
	}

	private StagedCoreSnapshotArchiveDownload stageFullArchive(
			FullArchiveCandidate candidate, Path stagingDirectory) {
		try {
			Path staging = prepareDirectory(stagingDirectory);
			Path stateManifestFile = staging.resolve("manifest.json");
			Path manifestFile = staging.resolve("archive-manifest.json");
			long archiveDownloadBytes = 0;
			for (ResolvedChunk chunk : candidate.blockChunks()) {
				archiveDownloadBytes = Math.addExact(archiveDownloadBytes, chunk.encodedByteCount());
			}
			for (ResolvedChunk chunk : candidate.entityChunks()) {
				archiveDownloadBytes = Math.addExact(archiveDownloadBytes, chunk.encodedByteCount());
			}
			if (archiveDownloadBytes > properties.getMaxArchiveTotalBytes()) {
				throw failure("Archive block and entity chunks exceed the combined download limit");
			}
			List<ResolvedChunk> allChunks = new ArrayList<>(
					candidate.stateChunks().size() + candidate.blockChunks().size() + candidate.entityChunks().size());
			allChunks.addAll(candidate.stateChunks());
			allChunks.addAll(candidate.blockChunks());
			allChunks.addAll(candidate.entityChunks());
			ensureUsableSpace(staging, allChunks);
			writeManifest(staging, stateManifestFile, "manifest.json.part", candidate.stateManifestBytes());
			writeManifest(staging, manifestFile, "archive-manifest.json.part", candidate.archiveManifestBytes());
			List<Path> stateChunkFiles = downloadChunks(staging, candidate.stateChunks(), "chunk-");
			List<Path> chunkFiles = downloadChunks(staging, candidate.blockChunks(), "archive-chunk-");
			List<Path> entityChunkFiles = downloadChunks(staging, candidate.entityChunks(), "archive-entity-");
			StagedSnapshotDownload state = new StagedSnapshotDownload(
					candidate.stateEnvelope(), candidate.stateManifest(), staging, stateManifestFile, stateChunkFiles);
			return new StagedCoreSnapshotArchiveDownload(
					state, candidate.archiveEnvelope(), candidate.archiveManifest(), manifestFile,
					chunkFiles, entityChunkFiles);
		} catch (SnapshotTransportException e) {
			throw e;
		} catch (Exception e) {
			throw failure("Full core snapshot staging failed: " + e.getMessage(), e);
		}
	}

	private Path prepareResumeCacheDirectory(Network network, FullArchiveCandidate candidate) throws IOException {
		Path base = prepareDirectory(properties.getStagingDirectory());
		String cacheName = resumeCacheName(network, candidate);
		pruneResumeCaches(base, cacheName);
		Path cache = safeTarget(base, cacheName);
		Files.createDirectories(cache);
		return prepareDirectory(cache);
	}

	private String resumeCacheName(Network network, FullArchiveCandidate candidate) {
		return "full-core-cache-" + network.name().toLowerCase(Locale.ROOT)
				+ "-" + candidate.stateManifest().checkpointHeight()
				+ "-" + hex(candidate.stateManifest().checkpointHash())
				+ "-" + hex(CheckpointSnapshotManifestCodec.signingHash(candidate.stateManifest()))
				+ "-" + hex(CoreSnapshotArchiveManifestCodec.signingHash(candidate.archiveManifest()));
	}

	private String hex(Hash hash) {
		String value = hash.toHexString();
		return value.startsWith("0x") ? value.substring(2) : value;
	}

	private void pruneResumeCaches(Path base, String preservedName) throws IOException {
		List<Path> caches;
		try (var entries = Files.list(base)) {
			caches = entries
					.filter(path -> path.getFileName().toString().startsWith("full-core-cache-"))
					.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
					.sorted(Comparator.comparingLong(this::lastModified).reversed())
					.toList();
		}
		int retained = 1;
		for (Path cache : caches) {
			if (cache.getFileName().toString().equals(preservedName)
					|| retained++ < properties.getResumeCacheMaxEntries()) {
				continue;
			}
			deleteOwnedStaging(cache);
		}
	}

	private long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
		} catch (IOException e) {
			return Long.MIN_VALUE;
		}
	}

	@Override
	public StagedSnapshotDownload stage(URI trustedSource, Path stagingDirectory) {
		properties.validate();
		if (!properties.isBootstrapEnabled()) {
			throw failure("Snapshot bootstrap is disabled");
		}
		URI source = validateAllowedSource(trustedSource);
		try {
			Path staging = prepareDirectory(stagingDirectory);
			Path manifestFile = staging.resolve("manifest.json");
			byte[] manifestBytes = downloadManifest(source, MANIFEST_PATH, "Snapshot manifest");
			SnapshotTransportManifest manifest = objectMapper.readValue(manifestBytes, SnapshotTransportManifest.class);
			CheckpointSnapshotManifest domainManifest = manifest.decodeAndVerify();
			manifestPreflight.verify(domainManifest);
			List<ResolvedChunk> chunks = validateManifest(source, domainManifest);
			ensureUsableSpace(staging, chunks);
			writeManifest(staging, manifestFile, "manifest.json.part", manifestBytes);
			List<Path> chunkFiles = downloadChunks(staging, chunks, "chunk-");
			return new StagedSnapshotDownload(manifest, domainManifest, staging, manifestFile, chunkFiles);
		} catch (SnapshotTransportException e) {
			throw e;
		} catch (Exception e) {
			throw failure("Snapshot staging failed: " + e.getMessage(), e);
		}
	}

	private byte[] downloadManifest(URI source, String path, String label) throws IOException {
		URI manifestUri = source.resolve(path);
		try (TrackedResponse tracked = execute(
				new Request.Builder().url(manifestUri.toString()).get().build(), null);
				ResponseBody body = tracked.response().body()) {
			if (body == null) {
				throw failure(label + " response is empty");
			}
			if (body.contentLength() > properties.getMaxManifestBytes()) {
				throw failure(label + " exceeds configured byte limit");
			}
			try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				copyBounded(input, output, properties.getMaxManifestBytes());
				return output.toByteArray();
			}
		}
	}

	private List<ResolvedChunk> validateArchiveManifest(
			URI source,
			CheckpointSnapshotManifest stateManifest,
			CoreSnapshotArchiveManifest manifest,
			String chunksPath) {
		if (manifest == null || !SnapshotFormatCompatibility.supportsArchive(manifest.formatVersion())
				|| !manifest.stateManifestSigningHash().equals(
						CheckpointSnapshotManifestCodec.signingHash(stateManifest))) {
			throw failure("Archive manifest is not bound to the staged state manifest");
		}
		List<CoreSnapshotBlockChunkDescriptor> descriptors = manifest.blockChunks();
		if (descriptors.isEmpty() || descriptors.size() > Math.min(
				properties.getMaxArchiveChunkCount(), CoreSnapshotArchiveLimits.MAX_CHUNK_COUNT)) {
			throw failure("Archive manifest exceeds configured chunk count");
		}
		long nextHeight = 0;
		long totalCompressedBytes = 0;
		long totalUncompressedBytes = 0;
		long totalBlocks = 0;
		List<ResolvedChunk> result = new ArrayList<>(descriptors.size());
		for (int index = 0; index < descriptors.size(); index++) {
			CoreSnapshotBlockChunkDescriptor chunk = descriptors.get(index);
			long expectedLast;
			try {
				expectedLast = Math.addExact(chunk.firstHeight(), chunk.blockCount() - 1L);
				totalBlocks = Math.addExact(totalBlocks, chunk.blockCount());
				totalCompressedBytes = Math.addExact(
						totalCompressedBytes, chunk.compressedByteCount());
				totalUncompressedBytes = Math.addExact(
						totalUncompressedBytes, chunk.uncompressedByteCount());
			} catch (ArithmeticException e) {
				throw failure("Archive chunk descriptor overflow", e);
			}
			if (chunk.index() != index || chunk.blockCount() <= 0
					|| chunk.blockCount() > CoreSnapshotArchiveLimits.MAX_BLOCKS_PER_CHUNK
					|| chunk.firstHeight() != nextHeight || chunk.lastHeight() != expectedLast
					|| chunk.compression() != CoreSnapshotChunkCompression.ZSTD
					|| chunk.compressedByteCount() <= 0
					|| chunk.compressedByteCount() > Math.min(
							properties.getMaxArchiveChunkBytes(), CoreSnapshotArchiveLimits.MAX_CHUNK_BYTES)
					|| chunk.uncompressedByteCount() <= 0
					|| chunk.uncompressedByteCount() > CoreSnapshotArchiveLimits.MAX_CHUNK_BYTES) {
				throw failure("Archive chunk descriptor is invalid at index " + index);
			}
			nextHeight = Math.addExact(expectedLast, 1L);
			if (totalBlocks > CoreSnapshotArchiveLimits.MAX_TOTAL_BLOCKS
					|| totalCompressedBytes > Math.min(
							properties.getMaxArchiveTotalBytes(), CoreSnapshotArchiveLimits.MAX_TOTAL_BYTES)
					|| totalUncompressedBytes > CoreSnapshotArchiveLimits.MAX_TOTAL_BYTES) {
				throw failure("Archive manifest exceeds configured total limits");
			}
			URI uri = source.resolve(chunksPath + index);
			if (!sameOrigin(source, uri)) {
				throw failure("Archive chunk URL must use the trusted manifest origin");
			}
			result.add(new ResolvedChunk(
					index, uri, chunk.compressedByteCount(), chunk.compressedContentHash()));
		}
		long expectedBlocks;
		try {
			expectedBlocks = Math.addExact(stateManifest.checkpointHeight(), 1L);
		} catch (ArithmeticException e) {
			throw failure("Archive checkpoint height overflow", e);
		}
		if (totalBlocks != expectedBlocks || nextHeight != expectedBlocks) {
			throw failure("Archive does not cover genesis through the state checkpoint");
		}
		return List.copyOf(result);
	}

	private List<ResolvedChunk> validateEntityManifest(
			URI source, CoreSnapshotArchiveManifest manifest, String chunksPath) {
		List<CoreSnapshotEntityChunkDescriptor> descriptors = manifest.entityChunks();
		if (descriptors.size() > CoreSnapshotEntityLimits.MAX_CHUNK_COUNT) {
			throw failure("Entity sidecar exceeds its chunk count limit");
		}
		CoreSnapshotEntityType previousType = null;
		long totalEntries = 0;
		long totalCompressedBytes = 0;
		long totalUncompressedBytes = 0;
		List<ResolvedChunk> result = new ArrayList<>(descriptors.size());
		for (int index = 0; index < descriptors.size(); index++) {
			CoreSnapshotEntityChunkDescriptor chunk = descriptors.get(index);
			if (chunk.index() != index || chunk.entryCount() < 0
					|| chunk.entryCount() > CoreSnapshotEntityLimits.MAX_ENTRIES_PER_CHUNK
					|| chunk.compressedByteCount() <= 0
					|| chunk.compressedByteCount() > Math.min(
							properties.getMaxArchiveChunkBytes(),
							CoreSnapshotEntityLimits.MAX_COMPRESSED_CHUNK_BYTES)
					|| chunk.uncompressedByteCount() < CoreSnapshotEntityChunkCodec.HEADER_BYTES
					|| chunk.uncompressedByteCount() > CoreSnapshotEntityLimits.MAX_UNCOMPRESSED_CHUNK_BYTES
					|| previousType != null && chunk.entityType().ordinal() < previousType.ordinal()) {
				throw failure("Entity chunk descriptor is invalid at index " + index);
			}
			try {
				totalEntries = Math.addExact(totalEntries, chunk.entryCount());
				totalCompressedBytes = Math.addExact(totalCompressedBytes, chunk.compressedByteCount());
				totalUncompressedBytes = Math.addExact(totalUncompressedBytes, chunk.uncompressedByteCount());
			} catch (ArithmeticException e) {
				throw failure("Entity chunk descriptor overflow", e);
			}
			if (totalEntries > CoreSnapshotEntityLimits.MAX_TOTAL_ENTRIES
					|| totalCompressedBytes > properties.getMaxArchiveTotalBytes()
					|| totalUncompressedBytes > CoreSnapshotEntityLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
				throw failure("Entity sidecar exceeds configured total limits");
			}
			URI uri = source.resolve(chunksPath + index);
			if (!sameOrigin(source, uri)) {
				throw failure("Entity chunk URL must use the trusted manifest origin");
			}
			result.add(new ResolvedChunk(
					index, uri, chunk.compressedByteCount(), chunk.compressedContentHash()));
			previousType = chunk.entityType();
		}
		return List.copyOf(result);
	}

	private List<ResolvedChunk> validateManifest(URI source, CheckpointSnapshotManifest manifest) {
		if (manifest == null || !SnapshotFormatCompatibility.supportsState(manifest.formatVersion())
				|| manifest.networkCode() != generalProperties.getNetwork().getCode()
				|| manifest.checkpointHeight() < 0 || manifest.checkpointHash() == null
				|| manifest.checkpointStateRoot() == null || manifest.chainIdentity() == null
				|| manifest.headerSegment() == null) {
			throw failure("Snapshot manifest identity/version metadata is invalid");
		}
		if (manifest.headerSegment().headers().size() > CheckpointSnapshotLimits.MAX_HEADER_COUNT) {
			throw failure("Snapshot manifest exceeds header count limit");
		}
		if (manifest.chunks().size() > Math.min(properties.getMaxChunkCount(), CheckpointSnapshotLimits.MAX_CHUNK_COUNT)) {
			throw failure("Snapshot manifest exceeds configured chunk count");
		}
		long totalNodeBytes = 0;
		long totalDownloadBytes = 0;
		List<ResolvedChunk> result = new ArrayList<>(manifest.chunks().size());
		for (int index = 0; index < manifest.chunks().size(); index++) {
			SnapshotChunkDescriptor chunk = manifest.chunks().get(index);
			if (chunk == null || chunk.index() != index || chunk.id() == null || chunk.id().isBlank()
					|| chunk.nodeCount() < 0 || chunk.nodeCount() > CheckpointSnapshotLimits.MAX_NODES_PER_CHUNK
					|| chunk.byteCount() < 0 || chunk.byteCount() > CheckpointSnapshotLimits.MAX_CHUNK_BYTES) {
				throw failure("Snapshot chunk descriptor is invalid at index " + index);
			}
			long encodedByteCount = encodedByteCount(chunk);
			if (encodedByteCount > properties.getMaxChunkBytes()) {
				throw failure("Encoded snapshot chunk exceeds configured byte limit at index " + index);
			}
			URI uri = resolveChunkUri(source, chunk);
			totalNodeBytes = Math.addExact(totalNodeBytes, chunk.byteCount());
			totalDownloadBytes = Math.addExact(totalDownloadBytes, encodedByteCount);
			if (totalNodeBytes > CheckpointSnapshotLimits.MAX_TOTAL_BYTES
					|| totalDownloadBytes > properties.getMaxTotalBytes()) {
				throw failure("Snapshot manifest exceeds configured total byte limit");
			}
			result.add(new ResolvedChunk(index, uri, encodedByteCount, chunk.contentHash()));
		}
		return List.copyOf(result);
	}

	private long encodedByteCount(SnapshotChunkDescriptor chunk) {
		try {
			return Math.addExact(4L, Math.addExact(chunk.byteCount(), Math.multiplyExact(36L, chunk.nodeCount())));
		} catch (ArithmeticException e) {
			throw failure("Encoded snapshot chunk size overflow", e);
		}
	}

	private URI resolveChunkUri(URI source, SnapshotChunkDescriptor chunk) {
		URI uri;
		if (chunk.url() == null || chunk.url().isBlank()) {
			uri = source.resolve(CHUNKS_PATH + chunk.index());
		} else {
			try {
				uri = new URI(chunk.url());
			} catch (URISyntaxException e) {
				throw failure("Snapshot chunk URL is invalid", e);
			}
		}
		if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
				|| !sameOrigin(source, uri)) {
			throw failure("Snapshot chunk URL must use the trusted manifest origin");
		}
		properties.validateTrustedSource(origin(uri));
		return uri;
	}

	private List<Path> downloadChunks(Path staging, List<ResolvedChunk> chunks, String filePrefix) throws Exception {
		if (chunks.isEmpty()) {
			return List.of();
		}
		ExecutorService executor = Executors.newFixedThreadPool(properties.getParallelism());
		Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
		DownloadProgress progress = new DownloadProgress(downloadLabel(filePrefix), chunks);
		progress.started();
		List<Future<Path>> futures = chunks.stream()
				.map(chunk -> executor.submit(() -> {
					Path downloaded = downloadChunk(staging, chunk, filePrefix, activeCalls);
					progress.completed(chunk);
					return downloaded;
				}))
				.toList();
		boolean completed = false;
		try {
			List<Path> paths = new ArrayList<>(futures.size());
			for (Future<Path> future : futures) {
				try {
					paths.add(future.get());
				} catch (ExecutionException e) {
					futures.forEach(item -> item.cancel(true));
					Throwable cause = e.getCause();
					if (cause instanceof SnapshotTransportException transportException) {
						throw transportException;
					}
					throw failure("Snapshot chunk download failed", cause);
				}
			}
			paths.sort(Comparator.comparing(Path::toString));
			completed = true;
			return List.copyOf(paths);
		} finally {
			if (completed) {
				executor.shutdown();
			} else {
				futures.forEach(item -> item.cancel(true));
				activeCalls.forEach(Call::cancel);
				executor.shutdownNow();
			}
			try {
				if (!executor.awaitTermination(WORKER_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
					activeCalls.forEach(Call::cancel);
					executor.shutdownNow();
					throw failure("Snapshot download workers did not terminate after cancellation");
				}
			} catch (InterruptedException e) {
				activeCalls.forEach(Call::cancel);
				executor.shutdownNow();
				Thread.currentThread().interrupt();
				throw failure("Interrupted while waiting for snapshot download workers", e);
			}
		}
	}

	private String downloadLabel(String filePrefix) {
		return switch (filePrefix) {
			case "chunk-" -> "state";
			case "archive-chunk-" -> "block archive";
			case "archive-entity-" -> "entity index";
			default -> "snapshot";
		};
	}

	private Path downloadChunk(
			Path staging, ResolvedChunk chunk, String filePrefix, Set<Call> activeCalls) throws IOException {
		Path target = safeTarget(staging, filePrefix + "%05d.bin".formatted(chunk.index()));
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && verifyFile(target, chunk)) {
				return target;
			}
			Files.delete(target);
		}
		Path partial = safeTarget(staging, target.getFileName() + ".part");
		Files.deleteIfExists(partial);
		Request request = new Request.Builder().url(chunk.uri().toString()).get().build();
		try (TrackedResponse tracked = execute(request, activeCalls);
				ResponseBody body = tracked.response().body()) {
			long configuredChunkLimit = filePrefix.startsWith("archive-")
					? properties.getMaxArchiveChunkBytes() : properties.getMaxChunkBytes();
			if (body == null || body.contentLength() > chunk.encodedByteCount()
					|| body.contentLength() > configuredChunkLimit) {
				throw failure("Snapshot chunk response size is invalid at index " + chunk.index());
			}
			Keccak.Digest256 digest = new Keccak.Digest256();
			long count;
			try (InputStream input = body.byteStream(); OutputStream output = Files.newOutputStream(partial)) {
				count = copyChunk(input, output, digest, chunk.encodedByteCount());
			}
			Hash actualHash = Hash.wrap(digest.digest());
			if (count != chunk.encodedByteCount() || !actualHash.equals(chunk.contentHash())) {
				throw failure("Snapshot chunk size/hash mismatch at index " + chunk.index());
			}
			moveVerified(partial, target);
			return target;
		} catch (RuntimeException | IOException e) {
			Files.deleteIfExists(partial);
			throw e;
		}
	}

	private boolean verifyFile(Path file, ResolvedChunk chunk) throws IOException {
		if (Files.size(file) != chunk.encodedByteCount()) {
			return false;
		}
		Keccak.Digest256 digest = new Keccak.Digest256();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = Files.newInputStream(file)) {
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return Hash.wrap(digest.digest()).equals(chunk.contentHash());
	}

	private TrackedResponse execute(Request request, Set<Call> activeCalls) throws IOException {
		for (int attempt = 1; attempt <= RATE_LIMIT_MAX_ATTEMPTS; attempt++) {
			Call call = httpClient.newCall(request);
			if (activeCalls != null) {
				activeCalls.add(call);
			}
			Response response;
			try {
				response = call.execute();
			} catch (IOException e) {
				removeActiveCall(activeCalls, call);
				throw e;
			}
			if (response.isSuccessful()) {
				return new TrackedResponse(call, response, activeCalls);
			}
			int code = response.code();
			long retryAfterSeconds = retryAfterSeconds(response);
			response.close();
			removeActiveCall(activeCalls, call);
			if (code != 429 || attempt == RATE_LIMIT_MAX_ATTEMPTS) {
				throw failure("Snapshot HTTP request failed with status " + code);
			}
			try {
				TimeUnit.SECONDS.sleep(retryAfterSeconds);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw failure("Interrupted while backing off after snapshot HTTP 429", e);
			}
		}
		throw new IllegalStateException("Unreachable snapshot HTTP retry state");
	}

	private long retryAfterSeconds(Response response) {
		String header = response.header("Retry-After");
		if (header == null) {
			return 1;
		}
		try {
			return Math.min(RATE_LIMIT_MAX_DELAY_SECONDS, Math.max(0, Long.parseLong(header.trim())));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private void removeActiveCall(Set<Call> activeCalls, Call call) {
		if (activeCalls != null) {
			activeCalls.remove(call);
		}
	}

	private void ensureUsableSpace(Path staging, List<ResolvedChunk> chunks) throws IOException {
		long required = 0;
		for (ResolvedChunk chunk : chunks) {
			required = Math.addExact(required, chunk.encodedByteCount());
		}
		long usable = Files.getFileStore(staging).getUsableSpace();
		if (!hasSufficientUsableSpace(required, usable)) {
			throw failure("Snapshot staging filesystem has insufficient usable space: requires "
					+ required + " payload bytes plus safety reserve, has " + usable);
		}
	}

	static boolean hasSufficientUsableSpace(long requiredPayloadBytes, long usableBytes) {
		if (requiredPayloadBytes < 0 || usableBytes < 0) {
			return false;
		}
		long fivePercent = requiredPayloadBytes / 20
				+ (requiredPayloadBytes % 20 == 0 ? 0 : 1);
		long reserve = Math.min(MAX_FREE_SPACE_RESERVE, Math.max(MIN_FREE_SPACE_RESERVE, fivePercent));
		return requiredPayloadBytes <= Long.MAX_VALUE - reserve
				&& requiredPayloadBytes + reserve <= usableBytes;
	}

	private URI validateAllowedSource(URI source) {
		properties.validateTrustedSource(source);
		URI requestedOrigin = origin(source);
		boolean allowed = properties.trustedSources(generalProperties.getNetwork()).stream()
				.map(this::origin)
				.anyMatch(requestedOrigin::equals);
		if (!allowed) {
			throw failure("Snapshot source is not trusted for " + generalProperties.getNetwork());
		}
		return requestedOrigin;
	}

	private URI origin(URI uri) {
		try {
			return new URI(uri.getScheme().toLowerCase(), null, uri.getHost().toLowerCase(), effectivePort(uri),
					"/", null, null);
		} catch (URISyntaxException e) {
			throw failure("Invalid snapshot origin", e);
		}
	}

	private boolean sameOrigin(URI left, URI right) {
		return origin(left).equals(origin(right));
	}

	private int effectivePort(URI uri) {
		if (uri.getPort() >= 0) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private Path prepareDirectory(Path directory) throws IOException {
		if (directory == null) {
			throw failure("Snapshot staging directory is required");
		}
		Path absolute = directory.toAbsolutePath().normalize();
		rejectSymlinkComponents(absolute);
		Files.createDirectories(absolute);
		rejectSymlinkComponents(absolute);
		Path real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
		if (!real.equals(absolute)) {
			throw failure("Snapshot staging directory must resolve without symbolic links");
		}
		return real;
	}

	private void rejectSymlinkComponents(Path absolute) {
		Path current = absolute.getRoot();
		for (Path component : absolute) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw failure("Snapshot staging path must not contain symbolic links");
			}
		}
	}

	private void deleteOwnedStaging(Path directory) {
		if (directory == null) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Best-effort cleanup of a directory created by this client invocation.
				}
			});
		} catch (IOException ignored) {
			// The original transport failure remains authoritative.
		}
	}

	private Path safeTarget(Path staging, String fileName) {
		Path target = staging.resolve(fileName).normalize();
		if (!target.getParent().equals(staging)) {
			throw failure("Snapshot staging path escaped its directory");
		}
		return target;
	}

	private void writeManifest(Path staging, Path target, String partialName, byte[] bytes) throws IOException {
		Path partial = safeTarget(staging, partialName);
		Files.deleteIfExists(partial);
		Files.write(partial, bytes);
		moveVerified(partial, target);
	}

	private void moveVerified(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(source, target, REPLACE_EXISTING);
		}
	}

	private long copyChunk(InputStream input, OutputStream output, Keccak.Digest256 digest, long limit)
			throws IOException {
		byte[] buffer = new byte[64 * 1024];
		long count = 0;
		for (int read; (read = input.read(buffer)) >= 0;) {
			if (read == 0) {
				continue;
			}
			count = Math.addExact(count, read);
			if (count > limit) {
				throw failure("Snapshot chunk exceeded its declared size");
			}
			digest.update(buffer, 0, read);
			output.write(buffer, 0, read);
		}
		return count;
	}

	private void copyBounded(InputStream input, OutputStream output, long limit) throws IOException {
		byte[] buffer = new byte[16 * 1024];
		long count = 0;
		for (int read; (read = input.read(buffer)) >= 0;) {
			if (read == 0) {
				continue;
			}
			count = Math.addExact(count, read);
			if (count > limit) {
				throw failure("Snapshot manifest exceeded configured byte limit");
			}
			output.write(buffer, 0, read);
		}
	}

	private static OkHttpClient client(SnapshotDistributionProperties properties) {
		properties.validate();
		return new OkHttpClient.Builder()
				.followRedirects(false)
				.followSslRedirects(false)
				.connectTimeout(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.readTimeout(properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.callTimeout(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.build();
	}

	private SnapshotTransportException failure(String message) {
		return new SnapshotTransportException(message);
	}

	private SnapshotTransportException failure(String message, Throwable cause) {
		return new SnapshotTransportException(message, cause);
	}

	private SnapshotTransportException aggregateFailure(
			String message, SnapshotTransportException lastFailure) {
		if (lastFailure == null || lastFailure.getMessage() == null || lastFailure.getMessage().isBlank()) {
			return failure(message, lastFailure);
		}
		return failure(message + ": " + lastFailure.getMessage(), lastFailure);
	}

	private record ResolvedChunk(int index, URI uri, long encodedByteCount, Hash contentHash) {
	}

	private record TrackedResponse(Call call, Response response, Set<Call> activeCalls) implements AutoCloseable {

		@Override
		public void close() {
			try {
				response.close();
			} finally {
				if (activeCalls != null) {
					activeCalls.remove(call);
				}
			}
		}
	}

	private static final class DownloadProgress {

		private final String label;
		private final int totalChunks;
		private final long totalBytes;
		private final AtomicInteger completedChunks = new AtomicInteger();
		private final AtomicLong completedBytes = new AtomicLong();
		private final AtomicInteger reportedDecile = new AtomicInteger();

		private DownloadProgress(String label, List<ResolvedChunk> chunks) {
			this.label = label;
			this.totalChunks = chunks.size();
			this.totalBytes = chunks.stream().mapToLong(ResolvedChunk::encodedByteCount).sum();
		}

		private void started() {
			log.info("CORE SNAPSHOT: Downloading {} data: {} chunk(s), {} MiB",
					label, totalChunks, mebibytes(totalBytes));
		}

		private void completed(ResolvedChunk chunk) {
			int chunks = completedChunks.incrementAndGet();
			long bytes = completedBytes.addAndGet(chunk.encodedByteCount());
			int percent = totalChunks == 0 ? 100 : Math.min(100, chunks * 100 / totalChunks);
			int decile = percent / 10;
			int previous = reportedDecile.get();
			if ((chunks == totalChunks || decile > previous) && reportedDecile.compareAndSet(previous, decile)) {
				log.info("CORE SNAPSHOT: Download {} progress: {}/{} chunks ({}%), {}/{} MiB",
						label, chunks, totalChunks, percent, mebibytes(bytes), mebibytes(totalBytes));
			}
		}

		private static String mebibytes(long bytes) {
			return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
		}
	}

	private record FullArchiveCandidate(
			URI source,
			byte[] stateManifestBytes,
			SnapshotTransportManifest stateEnvelope,
			CheckpointSnapshotManifest stateManifest,
			List<ResolvedChunk> stateChunks,
			byte[] archiveManifestBytes,
			CoreSnapshotArchiveTransportManifest archiveEnvelope,
			CoreSnapshotArchiveManifest archiveManifest,
			List<ResolvedChunk> blockChunks,
			List<ResolvedChunk> entityChunks) {
	}

	record TrustedSnapshotCandidate(URI source, long height, Hash hash) {
		TrustedSnapshotCandidate {
			if (source == null || height < 0 || hash == null) {
				throw new IllegalArgumentException("Trusted snapshot candidate is invalid");
			}
		}
	}

	@FunctionalInterface
	interface ManifestPreflight {
		void verify(CheckpointSnapshotManifest manifest);
	}
}
