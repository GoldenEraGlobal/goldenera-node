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
package global.goldenera.node.core.api.v1.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static java.nio.file.StandardOpenOption.READ;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import global.goldenera.node.core.properties.SnapshotDistributionProperties;
import global.goldenera.node.core.sync.snapshot.publication.SnapshotPublicationDirectorySelector;
import global.goldenera.node.explorer.snapshot.ExplorerCheckpointSnapshotExporter;
import global.goldenera.node.explorer.snapshot.ExplorerSnapshotTable;

/** Serves only immutable, pre-created snapshot artifacts from an opt-in directory. */
@RestController
@RequestMapping("/api/core/v1/sync/snapshots/checkpoint")
public class CheckpointSnapshotApiV1 {
	private static final long MAX_EXPLORER_CHUNK_BYTES = 64L * 1024 * 1024 + 64L * 1024;

	private final SnapshotDistributionProperties properties;
	private final SnapshotStreamLimiter streamLimiter;
	private final SnapshotPublicationDirectorySelector publicationSelector;

	public CheckpointSnapshotApiV1(SnapshotDistributionProperties properties) {
		this(properties, new SnapshotStreamLimiter(properties));
	}

	@Autowired
	public CheckpointSnapshotApiV1(
			SnapshotDistributionProperties properties,
			SnapshotStreamLimiter streamLimiter) {
		this.properties = properties;
		this.streamLimiter = streamLimiter;
		this.publicationSelector = new SnapshotPublicationDirectorySelector();
	}

