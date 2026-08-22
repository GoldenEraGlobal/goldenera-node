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
package global.goldenera.node.explorer.snapshot;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.Network;
import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.sync.snapshot.SnapshotFormatCompatibility;
import global.goldenera.node.shared.properties.GeneralProperties;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Service
public class HttpExplorerSnapshotRemoteSource implements ExplorerSnapshotRemoteSource {

	public static final String MANIFEST_PATH =
			"/api/core/v1/sync/snapshots/checkpoint/explorer/manifest";
	public static final String CHUNKS_PATH =
			"/api/core/v1/sync/snapshots/checkpoint/explorer/chunks/";
	public static final String VERSIONS_PATH = "/api/core/v1/sync/snapshots/checkpoint/versions/";

	private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
	private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024 + 64L * 1024;
	private static final int MAX_CHUNKS = 100_000;
	private static final long MAX_ROWS = 2_000_000_000L;

	private final ObjectMapper objectMapper;
	private final SnapshotDistributionProperties properties;
	private final GeneralProperties generalProperties;
	private final OkHttpClient httpClient;

	@Autowired
	public HttpExplorerSnapshotRemoteSource(
			ObjectMapper objectMapper,
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties) {
		this(objectMapper, properties, generalProperties, createClient(properties));
	}

	HttpExplorerSnapshotRemoteSource(
			ObjectMapper objectMapper,
			SnapshotDistributionProperties properties,
			GeneralProperties generalProperties,
			OkHttpClient httpClient) {
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.generalProperties = generalProperties;
		this.httpClient = httpClient;
	}

	@Override
	public StagedExplorerSnapshotDownload stageFromFirstTrustedSource(
			ExplorerSnapshotBinding expectedBinding) {
		properties.validate();
		if (!properties.isBootstrapEnabled()) {
			throw failure("Explorer snapshot bootstrap is disabled");
		}
		Network network = generalProperties.getNetwork();
		if (expectedBinding.carrierNetworkCode() != network.getCode()) {
			throw failure("Explorer snapshot binding does not match the configured network");
		}
		List<URI> sources = properties.trustedSources(network);
		if (sources.isEmpty()) {
			throw failure("No trusted explorer snapshot source configured for " + network);
		}
		ExplorerSnapshotException lastFailure = null;
		for (URI source : sources) {
			Path directory = null;
			try {
				directory = createStagingDirectory();
				return stage(source, expectedBinding, directory);
			} catch (IOException | ExplorerSnapshotException e) {
				lastFailure = e instanceof ExplorerSnapshotException snapshotException
						? snapshotException
						: failure("Could not stage explorer snapshot from " + source, e);
				deleteOwnedDirectory(directory);
			}
		}
		throw failure("All trusted explorer snapshot sources failed for " + network, lastFailure);
	}