	@GetMapping("/manifest")
	public ResponseEntity<StreamingResponseBody> manifest() {
		return serve("manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/chunks/{index}")
	public ResponseEntity<StreamingResponseBody> chunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("chunk-%05d.bin".formatted(parsed), properties.getMaxChunkBytes(), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/archive/manifest")
	public ResponseEntity<StreamingResponseBody> archiveManifest() {
		return serve("archive-manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/archive/chunks/{index}")
	public ResponseEntity<StreamingResponseBody> archiveChunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("archive-chunk-%05d.bin".formatted(parsed), properties.getMaxArchiveChunkBytes(), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/archive/entities/{index}")
	public ResponseEntity<StreamingResponseBody> archiveEntityChunk(@PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		if (parsed == null) {
			return ResponseEntity.notFound().build();
		}
		return serve("entity-chunk-%05d.zst".formatted(parsed), properties.getMaxArchiveChunkBytes(), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/explorer/manifest")
	public ResponseEntity<StreamingResponseBody> explorerManifest() {
		return serve(ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME,
				properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/explorer/chunks/{fileName}")
	public ResponseEntity<StreamingResponseBody> explorerChunk(@PathVariable String fileName) {
		if (!isExplorerChunkFileName(fileName)) {
			return ResponseEntity.notFound().build();
		}
		return serve(fileName, Math.min(properties.getMaxArchiveChunkBytes(), MAX_EXPLORER_CHUNK_BYTES), false,
				MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/manifest")
	public ResponseEntity<StreamingResponseBody> versionManifest(@PathVariable String version) {
		return serveVersion(version, "manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/versions/{version}/chunks/{index}")
	public ResponseEntity<StreamingResponseBody> versionChunk(
			@PathVariable String version, @PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxChunkCount());
		return parsed == null ? ResponseEntity.notFound().build()
				: serveVersion(version, "chunk-%05d.bin".formatted(parsed), properties.getMaxChunkBytes(), true,
						MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/archive/manifest")
	public ResponseEntity<StreamingResponseBody> versionArchiveManifest(@PathVariable String version) {
		return serveVersion(
				version, "archive-manifest.json", properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/versions/{version}/archive/chunks/{index}")
	public ResponseEntity<StreamingResponseBody> versionArchiveChunk(
			@PathVariable String version, @PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		return parsed == null ? ResponseEntity.notFound().build()
				: serveVersion(version, "archive-chunk-%05d.bin".formatted(parsed),
						properties.getMaxArchiveChunkBytes(), true, MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/archive/entities/{index}")
	public ResponseEntity<StreamingResponseBody> versionArchiveEntityChunk(
			@PathVariable String version, @PathVariable String index) {
		Integer parsed = parseIndex(index, properties.getMaxArchiveChunkCount());
		return parsed == null ? ResponseEntity.notFound().build()
				: serveVersion(version, "entity-chunk-%05d.zst".formatted(parsed),
						properties.getMaxArchiveChunkBytes(), true, MediaType.APPLICATION_OCTET_STREAM);
	}

	@GetMapping("/versions/{version}/explorer/manifest")
	public ResponseEntity<StreamingResponseBody> versionExplorerManifest(@PathVariable String version) {
		return serveVersion(version, ExplorerCheckpointSnapshotExporter.MANIFEST_FILE_NAME,
				properties.getMaxManifestBytes(), false, MediaType.APPLICATION_JSON);
	}

	@GetMapping("/versions/{version}/explorer/chunks/{fileName}")
	public ResponseEntity<StreamingResponseBody> versionExplorerChunk(
			@PathVariable String version, @PathVariable String fileName) {
		return !isExplorerChunkFileName(fileName) ? ResponseEntity.notFound().build()
				: serveVersion(version, fileName,
						Math.min(properties.getMaxArchiveChunkBytes(), MAX_EXPLORER_CHUNK_BYTES), true,
						MediaType.APPLICATION_OCTET_STREAM);
	}

	private Integer parseIndex(String index, int limit) {
		if (index == null || !index.matches("0|[1-9][0-9]{0,4}")) {
			return null;
		}
		int parsed = Integer.parseInt(index);
		return parsed < limit ? parsed : null;
	}

	private boolean isExplorerChunkFileName(String fileName) {
		if (fileName == null || !fileName.matches("explorer-[a-z_]+-(0|[1-9][0-9]{0,4})\\.bin")) {
			return false;
		}
		int tableEnd = fileName.lastIndexOf('-');
		String table = fileName.substring("explorer-".length(), tableEnd).toUpperCase(Locale.ROOT);
		try {
			ExplorerSnapshotTable.valueOf(table);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private ResponseEntity<StreamingResponseBody> serve(
			String fileName, long maxBytes, boolean immutable, MediaType contentType) {
		return serveFromBase(null, fileName, maxBytes, immutable, contentType);
	}

	private ResponseEntity<StreamingResponseBody> serveVersion(
			String version, String fileName, long maxBytes, boolean immutable, MediaType contentType) {
		return serveFromBase(version, fileName, maxBytes, immutable, contentType);
	}

	private ResponseEntity<StreamingResponseBody> serveFromBase(
			String version, String fileName, long maxBytes, boolean immutable, MediaType contentType) {
		if (!properties.isPublishEnabled() || properties.getPublishDirectory() == null) {
			return ResponseEntity.notFound().build();
		}
		try {
			properties.validate();
			Path base = version == null
					? publicationSelector.resolve(properties.getPublishDirectory()).orElse(null)
					: publicationSelector.resolveVersion(properties.getPublishDirectory(), version).orElse(null);
			if (base == null) {
				return ResponseEntity.notFound().build();
			}
			Path candidate = base.resolve(fileName).normalize();
			if (!candidate.getParent().equals(base) || Files.isSymbolicLink(candidate)
					|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
				return ResponseEntity.notFound().build();
			}
			Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
			FileChannel channel = FileChannel.open(real, READ, LinkOption.NOFOLLOW_LINKS);
			long size = channel.size();
			if (!real.getParent().equals(base) || size < 0 || size > maxBytes) {
				channel.close();
				return ResponseEntity.notFound().build();
			}
			SnapshotStreamLimiter.Lease streamLease = streamLimiter.tryAcquire();
			if (streamLease == null) {
				channel.close();
				return ResponseEntity.status(429).header("Retry-After", "1").build();
			}
			StreamingResponseBody body = output -> {
				try (streamLease; channel; InputStream input = Channels.newInputStream(channel)) {
					input.transferTo(output);
				}
			};
			try {
				return ResponseEntity.ok()
						.contentType(contentType)
						.contentLength(size)
						.cacheControl(immutable
								? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
								: CacheControl.noCache())
						.body(body);
			} catch (RuntimeException e) {
				streamLease.close();
				channel.close();
				throw e;
			}
		} catch (IOException | RuntimeException e) {
			return ResponseEntity.notFound().build();
		}
	}

}