	StagedExplorerSnapshotDownload stage(
			URI trustedSource,
			ExplorerSnapshotBinding expectedBinding,
			Path stagingDirectory) {
		URI source = validateAllowedSource(trustedSource);
		try {
			Path staging = prepareDirectory(stagingDirectory);
			String versionBase = VERSIONS_PATH + version(expectedBinding) + "/explorer/";
			byte[] manifestBytes;
			String chunksPath;
			try {
				manifestBytes = downloadBounded(
						source.resolve(versionBase + "manifest"),
						Math.min(properties.getMaxManifestBytes(), MAX_MANIFEST_BYTES),
						"Versioned explorer snapshot manifest");
				chunksPath = versionBase + "chunks/";
			} catch (ExplorerSnapshotException unavailableVersionedEndpoint) {
				manifestBytes = downloadBounded(
						source.resolve(MANIFEST_PATH),
						Math.min(properties.getMaxManifestBytes(), MAX_MANIFEST_BYTES),
						"Explorer snapshot manifest");
				chunksPath = CHUNKS_PATH;
			}
			ExplorerSnapshotManifest manifest = new ExplorerSnapshotManifestCodec(objectMapper)
					.decode(manifestBytes);
			validateManifest(manifest, expectedBinding);
			writeVerified(staging.resolve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME), manifestBytes);
			for (ExplorerSnapshotChunkDescriptor chunk : manifest.chunks()) {
				downloadChunk(source, staging, chunk, chunksPath);
				ExplorerSnapshotChunkCodec.verifyFormatHeader(
						Files.readAllBytes(staging.resolve(chunk.fileName())), manifest.formatVersion());
			}
			return new StagedExplorerSnapshotDownload(staging, manifest);
		} catch (ExplorerSnapshotException e) {
			throw e;
		} catch (Exception e) {
			throw failure("Explorer snapshot staging failed: " + e.getMessage(), e);
		}
	}

	private String version(ExplorerSnapshotBinding binding) {
		return SnapshotFormatCompatibility.currentVersionName(
				binding.checkpointHeight(), Hash.fromHexString(binding.checkpointHash()));
	}

	private void validateManifest(
			ExplorerSnapshotManifest manifest,
			ExplorerSnapshotBinding expectedBinding) {
		ExplorerSnapshotManifestCodec codec = new ExplorerSnapshotManifestCodec(objectMapper);
		if (manifest == null || !SnapshotFormatCompatibility.supportsExplorer(manifest.formatVersion())
				|| !codec.hasValidSigningHash(manifest)) {
			throw failure("Explorer snapshot manifest signing hash or version is invalid");
		}
		ExplorerSnapshotBinding actualBinding;
		try {
			actualBinding = new ExplorerSnapshotBinding(
					manifest.carrierNetworkCode(), manifest.chainId(), manifest.genesisHash(),
					manifest.checkpointHeight(), manifest.checkpointHash(), manifest.checkpointStateRoot(),
					manifest.coreStateSigningHash(), manifest.coreArchiveSigningHash());
		} catch (IllegalArgumentException e) {
			throw failure("Explorer snapshot manifest binding is invalid", e);
		}
		if (!actualBinding.equals(expectedBinding)) {
			throw failure("Explorer snapshot is not bound to the activated core checkpoint");
		}
		if (manifest.chunks() == null || manifest.chunks().isEmpty()
				|| manifest.chunks().size() > MAX_CHUNKS) {
			throw failure("Explorer snapshot manifest has an invalid chunk count");
		}
		Set<String> expectedTableNames = EnumSet.allOf(ExplorerSnapshotTable.class).stream()
				.map(ExplorerSnapshotTable::tableName)
				.collect(Collectors.toUnmodifiableSet());
		if (manifest.tableSchemaVersions() == null || manifest.tableRowCounts() == null
				|| !manifest.tableSchemaVersions().keySet().equals(expectedTableNames)
				|| !manifest.tableRowCounts().keySet().equals(expectedTableNames)
				|| manifest.tableSchemaVersions().values().stream()
						.anyMatch(version -> version == null || version != ExplorerSnapshotTable.SCHEMA_VERSION)
				|| manifest.tableRowCounts().values().stream()
						.anyMatch(count -> count == null || count < 0 || count > MAX_ROWS)) {
			throw failure("Explorer snapshot table coverage or count metadata is invalid");
		}
		Map<ExplorerSnapshotTable, Integer> nextIndexes = new EnumMap<>(ExplorerSnapshotTable.class);
		Map<ExplorerSnapshotTable, Long> chunkRowCounts = new EnumMap<>(ExplorerSnapshotTable.class);
		Set<String> fileNames = new HashSet<>();
		long totalBytes = 0;
		for (ExplorerSnapshotChunkDescriptor chunk : manifest.chunks()) {
			if (chunk == null || chunk.table() == null || chunk.index() < 0
					|| chunk.index() != nextIndexes.getOrDefault(chunk.table(), 0)
					|| chunk.tableSchemaVersion() != ExplorerSnapshotTable.SCHEMA_VERSION
					|| chunk.rowCount() < 0 || chunk.rowCount() > MAX_ROWS
					|| chunk.uncompressedSize() < 0 || chunk.uncompressedSize() > MAX_CHUNK_BYTES
					|| !expectedFileName(chunk).equals(chunk.fileName())
					|| !fileNames.add(chunk.fileName())
					|| chunk.sha256() == null || !chunk.sha256().matches("[0-9a-f]{64}")) {
				throw failure("Explorer snapshot chunk descriptor is invalid");
			}
			nextIndexes.put(chunk.table(), chunk.index() + 1);
			try {
				totalBytes = Math.addExact(totalBytes, chunk.uncompressedSize());
				chunkRowCounts.merge(chunk.table(), chunk.rowCount(), Math::addExact);
			} catch (ArithmeticException e) {
				throw failure("Explorer snapshot total byte or row count overflow", e);
			}
			if (totalBytes > properties.getMaxArchiveTotalBytes()) {
				throw failure("Explorer snapshot exceeds the configured total byte limit");
			}
		}
		for (ExplorerSnapshotTable table : ExplorerSnapshotTable.values()) {
			if (!nextIndexes.containsKey(table)
					|| !manifest.tableRowCounts().get(table.tableName())
							.equals(chunkRowCounts.getOrDefault(table, 0L))) {
				throw failure("Explorer snapshot table chunk coverage is invalid for " + table.tableName());
			}
		}
	}

	private void downloadChunk(
			URI source,
			Path staging,
			ExplorerSnapshotChunkDescriptor chunk,
			String chunksPath) throws IOException {
		URI uri = source.resolve(chunksPath + chunk.fileName());
		if (!sameOrigin(source, uri)) {
			throw failure("Explorer snapshot chunk URL left the trusted origin");
		}
		Path target = safeTarget(staging, chunk.fileName());
		if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
				&& Files.size(target) == chunk.uncompressedSize()
				&& ExplorerSnapshotDigests.sha256(target).equals(chunk.sha256())) {
			return;
		}
		Files.deleteIfExists(target);
		Path partial = safeTarget(staging, chunk.fileName() + ".part");
		Files.deleteIfExists(partial);
		try (Response response = execute(uri); ResponseBody body = response.body()) {
			if (body == null || body.contentLength() > chunk.uncompressedSize()
					|| body.contentLength() > MAX_CHUNK_BYTES) {
				throw failure("Explorer snapshot chunk response size is invalid: " + chunk.fileName());
			}
			MessageDigest digest = sha256();
			long count;
			try (InputStream input = body.byteStream(); OutputStream output = Files.newOutputStream(partial)) {
				count = copy(input, output, digest, chunk.uncompressedSize());
			}
			String actualHash = HexFormat.of().formatHex(digest.digest());
			if (count != chunk.uncompressedSize() || !actualHash.equals(chunk.sha256())) {
				throw failure("Explorer snapshot chunk size/hash mismatch: " + chunk.fileName());
			}
			moveVerified(partial, target);
		} catch (RuntimeException | IOException e) {
			Files.deleteIfExists(partial);
			throw e;
		}
	}

	private byte[] downloadBounded(URI uri, long limit, String label) throws IOException {
		try (Response response = execute(uri); ResponseBody body = response.body()) {
			if (body == null || body.contentLength() > limit) {
				throw failure(label + " exceeds the configured byte limit");
			}
			try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				copy(input, output, null, limit);
				return output.toByteArray();
			}
		}
	}

	private long copy(InputStream input, OutputStream output, MessageDigest digest, long limit)
			throws IOException {
		byte[] buffer = new byte[64 * 1024];
		long count = 0;
		for (int read; (read = input.read(buffer)) >= 0;) {
			if (read == 0) {
				continue;
			}
			count = Math.addExact(count, read);
			if (count > limit) {
				throw failure("Explorer snapshot response exceeded its declared size");
			}
			if (digest != null) {
				digest.update(buffer, 0, read);
			}
			output.write(buffer, 0, read);
		}
		return count;
	}

	private Response execute(URI uri) throws IOException {
		Response response = httpClient.newCall(new Request.Builder().url(uri.toString()).get().build()).execute();
		if (!response.isSuccessful()) {
			int status = response.code();
			response.close();
			throw failure("Explorer snapshot HTTP request failed with status " + status);
		}
		return response;
	}

	private Path createStagingDirectory() throws IOException {
		if (properties.getStagingDirectory() == null) {
			return Files.createTempDirectory("goldenera-explorer-snapshot-").toRealPath();
		}
		Path base = prepareDirectory(properties.getStagingDirectory());
		return Files.createTempDirectory(base, "explorer-").toRealPath();
	}

	private Path prepareDirectory(Path directory) throws IOException {
		Path absolute = directory.toAbsolutePath().normalize();
		rejectSymlinkComponents(absolute);
		Files.createDirectories(absolute);
		rejectSymlinkComponents(absolute);
		Path real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
		if (!real.equals(absolute)) {
			throw failure("Explorer snapshot staging directory must not contain symbolic links");
		}
		return real;
	}

	private void rejectSymlinkComponents(Path absolute) {
		Path current = absolute.getRoot();
		for (Path component : absolute) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw failure("Explorer snapshot staging path must not contain symbolic links");
			}
		}
	}

	private Path safeTarget(Path staging, String fileName) {
		Path target = staging.resolve(fileName).normalize();
		if (!target.getParent().equals(staging)) {
			throw failure("Explorer snapshot staging path escaped its directory");
		}
		return target;
	}

	private void writeVerified(Path target, byte[] bytes) throws IOException {
		Path partial = safeTarget(target.getParent(), target.getFileName() + ".part");
		Files.deleteIfExists(partial);
		Files.write(partial, bytes);
		moveVerified(partial, target);
	}

	private void moveVerified(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, REPLACE_EXISTING);
		}
	}

	private URI validateAllowedSource(URI source) {
		properties.validateTrustedSource(source);
		URI requestedOrigin = origin(source);
		boolean allowed = properties.trustedSources(generalProperties.getNetwork()).stream()
				.map(this::origin)
				.anyMatch(requestedOrigin::equals);
		if (!allowed) {
			throw failure("Explorer snapshot source is not trusted for " + generalProperties.getNetwork());
		}
		return requestedOrigin;
	}

	private URI origin(URI uri) {
		try {
			return new URI(uri.getScheme().toLowerCase(Locale.ROOT), null,
					uri.getHost().toLowerCase(Locale.ROOT), effectivePort(uri), "/", null, null);
		} catch (URISyntaxException e) {
			throw failure("Invalid explorer snapshot origin", e);
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

	private static String expectedFileName(ExplorerSnapshotChunkDescriptor chunk) {
		return "explorer-" + chunk.table().name().toLowerCase(Locale.ROOT) + "-" + chunk.index() + ".bin";
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static OkHttpClient createClient(SnapshotDistributionProperties properties) {
		properties.validate();
		return new OkHttpClient.Builder()
				.followRedirects(false)
				.followSslRedirects(false)
				.connectTimeout(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.readTimeout(properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.callTimeout(properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.build();
	}

	private void deleteOwnedDirectory(Path directory) {
		if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// Preserve the transport failure that caused cleanup.
		}
	}

	private ExplorerSnapshotException failure(String message) {
		return new ExplorerSnapshotException(message);
	}

	private ExplorerSnapshotException failure(String message, Throwable cause) {
		return new ExplorerSnapshotException(message, cause);
	}
}
